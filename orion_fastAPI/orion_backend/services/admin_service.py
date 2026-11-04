"""Admin/operator operations: per-site capacity and enrollment-code rotation.

Both lock the site row first (capacity.lock_site) and audit in the same transaction.
"""

import secrets

from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import Site
from orion_backend.errors import CapacityBelowOccupancyError
from orion_backend.services import audit, capacity
from orion_backend.services.credential_hasher import CredentialHasher
from orion_backend.services.enrollment_service import _resolve_organization, _resolve_site

_ENROLLMENT_CODE_BYTES = 12


async def get_capacity(db: AsyncSession, organization_code: str, site_code: str) -> tuple[Site, int]:
    organization = await _resolve_organization(db, organization_code)
    site = await _resolve_site(db, organization, site_code)
    return site, await capacity.count_active(db, site.id)


async def set_capacity(
    db: AsyncSession,
    organization_code: str,
    site_code: str,
    actor: str,
    extra_device_slots: int | None = None,
    included_device_slots: int | None = None,
) -> tuple[Site, int]:
    organization = await _resolve_organization(db, organization_code)
    site = await _resolve_site(db, organization, site_code)
    site = await capacity.lock_site(db, site.id)
    extra = site.extra_device_slots if extra_device_slots is None else extra_device_slots
    included = site.included_device_slots if included_device_slots is None else included_device_slots
    active = await capacity.count_active(db, site.id)
    if included + extra < active:
        raise CapacityBelowOccupancyError(included + extra, active)
    old = {"included": site.included_device_slots, "extra": site.extra_device_slots, "max": site.max_devices}
    site.extra_device_slots = extra
    site.included_device_slots = included
    audit.record(
        db, actor, "capacity_changed", site.id,
        {"old": old, "new": {"included": included, "extra": extra, "max": included + extra}},
    )
    await db.commit()
    await db.refresh(site)  # max_devices is generated: re-read it
    return site, active


async def rotate_enrollment_code(
    db: AsyncSession,
    hasher: CredentialHasher,
    organization_code: str,
    site_code: str,
    actor: str,
) -> str:
    """Returns the new plaintext code; only its Argon2id hash is stored."""
    organization = await _resolve_organization(db, organization_code)
    site = await _resolve_site(db, organization, site_code)
    code = secrets.token_urlsafe(_ENROLLMENT_CODE_BYTES)
    code_hash = hasher.hash(code)  # CPU-heavy: before the lock
    site = await capacity.lock_site(db, site.id)
    site.enrollment_code_hash = code_hash
    audit.record(db, actor, "enrollment_code_rotated", site.id)
    await db.commit()
    return code
