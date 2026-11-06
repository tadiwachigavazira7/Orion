import asyncio
import time

from httpx import AsyncClient
from sqlalchemy import func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import AuditEvent, EnrolledDevice
from orion_backend.services import admin_service, capacity
from tests.conftest import FastHasher
from tests.helpers import (
    DEFAULT_ENROLLMENT_CODE as OLD,
    DEFAULT_ORGANIZATION_CODE as ORG,
    DEFAULT_SITE_CODE as SITE,
    create_organization_and_site,
    enroll,
)


async def _counts(db: AsyncSession) -> tuple[int, int]:
    devices = (await db.execute(select(func.count()).select_from(EnrolledDevice))).scalar_one()
    audits = (await db.execute(select(func.count()).select_from(AuditEvent))).scalar_one()
    return devices, audits


async def test_code_rotated_while_waiting_for_lock_is_rejected(
    client: AsyncClient, db_session: AsyncSession, sessionmaker_
):
    _, site = await create_organization_and_site(db_session)  # real Argon2 hash of OLD
    async with sessionmaker_() as holder:
        await capacity.lock_site(holder, site.id)
        task = asyncio.create_task(enroll(client, "A", enrollment_code=OLD))
        async with sessionmaker_() as probe:  # wait until the enrollment is blocked on the lock
            for _ in range(100):
                waiting = (
                    await probe.execute(text("select count(*) from pg_stat_activity where wait_event_type = 'Lock'"))
                ).scalar_one()
                if waiting:
                    break
                await asyncio.sleep(0.05)
        assert waiting, "enrollment never blocked on the site lock"
        locked = await capacity.lock_site(holder, site.id)
        locked.enrollment_code_hash = FastHasher().hash("new-code")  # rotation, committed while it waits
        await holder.commit()
    r = await task
    assert r.status_code == 403 and r.json()["error_code"] == "invalid_enrollment_code"
    assert await _counts(db_session) == (0, 0)


async def test_rotate_vs_enroll_no_enrollment_admitted_after_rotation(
    client: AsyncClient, db_session: AsyncSession, sessionmaker_, race_decisions
):
    await create_organization_and_site(db_session, hash_fn=lambda c: "h:" + c)
    rotated: list[float] = []

    async def rotate():
        await asyncio.sleep(0.05)  # first enrollment holds the lock inside its window
        async with sessionmaker_() as s:
            await admin_service.rotate_enrollment_code(s, FastHasher(), ORG, SITE, "tester")
        rotated.append(time.monotonic())

    results = await asyncio.gather(rotate(), *(enroll(client, f"R-{i}", enrollment_code=OLD) for i in range(8)))
    ok = [r for r in results[1:] if r.status_code == 201]
    assert ok and {
        (r.status_code, r.json()["error_code"]) for r in results[1:] if r.status_code != 201
    } <= {(403, "invalid_enrollment_code"), (409, "site_capacity_exceeded")}
    # With the old code, nothing may pass the admission check once the rotation committed.
    assert max(race_decisions) < rotated[0]
    late = await enroll(client, "LATE", enrollment_code=OLD)
    assert late.status_code == 403 and late.json()["error_code"] == "invalid_enrollment_code"
