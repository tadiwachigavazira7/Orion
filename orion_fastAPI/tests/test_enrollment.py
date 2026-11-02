from datetime import UTC, datetime, timedelta

from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import DeviceStatus
from tests.conftest import OFFLINE_AUTHORIZATION_HOURS
from tests.helpers import (
    DEFAULT_ORGANIZATION_CODE,
    DEFAULT_SITE_CODE,
    authorize_and_enroll,
    create_organization_and_site,
    enroll,
    fetch_authorization,
    fetch_device,
    issue_authorization,
    parse_timestamp,
)


async def test_authorized_device_enrolls_successfully(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-1")

    before = datetime.now(UTC)
    response = await enroll(client, "PDT-1", secret)
    after = datetime.now(UTC)

    assert response.status_code == 201
    body = response.json()
    assert body["device_id"] == "PDT-1"
    assert body["organization_code"] == DEFAULT_ORGANIZATION_CODE
    assert body["site_code"] == DEFAULT_SITE_CODE
    assert body["status"] == "ACTIVE"
    assert body["enrollment_credential"]
    assert body["enrollment_credential"] != secret

    server_time = parse_timestamp(body["server_time"])
    offline_valid_until = parse_timestamp(body["offline_valid_until"])
    assert before <= server_time <= after
    assert offline_valid_until - server_time == timedelta(hours=OFFLINE_AUTHORIZATION_HOURS)

    authorization = await fetch_authorization(db_session, secret)
    assert authorization.consumed_at is not None


async def test_enrollment_without_authorization_field_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)

    response = await client.post(
        "/enrollment",
        json={
            "organization_code": DEFAULT_ORGANIZATION_CODE,
            "site_code": DEFAULT_SITE_CODE,
            "device_id": "PDT-1a",
        },
    )

    assert response.status_code == 422
    assert await fetch_device(db_session, "PDT-1a") is None


async def test_enrollment_with_empty_authorization_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)

    response = await enroll(client, "PDT-1e", enrollment_authorization="")

    assert response.status_code == 422


async def test_enrollment_with_unknown_organization_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-1b")

    response = await enroll(client, "PDT-1b", secret, organization_code="NO-SUCH-ORG")

    assert response.status_code == 404
    assert response.json()["error_code"] == "organization_not_found"


async def test_enrollment_with_unknown_site_is_rejected(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-1c")

    response = await enroll(client, "PDT-1c", secret, site_code="NO-SUCH-SITE")

    assert response.status_code == 404
    assert response.json()["error_code"] == "site_not_found"


async def test_enrollment_with_site_from_different_organization_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(
        db_session, organization_code="TEST-ORG-001", site_code="SHARED-SITE"
    )
    await create_organization_and_site(
        db_session, organization_code="TEST-ORG-002", site_code="OTHER-SITE-002"
    )
    secret = await issue_authorization(db_session, "PDT-1d", "TEST-ORG-001", "SHARED-SITE")

    response = await enroll(
        client,
        "PDT-1d",
        secret,
        organization_code="TEST-ORG-002",
        site_code="SHARED-SITE",
    )

    assert response.status_code == 404
    assert response.json()["error_code"] == "site_organization_mismatch"


async def test_duplicate_enrollment_of_active_device_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    first = await authorize_and_enroll(client, db_session, "PDT-2")
    assert first.status_code == 201
    original_hash = (await fetch_device(db_session, "PDT-2")).credential_hash

    # Replaying the (now consumed) authorization - e.g. a device whose first
    # response was lost - is told the device is already enrolled.
    second = await enroll(client, "PDT-2", "anything")

    assert second.status_code == 409
    assert second.json()["error_code"] == "device_already_enrolled"
    after = await fetch_device(db_session, "PDT-2")
    assert after.status == DeviceStatus.ACTIVE
    assert after.credential_hash == original_hash


async def test_invalid_request_is_rejected(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    response = await enroll(client, device_id="", enrollment_authorization="x")

    assert response.status_code == 422


async def test_missing_fields_are_rejected(client: AsyncClient):
    response = await client.post("/enrollment", json={"device_id": "PDT-3"})

    assert response.status_code == 422


async def test_plaintext_credential_is_never_stored(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    response = await authorize_and_enroll(client, db_session, "PDT-4")
    credential = response.json()["enrollment_credential"]

    device = await fetch_device(db_session, "PDT-4")
    assert device.credential_hash != credential
    assert credential not in device.credential_hash


async def test_credential_is_stored_as_argon2id_hash(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    await authorize_and_enroll(client, db_session, "PDT-5")

    device = await fetch_device(db_session, "PDT-5")
    assert device.credential_hash.startswith("$argon2id$")


async def test_enrolled_device_is_active(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    await authorize_and_enroll(client, db_session, "PDT-6")

    device = await fetch_device(db_session, "PDT-6")
    assert device.status == DeviceStatus.ACTIVE


async def test_authorization_secret_is_never_stored_in_plaintext(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    secret = await issue_authorization(db_session, "PDT-7")

    authorization = await fetch_authorization(db_session, secret)

    assert authorization.secret_hash != secret
    assert secret not in authorization.secret_hash
    assert len(authorization.secret_hash) == 64  # SHA-256 hex digest
