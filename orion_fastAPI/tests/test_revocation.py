from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import DeviceStatus, EnrolledDevice
from tests.helpers import (
    DEFAULT_ORGANIZATION_CODE,
    DEFAULT_SITE_CODE,
    admin_headers,
    create_organization_and_site,
    deactivate_device,
    enroll,
)


async def test_active_device_can_be_deactivated(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await enroll(client, device_id="PDT-20")

    response = await deactivate_device(client, "PDT-20")

    assert response.status_code == 200
    body = response.json()
    assert body["device_id"] == "PDT-20"
    assert body["status"] == "REVOKED"
    assert body["organization_code"] == DEFAULT_ORGANIZATION_CODE
    assert body["site_code"] == DEFAULT_SITE_CODE


async def test_deactivate_keeps_row_and_is_idempotent(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await enroll(client, device_id="PDT-23")

    first = await deactivate_device(client, "PDT-23")
    second = await deactivate_device(client, "PDT-23")

    assert first.status_code == 200
    assert second.status_code == 200
    assert second.json()["status"] == "REVOKED"
    db_session.expire_all()
    rows = (
        await db_session.execute(select(EnrolledDevice).where(EnrolledDevice.device_id == "PDT-23"))
    ).scalars().all()
    assert len(rows) == 1
    assert rows[0].status == DeviceStatus.REVOKED


async def test_deactivated_device_cannot_verify(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    enroll_response = await enroll(client, device_id="PDT-21")
    credential = enroll_response.json()["enrollment_credential"]

    await deactivate_device(client, "PDT-21")

    response = await client.post(
        "/enrolled_devices/PDT-21/verify", json={"enrollment_credential": credential}
    )

    assert response.status_code == 403
    assert response.json()["error_code"] == "device_revoked"
    assert response.json().get("verified") is not True


async def test_deactivated_device_cannot_reenroll(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await enroll(client, device_id="PDT-24")
    await deactivate_device(client, "PDT-24")

    response = await enroll(client, device_id="PDT-24")

    assert response.status_code == 409


async def test_deactivating_unknown_device_returns_404(client: AsyncClient):
    response = await deactivate_device(client, "PDT-does-not-exist")

    assert response.status_code == 404
    assert response.json()["error_code"] == "device_not_found"


async def test_admin_get_device_has_safe_shape(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    enroll_response = await enroll(client, device_id="PDT-22")
    credential = enroll_response.json()["enrollment_credential"]

    response = await client.get("/admin/devices/PDT-22", headers=admin_headers())

    assert response.status_code == 200
    body = response.json()
    assert body["device_id"] == "PDT-22"
    assert body["status"] == "ACTIVE"
    assert body["organization_code"] == DEFAULT_ORGANIZATION_CODE
    assert body["site_code"] == DEFAULT_SITE_CODE
    assert body["last_verified_at"] is None
    assert body["created_at"]
    assert body["updated_at"]
    assert "credential" not in response.text.lower()
    assert "hash" not in response.text.lower()
    assert credential not in response.text


async def test_admin_get_unknown_device_returns_404(client: AsyncClient):
    response = await client.get("/admin/devices/PDT-does-not-exist", headers=admin_headers())

    assert response.status_code == 404


async def test_public_router_no_longer_exposes_get_or_delete(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    await enroll(client, device_id="PDT-25")

    for headers in ({}, admin_headers()):
        get_response = await client.get("/enrolled_devices/PDT-25", headers=headers)
        delete_response = await client.delete("/enrolled_devices/PDT-25", headers=headers)
        assert get_response.status_code in (404, 405)
        assert delete_response.status_code in (404, 405)

    # The unauthenticated DELETE must not have revoked anything.
    db_session.expire_all()
    device = (
        await db_session.execute(select(EnrolledDevice).where(EnrolledDevice.device_id == "PDT-25"))
    ).scalar_one()
    assert device.status == DeviceStatus.ACTIVE
