import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api import deps as admin_module
from orion_backend.config import Settings
from tests.helpers import ADMIN
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
    assert (await client.get("/enrolled_devices/D-7", headers=ADMIN)).status_code == 404


async def test_revoked_frees_slot(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await fill(client, 6)
    await client.delete("/enrolled_devices/D-0", headers=ADMIN)
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
        "included_device_slots": 6,
        "max_devices": 8,
        "available_slots": 2,
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


async def test_admin_included_slots_change_and_audit(
    client: AsyncClient, db_session: AsyncSession, admin_key
):
    await create_organization_and_site(db_session)
    await fill(client, 3)
    r = await client.put(
        CAP_URL, json={"extra_device_slots": 1, "included_device_slots": 4}, headers={"X-Admin-Key": KEY}
    )
    assert r.status_code == 200
    body = r.json()
    assert (body["max_devices"], body["capacity"], body["available_slots"]) == (5, 5, 2)
    assert body["included_device_slots"] == 4 and body["included_slots"] == 4
    # Decrease to exactly the active count is allowed; below it is rejected and changes nothing.
    ok = await client.put(
        CAP_URL, json={"extra_device_slots": 0, "included_device_slots": 3}, headers={"X-Admin-Key": KEY}
    )
    assert ok.status_code == 200 and ok.json()["max_devices"] == 3
    bad = await client.put(CAP_URL, json={"extra_device_slots": 0, "included_device_slots": 2}, headers={"X-Admin-Key": KEY})
    assert bad.status_code == 409 and bad.json()["error_code"] == "capacity_below_active_devices"
    assert (await enroll(client, device_id="D-9")).status_code == 409
    from sqlalchemy import select
    from orion_backend.db.models import AuditEvent

    rows = (await db_session.execute(select(AuditEvent).where(AuditEvent.action == "capacity_changed"))).scalars().all()
    assert len(rows) == 2 and {r.actor for r in rows} == {"admin-api"}


async def test_slot_counts_have_an_upper_bound(client: AsyncClient, db_session: AsyncSession, admin_key):
    await create_organization_and_site(db_session)
    h = {"X-Admin-Key": KEY}
    for body in ({"extra_device_slots": 10001}, {"extra_device_slots": 0, "included_device_slots": 2**40}):
        assert (await client.put(CAP_URL, json=body, headers=h)).status_code == 422
    ok = await client.put(CAP_URL, json={"extra_device_slots": 10000}, headers=h)
    assert ok.status_code == 200 and ok.json()["max_devices"] == 10006
