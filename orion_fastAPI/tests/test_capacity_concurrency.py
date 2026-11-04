import asyncio

import pytest
import pytest_asyncio

from httpx import AsyncClient
from sqlalchemy import func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_credential_hasher
from orion_backend.api import admin as admin_module
from orion_backend.services import capacity
from orion_backend.config import Settings
from orion_backend.db.models import DeviceStatus, EnrolledDevice
from tests.helpers import DEFAULT_ORGANIZATION_CODE, DEFAULT_SITE_CODE, create_organization_and_site, enroll



class _FastHasher:
    """Argon2 is synchronous and blocks the event loop, which serializes the
    requests and hides races; a trivial hasher lets them truly interleave."""

    def hash(self, credential: str) -> str:
        return "h:" + credential

    def verify(self, credential: str, credential_hash: str) -> bool:
        return credential_hash == "h:" + credential


@pytest_asyncio.fixture(autouse=True)
async def _race_ready(client: AsyncClient, sessionmaker_):
    app = client._transport.app
    app.dependency_overrides[get_credential_hasher] = lambda: _FastHasher()

    # Open 12 pooled connections up front. Otherwise connection setup latency
    # staggers the requests, the first one commits before the others read, and
    # the race never happens (the lock-removal mutation check would not fail).
    async def hold():
        async with sessionmaker_() as s:
            await s.execute(text("select pg_sleep(0.3)"))

    await asyncio.gather(*(hold() for _ in range(12)))


@pytest.fixture(autouse=True)
def _widen_race_window(monkeypatch):
    # Pause right after the occupancy read, before the decision is acted on.
    # Correct locking is unaffected (the pause happens inside the site lock);
    # without the lock every concurrent request acts on the same stale count.
    real = capacity.count_active

    async def slow_count(db, site_id):
        n = await real(db, site_id)
        await asyncio.sleep(0.2)
        return n

    monkeypatch.setattr(capacity, "count_active", slow_count)


# The `client` fixture's engine uses the default pool (5 + 10 overflow = 15
# connections) and every request opens its own session, so 10 concurrent
# requests really do run on 10 separate connections.


async def seed_active(db: AsyncSession, site, n: int):
    for i in range(n):
        db.add(
            EnrolledDevice(
                device_id=f"SEED-{i}", site_id=site.id,
                credential_hash="x", status=DeviceStatus.ACTIVE,
            )
        )
    await db.commit()


async def rows(db: AsyncSession, site, status=None) -> int:
    q = select(func.count()).select_from(EnrolledDevice).where(EnrolledDevice.site_id == site.id)
    if status:
        q = q.where(EnrolledDevice.status == status)
    return (await db.execute(q)).scalar_one()


async def test_ten_concurrent_distinct_enrollments_one_slot(
    client: AsyncClient, db_session: AsyncSession
):
    _, site = await create_organization_and_site(db_session)
    await seed_active(db_session, site, 5)
    ids = [f"NEW-{i}" for i in range(10)]
    responses = await asyncio.gather(*(enroll(client, d) for d in ids))

    ok = [(d, r) for d, r in zip(ids, responses) if r.status_code == 201]
    assert len(ok) == 1
    rejected = [(d, r) for d, r in zip(ids, responses) if r.status_code != 201]
    assert len(rejected) == 9
    assert all(
        r.status_code == 409 and r.json()["error_code"] == "site_capacity_exceeded"
        for _, r in rejected
    )
    assert await rows(db_session, site, DeviceStatus.ACTIVE) == 6
    assert await rows(db_session, site) == 6
    dev, resp = ok[0]
    v = await client.post(
        f"/enrolled_devices/{dev}/verify",
        json={"enrollment_credential": resp.json()["enrollment_credential"]},
    )
    assert v.status_code == 200
    for d, _ in rejected:
        assert (await client.get(f"/enrolled_devices/{d}")).status_code == 404


async def test_same_device_id_concurrently(client: AsyncClient, db_session: AsyncSession):
    _, site = await create_organization_and_site(db_session)
    responses = await asyncio.gather(*(enroll(client, "DUP") for _ in range(10)))
    assert sorted(r.status_code for r in responses) == [201] + [409] * 9
    assert all(
        r.json()["error_code"] == "device_already_enrolled"
        for r in responses
        if r.status_code == 409
    )
    assert await rows(db_session, site) == 1


async def test_same_device_id_at_two_sites(client: AsyncClient, db_session: AsyncSession):
    _, site1 = await create_organization_and_site(db_session)
    _, site2 = await create_organization_and_site(db_session, "ORG-2", "SITE-2")
    r1, r2 = await asyncio.gather(enroll(client, "DUP"), enroll(client, "DUP", "ORG-2", "SITE-2"))
    assert sorted([r1.status_code, r2.status_code]) == [201, 409]
    loser = r1 if r1.status_code == 409 else r2
    assert loser.json()["error_code"] == "device_already_enrolled"
    assert await rows(db_session, site1) + await rows(db_session, site2) == 1


async def test_enroll_vs_revoke_at_full_site(client: AsyncClient, db_session: AsyncSession):
    _, site = await create_organization_and_site(db_session)
    await seed_active(db_session, site, 6)
    enr, rev = await asyncio.gather(enroll(client, "NEW"), client.delete("/enrolled_devices/SEED-0"))
    assert rev.status_code == 200
    assert enr.status_code in (201, 409)
    active = await rows(db_session, site, DeviceStatus.ACTIVE)
    assert active <= 6
    assert active == (6 if enr.status_code == 201 else 5)


async def test_admin_reduction_vs_enrollment(
    client: AsyncClient, db_session: AsyncSession, monkeypatch
):
    monkeypatch.setattr(
        admin_module, "get_settings", lambda: Settings(database_url="x", admin_api_key="k")
    )
    _, site = await create_organization_and_site(db_session)
    await seed_active(db_session, site, 5)
    url = f"/organizations/{DEFAULT_ORGANIZATION_CODE}/sites/{DEFAULT_SITE_CODE}/capacity"
    h = {"X-Admin-Key": "k"}
    await client.put(url, json={"extra_device_slots": 2}, headers=h)
    results = await asyncio.gather(
        client.put(url, json={"extra_device_slots": 0}, headers=h),
        *(enroll(client, f"N-{i}") for i in range(3)),
    )
    await db_session.refresh(site)
    active = await rows(db_session, site, DeviceStatus.ACTIVE)
    assert active <= 6 + site.extra_device_slots
    assert results[0].status_code in (200, 409)
    assert (results[0].status_code == 200) == (site.extra_device_slots == 0)
