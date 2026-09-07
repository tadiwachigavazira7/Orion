"""Business logic for device enrollment, verification, and revocation.

Route handlers (orion_backend/api/devices.py) stay thin: they parse the
request, call one function here, and translate the result/exception into an
HTTP response. All database access and credential hashing/verification is
kept here, not in the routes.
"""

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import DeviceStatus, EnrolledDevice
from orion_backend.errors import (
    DeviceAlreadyEnrolledError,
    DeviceNotFoundError,
    DeviceRevokedError,
    InvalidCredentialError,
)
from orion_backend.services.credential_hasher import CredentialHasher


async def _get_by_device_id(db: AsyncSession, device_id: str) -> EnrolledDevice | None:
    result = await db.execute(select(EnrolledDevice).where(EnrolledDevice.device_id == device_id))
    return result.scalar_one_or_none()


async def get_device(db: AsyncSession, device_id: str) -> EnrolledDevice:
    device = await _get_by_device_id(db, device_id)
    if device is None:
        raise DeviceNotFoundError(device_id)
    return device


async def enroll_device(
    db: AsyncSession,
    hasher: CredentialHasher,
    device_id: str,
    site_id: str,
    enrollment_credential: str,
) -> EnrolledDevice:
    existing = await _get_by_device_id(db, device_id)
    if existing is not None:
        # Enrollment never silently overwrites an existing record, ACTIVE or
        # REVOKED — re-enrolling a revoked device is a separate, deliberate
        # decision this endpoint does not make on its own.
        raise DeviceAlreadyEnrolledError(device_id)

    device = EnrolledDevice(
        device_id=device_id,
        site_id=site_id,
        credential_hash=hasher.hash(enrollment_credential),
        status=DeviceStatus.ACTIVE,
    )
    db.add(device)
    await db.commit()
    await db.refresh(device)
    return device


async def verify_device(
    db: AsyncSession,
    hasher: CredentialHasher,
    device_id: str,
    enrollment_credential: str,
) -> EnrolledDevice:
    device = await _get_by_device_id(db, device_id)
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
    device = await _get_by_device_id(db, device_id)
    if device is None:
        raise DeviceNotFoundError(device_id)

    if device.status != DeviceStatus.REVOKED:
        device.status = DeviceStatus.REVOKED
        await db.commit()
        await db.refresh(device)
    return device
