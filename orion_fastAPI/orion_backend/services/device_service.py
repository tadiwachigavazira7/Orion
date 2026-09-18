"""Business logic for device enrollment, verification, and revocation.

Route handlers (orion_backend/api/devices.py) stay thin: they parse the
request, call one function here, and translate the result/exception into an
HTTP response. All database access and credential hashing/verification is
kept here, not in the routes.
"""

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from orion_backend.db.models import DeviceStatus, EnrolledDevice, Site
from orion_backend.errors import (
    DeviceNotFoundError,
    DeviceRevokedError,
    InvalidCredentialError,
)
from orion_backend.services.credential_hasher import CredentialHasher


async def find_by_device_id(db: AsyncSession, device_id: str) -> EnrolledDevice | None:
    """Look up a device by device_id, eagerly loading its site and that
    site's organization.

    Callers (routes, other services) need device.site.site_code and
    device.site.organization.organization_code for response bodies. The
    async ORM does not support implicit lazy-loading outside active IO, so
    those relationships must be eagerly loaded here rather than accessed
    lazily by callers.
    """
    result = await db.execute(
        select(EnrolledDevice)
        .where(EnrolledDevice.device_id == device_id)
        .options(selectinload(EnrolledDevice.site).selectinload(Site.organization))
    )
    return result.scalar_one_or_none()


async def get_device(db: AsyncSession, device_id: str) -> EnrolledDevice:
    device = await find_by_device_id(db, device_id)
    if device is None:
        raise DeviceNotFoundError(device_id)
    return device


async def verify_device(
    db: AsyncSession,
    hasher: CredentialHasher,
    device_id: str,
    enrollment_credential: str,
) -> EnrolledDevice:
    device = await find_by_device_id(db, device_id)
    if device is None:
        raise DeviceNotFoundError(device_id)
    if device.status != DeviceStatus.ACTIVE:
        raise DeviceRevokedError(device_id)
    if not hasher.verify(enrollment_credential, device.credential_hash):
        raise InvalidCredentialError(device_id)

    device.last_verified_at = datetime.now(UTC)
    await db.commit()
    await db.refresh(device)
    return device


async def revoke_device(db: AsyncSession, device_id: str) -> EnrolledDevice:
    device = await find_by_device_id(db, device_id)
    if device is None:
        raise DeviceNotFoundError(device_id)

    if device.status != DeviceStatus.REVOKED:
        device.status = DeviceStatus.REVOKED
        await db.commit()
        await db.refresh(device)
    return device
