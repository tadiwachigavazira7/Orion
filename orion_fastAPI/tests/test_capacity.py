import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api import admin as admin_module
from orion_backend.config import Settings
from tests.helpers import DEFAULT_ORGANIZATION_CODE, DEFAULT_SITE_CODE, create_organization_and_site, enroll

CAP_URL = f"/organizations/{DEFAULT_ORGANIZATION_CODE}/sites/{DEFAULT_SITE_CODE}/capacity"
KEY = "test-admin-key"


@pytest.fixture
def admin_key(monkeypatch):
    monkeypatch.setattr(
        admin_module, "get_settings", lambda: Settings(database_url="x", admin_api_key=KEY)
    )


async def fill(client: AsyncClient, n: int):
    for i in range(n):
        assert (await enroll(client, device_id=f"D-{i}")).status_code == 201


async def test_seventh_device_rejected(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await fill(client, 6)
    r = await enroll(client, device_id="D-7")
    assert r.status_code == 409
    assert r.json()["error_code"] == "site_capacity_exceeded"
    assert (await client.get("/enrolled_devices/D-7")).status_code == 404


async def test_revoked_frees_slot(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await fill(client, 6)
    await client.delete("/enrolled_devices/D-0")
    assert (await enroll(client, device_id="D-7")).status_code == 201


async def test_admin_raise_allows_more(client: AsyncClient, db_session: AsyncSession, admin_key):
    await create_organization_and_site(db_session)
    await fill(client, 6)
    r = await client.put(CAP_URL, json={"extra_device_slots": 2}, headers={"X-Admin-Key": KEY})
    assert r.status_code == 200
    assert r.json() == {
        "organization_code": DEFAULT_ORGANIZATION_CODE,
        "site_code": DEFAULT_SITE_CODE,
        "included_slots": 6,
        "extra_device_slots": 2,
        "capacity": 8,
        "active_devices": 6,
    }
    assert (await enroll(client, device_id="D-7")).status_code == 201


async def test_admin_lower_below_active_rejected(
    client: AsyncClient, db_session: AsyncSession, admin_key
):
    await create_organization_and_site(db_session)
    await fill(client, 6)
    await client.put(CAP_URL, json={"extra_device_slots": 2}, headers={"X-Admin-Key": KEY})
    await enroll(client, device_id="D-7")
    await enroll(client, device_id="D-8")
    r = await client.put(CAP_URL, json={"extra_device_slots": 1}, headers={"X-Admin-Key": KEY})
    assert r.status_code == 409
    assert r.json()["error_code"] == "capacity_below_active_devices"
    r = await client.put(CAP_URL, json={"extra_device_slots": -1}, headers={"X-Admin-Key": KEY})
    assert r.status_code == 422


async def test_admin_key_checks(client: AsyncClient, db_session: AsyncSession, monkeypatch):
    await create_organization_and_site(db_session)
    body = {"extra_device_slots": 1}
    monkeypatch.setattr(
        admin_module, "get_settings", lambda: Settings(database_url="x", admin_api_key=None)
    )
    r = await client.put(CAP_URL, json=body, headers={"X-Admin-Key": KEY})
    assert r.status_code == 503 and r.json()["error_code"] == "admin_api_not_configured"
    monkeypatch.setattr(
        admin_module, "get_settings", lambda: Settings(database_url="x", admin_api_key=KEY)
    )
    for headers in ({}, {"X-Admin-Key": "wrong"}):
        r = await client.put(CAP_URL, json=body, headers=headers)
        assert r.status_code == 401 and r.json()["error_code"] == "invalid_admin_key"


async def test_other_site_unaffected(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await create_organization_and_site(db_session, "ORG-2", "SITE-2")
    await fill(client, 6)
    assert (await enroll(client, "X-1", "ORG-2", "SITE-2")).status_code == 201
