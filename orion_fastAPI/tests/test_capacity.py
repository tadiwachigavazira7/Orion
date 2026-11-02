"""Subscription status and device capacity enforcement in POST /enrollment."""

import asyncio
import threading
from datetime import timedelta

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from orion_backend.db.models import DeviceStatus, SubscriptionStatus
from orion_backend.errors import CapacityBelowActiveDevicesError, InvalidEnrollmentAuthorizationError
from orion_backend.services import admin_service, enrollment_service
from orion_backend.services.credential_hasher import Argon2CredentialHasher
from orion_backend.services.rate_limit_service import RateLimitPolicy
from tests.helpers import (
    TEST_ACTOR,
    Store,
    count_devices,
    create_store,
    enroll_at,
    fetch_device,
    fetch_subscription,
    make_client,
    rotate_code,
    verify,
)


async def _fill(client: AsyncClient, store: Store, count: int, prefix: str = "PDT") -> list[str]:
    credentials = []
    for i in range(count):
        response = await enroll_at(client, store, f"{prefix}-{i}")
        assert response.status_code == 201, response.json()
        credentials.append(response.json()["enrollment_credential"])
    return credentials


def _assert_limit_reached(response) -> None:
    assert response.status_code == 409
    assert response.json()["error_code"] == "device_limit_reached"
    assert "enrollment_credential" not in response.json()


async def test_enrollment_below_capacity_and_sixth_device_succeed(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session)

    await _fill(client, store, 6)

    assert await count_devices(db_session, store.site_id) == 6


async def test_seventh_device_is_rejected_without_credential_row_or_slot(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session)
    await _fill(client, store, 6)

    response = await enroll_at(client, store, "PDT-7th")

    _assert_limit_reached(response)
    assert await fetch_device(db_session, "PDT-7th") is None
    assert await count_devices(db_session, store.site_id) == 6
    subscription = await fetch_subscription(db_session, store.site_id)
    assert (subscription.included_device_slots, subscription.additional_device_slots) == (6, 0)


async def test_increasing_additional_slots_allows_more_devices(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session)
    await _fill(client, store, 6)
    _assert_limit_reached(await enroll_at(client, store, "PDT-7"))

    await admin_service.set_additional_slots(
        db_session, store.organization_code, store.site_code, 2, TEST_ACTOR
    )

    assert (await enroll_at(client, store, "PDT-7")).status_code == 201
    assert (await enroll_at(client, store, "PDT-8")).status_code == 201
    _assert_limit_reached(await enroll_at(client, store, "PDT-9"))


@pytest.mark.parametrize("status", [SubscriptionStatus.INACTIVE, SubscriptionStatus.SUSPENDED])
async def test_inactive_or_suspended_subscription_rejects_enrollment_after_code_check(
    status: SubscriptionStatus, client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session, status=status)

    right_code = await enroll_at(client, store, "PDT-s1")
    wrong_code = await enroll_at(client, store, "PDT-s2", code="AAAAA-AAAAA-AAAAA-AAAAA")

    assert right_code.status_code == 403
    assert right_code.json()["error_code"] == "subscription_inactive"
    # Without the code the caller learns nothing about the subscription.
    assert wrong_code.status_code == 403
    assert wrong_code.json()["error_code"] == "invalid_enrollment_authorization"
    assert await count_devices(db_session, store.site_id) == 0


async def test_active_subscription_allows_enrollment(client: AsyncClient, db_session: AsyncSession):
    store = await create_store(db_session, status=SubscriptionStatus.ACTIVE)

    assert (await enroll_at(client, store, "PDT-a1")).status_code == 201


async def test_suspension_blocks_new_enrollment_but_not_verification(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session)
    credential = (await _fill(client, store, 1))[0]

    await admin_service.set_subscription_status(
        db_session, store.organization_code, store.site_code, SubscriptionStatus.SUSPENDED, TEST_ACTOR
    )

    assert (await verify(client, "PDT-0", credential)).status_code == 200
    assert (await enroll_at(client, store, "PDT-new")).json()["error_code"] == "subscription_inactive"


async def test_revocation_frees_a_slot(client: AsyncClient, db_session: AsyncSession):
    store = await create_store(db_session)
    await _fill(client, store, 6)
    _assert_limit_reached(await enroll_at(client, store, "PDT-extra"))

    await admin_service.revoke_device(db_session, "PDT-0", TEST_ACTOR)

    assert (await enroll_at(client, store, "PDT-extra")).status_code == 201
    subscription = await fetch_subscription(db_session, store.site_id)
    assert subscription.total_authorized_slots == 6  # revoke never changes capacity


async def test_reset_device_reenrollment_consumes_a_slot_and_respects_capacity(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session)
    await _fill(client, store, 6)

    # Reset frees PDT-0's slot; another device takes it.
    await admin_service.reset_device(db_session, "PDT-0", TEST_ACTOR)
    assert (await enroll_at(client, store, "PDT-taker")).status_code == 201

    # Now the site is full again, so the reset device cannot re-enroll ...
    _assert_limit_reached(await enroll_at(client, store, "PDT-0"))
    assert (await fetch_device(db_session, "PDT-0")).status == DeviceStatus.PENDING_ENROLLMENT

    # ... until a slot is freed; then re-enrollment takes it.
    await admin_service.revoke_device(db_session, "PDT-taker", TEST_ACTOR)
    response = await enroll_at(client, store, "PDT-0")
    assert response.status_code == 201
    assert (await fetch_device(db_session, "PDT-0")).status == DeviceStatus.ACTIVE
    _assert_limit_reached(await enroll_at(client, store, "PDT-another"))


async def test_capacity_reduction_below_active_devices_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session, additional_slots=2)
    await _fill(client, store, 7)

    with pytest.raises(CapacityBelowActiveDevicesError):
        await admin_service.set_additional_slots(
            db_session, store.organization_code, store.site_code, 0, TEST_ACTOR
        )
    assert (await fetch_subscription(db_session, store.site_id)).additional_device_slots == 2

    # Reducing to exactly the active count is allowed.
    await admin_service.set_additional_slots(
        db_session, store.organization_code, store.site_code, 1, TEST_ACTOR
    )
    assert (await fetch_subscription(db_session, store.site_id)).total_authorized_slots == 7
    _assert_limit_reached(await enroll_at(client, store, "PDT-over"))


async def test_concurrent_enrollments_at_the_boundary_yield_exactly_one_success(
    client: AsyncClient,
    db_session: AsyncSession,
    sessionmaker_: async_sessionmaker[AsyncSession],
):
    store = await create_store(db_session)
    await _fill(client, store, 5)

    # Distinct devices, separate app instances; every request has its own
    # connection, so the race is decided by the subscription row lock.
    clients = [make_client(sessionmaker_) for _ in range(6)]
    try:
        responses = await asyncio.gather(
            *(enroll_at(c, store, f"PDT-race-{i}") for i, c in enumerate(clients))
        )
    finally:
        for c in clients:
            await c.aclose()

    statuses = sorted(r.status_code for r in responses)
    assert statuses == [201, 409, 409, 409, 409, 409], statuses
    for r in responses:
        if r.status_code == 409:
            _assert_limit_reached(r)
    assert await count_devices(db_session, store.site_id) == 6


async def test_concurrent_enrollment_of_same_new_device_yields_one_credential(
    client: AsyncClient,
    db_session: AsyncSession,
    sessionmaker_: async_sessionmaker[AsyncSession],
):
    store = await create_store(db_session)
    clients = [make_client(sessionmaker_) for _ in range(4)]
    try:
        responses = await asyncio.gather(*(enroll_at(c, store, "PDT-same") for c in clients))
    finally:
        for c in clients:
            await c.aclose()

    assert sorted(r.status_code for r in responses) == [201, 409, 409, 409]
    credential = next(r for r in responses if r.status_code == 201).json()["enrollment_credential"]
    assert (await verify(client, "PDT-same", credential)).status_code == 200
    assert await count_devices(db_session, store.site_id) == 1


class GatedHasher(Argon2CredentialHasher):
    """Pauses after verifying the store code so a test can commit an operator
    change before the enrollment transaction runs."""

    def __init__(self) -> None:
        super().__init__()
        self.entered = threading.Event()
        self.release = threading.Event()

    def verify(self, credential: str, credential_hash: str) -> bool:
        result = super().verify(credential, credential_hash)
        self.entered.set()
        assert self.release.wait(10)
        return result


async def _enroll_racing(sessionmaker_, store: Store, device_id: str, operator_action):
    hasher = GatedHasher()

    async def _enroll():
        async with sessionmaker_() as session:
            return await enrollment_service.enroll_device_at_site(
                session,
                hasher,
                store.organization_code,
                store.site_code,
                device_id,
                store.code,
                client_ip="127.0.0.1",
                rate_limit=RateLimitPolicy(timedelta(minutes=15), 5, 50),
                offline_authorization_hours=24,
            )

    task = asyncio.create_task(_enroll())
    try:
        assert await asyncio.to_thread(hasher.entered.wait, 10)
        # Must not block: no lock is held while the code is checked.
        await asyncio.wait_for(operator_action(), timeout=5)
    finally:
        hasher.release.set()
    return task


async def test_rotation_during_enrollment_rejects_the_old_code(
    db_session: AsyncSession, sessionmaker_: async_sessionmaker[AsyncSession]
):
    store = await create_store(db_session)

    task = await _enroll_racing(
        sessionmaker_, store, "PDT-rot", lambda: rotate_code(db_session, store.organization_code, store.site_code)
    )

    with pytest.raises(InvalidEnrollmentAuthorizationError):
        await asyncio.wait_for(task, timeout=10)
    assert await fetch_device(db_session, "PDT-rot") is None


async def test_suspension_during_enrollment_is_honored(
    db_session: AsyncSession, sessionmaker_: async_sessionmaker[AsyncSession]
):
    from orion_backend.errors import SubscriptionInactiveError

    store = await create_store(db_session)

    task = await _enroll_racing(
        sessionmaker_,
        store,
        "PDT-susp",
        lambda: admin_service.set_subscription_status(
            db_session, store.organization_code, store.site_code, SubscriptionStatus.SUSPENDED, TEST_ACTOR
        ),
    )

    with pytest.raises(SubscriptionInactiveError):
        await asyncio.wait_for(task, timeout=10)
    assert await fetch_device(db_session, "PDT-susp") is None
