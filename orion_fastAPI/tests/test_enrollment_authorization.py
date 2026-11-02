"""Enrollment-authorization rules: scope, expiry, single use, atomic and
concurrent consumption, and that failed enrollment does not consume."""

import asyncio
from datetime import UTC, datetime

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from orion_backend.db.models import DeviceStatus, EnrolledDevice, EnrollmentAuthorization, Site
from orion_backend.errors import DeviceResetRequiredError
from orion_backend.main import create_app
from orion_backend.services import (
    admin_service,
    enrollment_authorization_service,
    enrollment_service,
)
from orion_backend.services.credential_hasher import Argon2CredentialHasher
from orion_backend.services.enrollment_authorization_service import hash_authorization_secret
from tests.conftest import make_settings
from tests.helpers import (
    create_organization_and_site,
    enroll,
    expire_authorization,
    fetch_authorization,
    fetch_device,
    issue_authorization,
    verify,
)

INVALID = "invalid_enrollment_authorization"


def _assert_generic_rejection(response) -> None:
    assert response.status_code == 403
    body = response.json()
    assert body["error_code"] == INVALID
    # The message never reveals which check failed.
    assert body["detail"] == "Enrollment authorization is invalid, expired, or already used."


async def test_unknown_authorization_is_rejected(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    response = await enroll(client, "PDT-30", "made-up-authorization")

    _assert_generic_rejection(response)
    assert await fetch_device(db_session, "PDT-30") is None


async def test_expired_authorization_is_rejected(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-31")
    await expire_authorization(db_session, secret)

    response = await enroll(client, "PDT-31", secret)

    _assert_generic_rejection(response)
    assert await fetch_device(db_session, "PDT-31") is None
    assert (await fetch_authorization(db_session, secret)).consumed_at is None


async def test_reused_authorization_is_rejected_after_reset(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-32")
    assert (await enroll(client, "PDT-32", secret)).status_code == 201

    # Reset makes the device enrollable again; the consumed authorization
    # must still not work a second time.
    await admin_service.reset_device(db_session, "PDT-32")
    response = await enroll(client, "PDT-32", secret)

    _assert_generic_rejection(response)
    assert (await fetch_device(db_session, "PDT-32")).status == DeviceStatus.PENDING_ENROLLMENT


async def test_authorization_for_another_device_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-33-intended")

    response = await enroll(client, "PDT-33-other", secret)

    _assert_generic_rejection(response)
    assert await fetch_device(db_session, "PDT-33-other") is None
    assert (await fetch_authorization(db_session, secret)).consumed_at is None


async def test_authorization_for_another_site_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    organization, _ = await create_organization_and_site(db_session, site_code="SITE-A")
    db_session.add(Site(site_code="SITE-B", organization_id=organization.id))
    await db_session.commit()
    secret = await issue_authorization(db_session, "PDT-34", site_code="SITE-A")

    response = await enroll(client, "PDT-34", secret, site_code="SITE-B")

    _assert_generic_rejection(response)
    assert (await fetch_authorization(db_session, secret)).consumed_at is None


async def test_authorization_for_another_organization_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session, organization_code="ORG-A", site_code="SITE-1")
    await create_organization_and_site(db_session, organization_code="ORG-B", site_code="SITE-1")
    secret = await issue_authorization(db_session, "PDT-35", "ORG-A", "SITE-1")

    response = await enroll(client, "PDT-35", secret, organization_code="ORG-B", site_code="SITE-1")

    _assert_generic_rejection(response)
    assert (await fetch_authorization(db_session, secret)).consumed_at is None


async def test_reissuing_invalidates_the_previous_authorization(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    old_secret = await issue_authorization(db_session, "PDT-36")
    new_secret = await issue_authorization(db_session, "PDT-36")

    _assert_generic_rejection(await enroll(client, "PDT-36", old_secret))
    assert (await enroll(client, "PDT-36", new_secret)).status_code == 201


async def test_failed_enrollment_before_consumption_leaves_authorization_usable(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-37")

    # A typo in the site code fails without consuming the authorization ...
    assert (await enroll(client, "PDT-37", secret, site_code="TYPO")).status_code == 404
    assert (await fetch_authorization(db_session, secret)).consumed_at is None

    # ... so the corrected request still succeeds.
    assert (await enroll(client, "PDT-37", secret)).status_code == 201


async def test_failure_after_consumption_rolls_back_and_leaves_authorization_unconsumed(
    db_session: AsyncSession,
    sessionmaker_: async_sessionmaker[AsyncSession],
    monkeypatch: pytest.MonkeyPatch,
):
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-38")

    class ExplodingHasher(Argon2CredentialHasher):
        def hash(self, credential: str) -> str:
            # Runs after the authorization UPDATE, before the commit.
            raise RuntimeError("simulated failure during enrollment")

    async with sessionmaker_() as session:
        with pytest.raises(RuntimeError):
            await enrollment_service.enroll_device_at_site(
                session,
                ExplodingHasher(),
                "TEST-ORG-001",
                "TEST-SITE-001",
                "PDT-38",
                secret,
                offline_authorization_hours=24,
            )

    assert (await fetch_authorization(db_session, secret)).consumed_at is None
    assert await fetch_device(db_session, "PDT-38") is None

    # The same authorization still works once the failure is gone.
    async with sessionmaker_() as session:
        outcome = await enrollment_service.enroll_device_at_site(
            session,
            Argon2CredentialHasher(),
            "TEST-ORG-001",
            "TEST-SITE-001",
            "PDT-38",
            secret,
            offline_authorization_hours=24,
        )
    assert outcome.device.status == DeviceStatus.ACTIVE


async def test_concurrent_enrollments_with_one_authorization_yield_exactly_one_credential(
    db_session: AsyncSession,
    sessionmaker_: async_sessionmaker[AsyncSession],
):
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-39")

    # Separate app instances and clients; every request gets its own
    # session/connection from the pool, so the race happens in Postgres.
    clients = []
    for _ in range(6):
        app = create_app(make_settings())
        app.state.sessionmaker = sessionmaker_
        clients.append(AsyncClient(transport=ASGITransport(app=app), base_url="http://test"))
    try:
        responses = await asyncio.gather(*(enroll(c, "PDT-39", secret) for c in clients))
    finally:
        for c in clients:
            await c.aclose()

    successes = [r for r in responses if r.status_code == 201]
    failures = [r for r in responses if r.status_code != 201]
    assert len(successes) == 1
    assert all(r.status_code in (403, 409) for r in failures), [r.status_code for r in failures]
    assert all("enrollment_credential" not in r.json() for r in failures)

    db_session.expire_all()
    device_count = (
        await db_session.execute(
            select(func.count()).select_from(EnrolledDevice).where(EnrolledDevice.device_id == "PDT-39")
        )
    ).scalar_one()
    assert device_count == 1

    # The single issued credential is the one that verifies.
    credential = successes[0].json()["enrollment_credential"]
    async with AsyncClient(
        transport=ASGITransport(app=_app(sessionmaker_)), base_url="http://test"
    ) as c:
        assert (await verify(c, "PDT-39", credential)).status_code == 200


def _app(sessionmaker_: async_sessionmaker[AsyncSession]):
    app = create_app(make_settings())
    app.state.sessionmaker = sessionmaker_
    return app


async def test_enrollment_blocked_by_concurrent_consumer_fails_once_it_commits(
    client: AsyncClient,
    db_session: AsyncSession,
    sessionmaker_: async_sessionmaker[AsyncSession],
):
    """Deterministic interleaving: another transaction holds the
    authorization row and consumes it. The enrollment request must wait for
    it and then fail, rather than consuming the authorization a second time."""
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-40")
    digest = hash_authorization_secret(secret)

    async with sessionmaker_() as competitor:
        await competitor.execute(
            select(EnrollmentAuthorization)
            .where(EnrollmentAuthorization.secret_hash == digest)
            .with_for_update()
        )

        request = asyncio.create_task(enroll(client, "PDT-40", secret))
        await asyncio.sleep(0.5)
        assert not request.done(), "enrollment should be blocked on the authorization row lock"

        await competitor.execute(
            update(EnrollmentAuthorization)
            .where(EnrollmentAuthorization.secret_hash == digest)
            .values(consumed_at=datetime.now(UTC))
        )
        await competitor.commit()

    response = await asyncio.wait_for(request, timeout=10)
    _assert_generic_rejection(response)
    assert await fetch_device(db_session, "PDT-40") is None


async def test_enrollment_blocked_by_concurrent_consumer_succeeds_if_it_rolls_back(
    client: AsyncClient,
    db_session: AsyncSession,
    sessionmaker_: async_sessionmaker[AsyncSession],
):
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-41")
    digest = hash_authorization_secret(secret)

    async with sessionmaker_() as competitor:
        await competitor.execute(
            update(EnrollmentAuthorization)
            .where(EnrollmentAuthorization.secret_hash == digest)
            .values(consumed_at=datetime.now(UTC))
        )
        request = asyncio.create_task(enroll(client, "PDT-41", secret))
        await asyncio.sleep(0.5)
        assert not request.done()
        await competitor.rollback()

    response = await asyncio.wait_for(request, timeout=10)
    assert response.status_code == 201


async def test_issuance_racing_first_enrollment_cannot_leave_authorization_on_active_device(
    db_session: AsyncSession,
    sessionmaker_: async_sessionmaker[AsyncSession],
):
    """A first-time device has no row to lock. The competitor plays an
    in-flight first enrollment: it holds the device_id lock, then creates the
    ACTIVE device and commits. Issuance must wait for it and then refuse."""
    _, site = await create_organization_and_site(db_session)

    async def _issue():
        async with sessionmaker_() as session:
            return await enrollment_authorization_service.issue_authorization(
                session, "TEST-ORG-001", "TEST-SITE-001", "PDT-42", ttl_minutes=30
            )

    async with sessionmaker_() as competitor:
        await enrollment_authorization_service.lock_device_id(competitor, "PDT-42")
        task = asyncio.create_task(_issue())
        await asyncio.sleep(0.5)
        assert not task.done(), "issuance should wait for the in-flight enrollment"

        competitor.add(
            EnrolledDevice(
                device_id="PDT-42",
                site_id=site.id,
                credential_hash="$argon2id$placeholder",
                status=DeviceStatus.ACTIVE,
            )
        )
        await competitor.commit()

    with pytest.raises(DeviceResetRequiredError):
        await asyncio.wait_for(task, timeout=10)

    db_session.expire_all()
    outstanding = (
        await db_session.execute(
            select(func.count())
            .select_from(EnrollmentAuthorization)
            .where(
                EnrollmentAuthorization.device_id == "PDT-42",
                EnrollmentAuthorization.consumed_at.is_(None),
                EnrollmentAuthorization.invalidated_at.is_(None),
            )
        )
    ).scalar_one()
    assert outstanding == 0


async def test_enrollment_waits_for_in_flight_issuance_and_old_authorization_is_void(
    client: AsyncClient,
    db_session: AsyncSession,
    sessionmaker_: async_sessionmaker[AsyncSession],
):
    await create_organization_and_site(db_session)
    old_secret = await issue_authorization(db_session, "PDT-43")

    async with sessionmaker_() as issuer:
        await enrollment_authorization_service.lock_device_id(issuer, "PDT-43")
        request = asyncio.create_task(enroll(client, "PDT-43", old_secret))
        await asyncio.sleep(0.5)
        assert not request.done(), "enrollment should wait for the in-flight issuance"
        await enrollment_authorization_service.invalidate_outstanding(
            issuer, "PDT-43", datetime.now(UTC)
        )
        await issuer.commit()

    _assert_generic_rejection(await asyncio.wait_for(request, timeout=10))
