"""Per-site device capacity and the locking convention that protects it.

LOCKING CONVENTION: every capacity-changing operation (enroll, revoke, admin
capacity change, subscription change, enrollment-code rotation) first takes `SELECT ... FOR UPDATE` on the SITE row via
lock_site(), BEFORE reading/counting/writing any device rows. Capacity is Site.max_devices (generated column; the only source of truth).
Exactly one lock
per transaction and always the site row, so there is no lock-order deadlock.
The lock is released by commit/rollback (get_db's session close rolls back).
"""

import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import DeviceStatus, EnrolledDevice, Site

async def lock_site(db: AsyncSession, site_id: uuid.UUID) -> Site:
    # populate_existing so slots/max_devices are re-read after waiting on the lock.
    result = await db.execute(
        select(Site).where(Site.id == site_id).with_for_update().execution_options(populate_existing=True)
    )
    return result.scalar_one()


async def count_active(db: AsyncSession, site_id: uuid.UUID) -> int:
    result = await db.execute(
        select(func.count())
        .select_from(EnrolledDevice)
        .where(EnrolledDevice.site_id == site_id, EnrolledDevice.status == DeviceStatus.ACTIVE)
    )
    return result.scalar_one()
