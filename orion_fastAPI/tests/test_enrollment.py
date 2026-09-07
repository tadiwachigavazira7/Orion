from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import DeviceStatus, EnrolledDevice
from tests.helpers import DEFAULT_CREDENTIAL, enroll


async def test_new_device_enrolls_successfully(client: AsyncClient):
    response = await enroll(client, device_id="PDT-1", site_id="STORE-1")

    assert response.status_code == 201
    body = response.json()
    assert body == {"device_id": "PDT-1", "site_id": "STORE-1", "status": "ACTIVE"}


async def test_duplicate_device_id_is_rejected(client: AsyncClient):
    first = await enroll(client, device_id="PDT-2")
    assert first.status_code == 201

    second = await enroll(client, device_id="PDT-2", credential="a-different-credential")

    assert second.status_code == 409


async def test_invalid_request_is_rejected(client: AsyncClient):
    response = await client.post(
        "/enrolled_devices",
        json={"device_id": "", "site_id": "STORE-1", "enrollment_credential": DEFAULT_CREDENTIAL},
    )

    assert response.status_code == 422


async def test_missing_fields_are_rejected(client: AsyncClient):
    response = await client.post("/enrolled_devices", json={"device_id": "PDT-3"})

    assert response.status_code == 422


async def test_plaintext_credential_is_never_stored(client: AsyncClient, db_session: AsyncSession):
    credential = "super-secret-enrollment-value"
    await enroll(client, device_id="PDT-4", credential=credential)

    result = await db_session.execute(
        select(EnrolledDevice).where(EnrolledDevice.device_id == "PDT-4")
    )
    device = result.scalar_one()

    assert device.credential_hash != credential
    assert credential not in device.credential_hash


async def test_credential_is_stored_as_argon2id_hash(client: AsyncClient, db_session: AsyncSession):
    await enroll(client, device_id="PDT-5")

    result = await db_session.execute(
        select(EnrolledDevice).where(EnrolledDevice.device_id == "PDT-5")
    )
    device = result.scalar_one()

    assert device.credential_hash.startswith("$argon2id$")


async def test_enrolled_device_is_active(client: AsyncClient, db_session: AsyncSession):
    await enroll(client, device_id="PDT-6")

    result = await db_session.execute(
        select(EnrolledDevice).where(EnrolledDevice.device_id == "PDT-6")
    )
    device = result.scalar_one()

    assert device.status == DeviceStatus.ACTIVE
