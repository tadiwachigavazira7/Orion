"""Admin operations: per-site capacity changes."""

from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import Site
from orion_backend.errors import CapacityBelowOccupancyError
from orion_backend.services import capacity
from orion_backend.services.enrollment_service import _resolve_organization, _resolve_site


async def set_extra_device_slots(
    db: AsyncSession, organization_code: str, site_code: str, extra_device_slots: int
) -> tuple[Site, int]:
    organization = await _resolve_organization(db, organization_code)
    site = await _resolve_site(db, organization, site_code)
    site = await capacity.lock_site(db, site.id)
    active = await capacity.count_active(db, site.id)
    new_capacity = capacity.INCLUDED_DEVICE_SLOTS + extra_device_slots
    if new_capacity < active:
        raise CapacityBelowOccupancyError(new_capacity, active)
    site.extra_device_slots = extra_device_slots
    await db.commit()
    return site, active
