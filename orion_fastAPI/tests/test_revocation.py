from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from tests.helpers import DEFAULT_ORGANIZATION_CODE, DEFAULT_SITE_CODE, create_organization_and_site, enroll


async def test_active_device_can_be_revoked(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await enroll(client, device_id="PDT-20")

    response = await client.delete("/enrolled_devices/PDT-20")

    assert response.status_code == 200
    body = response.json()
    assert body["device_id"] == "PDT-20"
    assert body["status"] == "REVOKED"
    assert body["organization_code"] == DEFAULT_ORGANIZATION_CODE
    assert body["site_code"] == DEFAULT_SITE_CODE


async def test_revoked_device_cannot_verify(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    enroll_response = await enroll(client, device_id="PDT-21")
    credential = enroll_response.json()["enrollment_credential"]

    await client.delete("/enrolled_devices/PDT-21")

    response = await client.post(
        "/enrolled_devices/PDT-21/verify", json={"enrollment_credential": credential}
    )

    assert response.status_code == 403
    assert response.json().get("verified") is not True


async def test_revoking_unknown_device_returns_404(client: AsyncClient):
    response = await client.delete("/enrolled_devices/PDT-does-not-exist")

    assert response.status_code == 404


async def test_get_device_status(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await enroll(client, device_id="PDT-22")

    response = await client.get("/enrolled_devices/PDT-22")

    assert response.status_code == 200
    body = response.json()
    assert body["device_id"] == "PDT-22"
    assert body["status"] == "ACTIVE"
    assert body["organization_code"] == DEFAULT_ORGANIZATION_CODE
    assert body["site_code"] == DEFAULT_SITE_CODE
    assert body["last_verified_at"] is None
    assert "enrollment_credential" not in body
    assert "credential_hash" not in body


async def test_get_unknown_device_returns_404(client: AsyncClient):
    response = await client.get("/enrolled_devices/PDT-does-not-exist")

    assert response.status_code == 404
