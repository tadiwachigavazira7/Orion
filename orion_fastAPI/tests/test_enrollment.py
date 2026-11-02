from datetime import UTC, datetime, timedelta

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from orion_backend.db.models import DeviceStatus
from orion_backend.services import admin_service, enrollment_service
from orion_backend.services.credential_hasher import Argon2CredentialHasher
from orion_backend.services.rate_limit_service import RateLimitPolicy
from tests.conftest import OFFLINE_AUTHORIZATION_HOURS
from tests.helpers import (
    DEFAULT_ORGANIZATION_CODE,
    DEFAULT_SITE_CODE,
    TEST_ACTOR,
    create_store,
    enroll,
    enroll_at,
    fetch_device,
    fetch_site,
    parse_timestamp,
    verify,
)

INVALID = "invalid_enrollment_authorization"


def _assert_invalid_code(response) -> None:
    assert response.status_code == 403
    assert response.json() == {
        "detail": "Enrollment authorization code is invalid.",
        "error_code": INVALID,
    }


async def test_three_field_enrollment_with_store_code_succeeds(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session)

    before = datetime.now(UTC)
    response = await enroll_at(client, store, "PDT-1")
    after = datetime.now(UTC)

    assert response.status_code == 201
    body = response.json()
    assert body["device_id"] == "PDT-1"
    assert body["organization_code"] == DEFAULT_ORGANIZATION_CODE
    assert body["site_code"] == DEFAULT_SITE_CODE
    assert body["status"] == "ACTIVE"
    assert body["enrollment_credential"]
    assert body["enrollment_credential"].replace("-", "") != store.code.replace("-", "")
    server_time = parse_timestamp(body["server_time"])
    assert before <= server_time <= after
    assert parse_timestamp(body["offline_valid_until"]) - server_time == timedelta(
        hours=OFFLINE_AUTHORIZATION_HOURS
    )
    assert (await verify(client, "PDT-1", body["enrollment_credential"])).status_code == 200


async def test_store_code_is_normalized(client: AsyncClient, db_session: AsyncSession):
    store = await create_store(db_session)
    sloppy = "  " + store.code.replace("-", " ").lower() + " "

    response = await enroll_at(client, store, "PDT-1n", code=sloppy)

    assert response.status_code == 201


async def test_store_code_is_reusable_for_many_devices(client: AsyncClient, db_session: AsyncSession):
    store = await create_store(db_session)

    for device_id in ("PDT-R1", "PDT-R2", "PDT-R3"):
        assert (await enroll_at(client, store, device_id)).status_code == 201


async def test_enrollment_with_unknown_organization_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session)

    response = await enroll(client, "PDT-1b", store.code, organization_code="NO-SUCH-ORG")

    assert response.status_code == 404
    assert response.json()["error_code"] == "organization_not_found"


async def test_enrollment_with_unknown_site_is_rejected(client: AsyncClient, db_session: AsyncSession):
    store = await create_store(db_session)

    response = await enroll(client, "PDT-1c", store.code, site_code="NO-SUCH-SITE")

    assert response.status_code == 404
    assert response.json()["error_code"] == "site_not_found"


async def test_enrollment_with_site_from_different_organization_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session, "TEST-ORG-001", "SHARED-SITE")
    await create_store(db_session, "TEST-ORG-002", "OTHER-SITE-002")

    response = await enroll(client, "PDT-1d", store.code, "TEST-ORG-002", "SHARED-SITE")

    assert response.status_code == 404
    assert response.json()["error_code"] == "site_organization_mismatch"


async def test_wrong_store_code_is_rejected(client: AsyncClient, db_session: AsyncSession):
    store = await create_store(db_session)

    response = await enroll_at(client, store, "PDT-1e", code="AAAAA-AAAAA-AAAAA-AAAAA")

    _assert_invalid_code(response)
    assert await fetch_device(db_session, "PDT-1e") is None


async def test_code_of_another_store_is_rejected(client: AsyncClient, db_session: AsyncSession):
    store_a = await create_store(db_session, site_code="SITE-A")
    store_b = await create_store(db_session, site_code="SITE-B")

    response = await enroll_at(client, store_b, "PDT-1f", code=store_a.code)

    _assert_invalid_code(response)
    assert await fetch_device(db_session, "PDT-1f") is None


async def test_site_without_store_code_rejects_enrollment(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session, with_code=False)

    _assert_invalid_code(await enroll_at(client, store, "PDT-1g", code="ANYTH-INGAT-ALLXX-XXXXX"))


async def test_duplicate_enrollment_of_active_device_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session)
    assert (await enroll_at(client, store, "PDT-2")).status_code == 201
    original_hash = (await fetch_device(db_session, "PDT-2")).credential_hash

    # Even with the correct store code - e.g. a device whose first response
    # was lost - the device is told it is already enrolled.
    second = await enroll_at(client, store, "PDT-2")

    assert second.status_code == 409
    assert second.json()["error_code"] == "device_already_enrolled"
    after = await fetch_device(db_session, "PDT-2")
    assert after.status == DeviceStatus.ACTIVE
    assert after.credential_hash == original_hash


async def test_pending_device_cannot_move_to_another_site(
    client: AsyncClient, db_session: AsyncSession
):
    store_a = await create_store(db_session, site_code="SITE-A")
    store_b = await create_store(db_session, site_code="SITE-B")
    assert (await enroll_at(client, store_a, "PDT-2m")).status_code == 201
    await admin_service.reset_device(db_session, "PDT-2m", TEST_ACTOR)

    _assert_invalid_code(await enroll_at(client, store_b, "PDT-2m"))
    device = await fetch_device(db_session, "PDT-2m")
    assert device.site_id == store_a.site_id
    assert device.status == DeviceStatus.PENDING_ENROLLMENT


async def test_invalid_request_is_rejected(client: AsyncClient, db_session: AsyncSession):
    await create_store(db_session, with_code=False)

    assert (await enroll(client, "", "x")).status_code == 422
    assert (
        await client.post(
            "/enrollment",
            json={"organization_code": "O", "site_code": "S", "device_id": "D", "enrollment_authorization": ""},
        )
    ).status_code == 422


async def test_missing_fields_are_rejected(client: AsyncClient):
    response = await client.post("/enrollment", json={"device_id": "PDT-3"})

    assert response.status_code == 422


async def test_plaintext_secrets_are_never_stored(client: AsyncClient, db_session: AsyncSession):
    store = await create_store(db_session)

    response = await enroll_at(client, store, "PDT-4")
    credential = response.json()["enrollment_credential"]

    device_hash = (await fetch_device(db_session, "PDT-4")).credential_hash
    assert credential not in device_hash
    assert device_hash.startswith("$argon2id$")
    site = await fetch_site(db_session, store.site_id)
    assert site.enrollment_code_hash.startswith("$argon2id$")
    assert store.code.replace("-", "") not in site.enrollment_code_hash
    assert device_hash != site.enrollment_code_hash


async def test_failure_after_code_check_creates_no_device(
    db_session: AsyncSession, sessionmaker_: async_sessionmaker[AsyncSession]
):
    store = await create_store(db_session)

    class ExplodingHasher(Argon2CredentialHasher):
        def hash(self, credential: str) -> str:
            raise RuntimeError("simulated failure while issuing the credential")

    async with sessionmaker_() as session:
        with pytest.raises(RuntimeError):
            await enrollment_service.enroll_device_at_site(
                session,
                ExplodingHasher(),
                store.organization_code,
                store.site_code,
                "PDT-38",
                store.code,
                client_ip="127.0.0.1",
                rate_limit=RateLimitPolicy(timedelta(minutes=15), 5, 50),
                offline_authorization_hours=24,
            )

    assert await fetch_device(db_session, "PDT-38") is None
