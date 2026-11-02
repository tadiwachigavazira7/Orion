"""Business logic for device lookup and verification.

Route handlers (orion_backend/api/devices.py) stay thin: they parse the
request, call one function here, and translate the result/exception into an
HTTP response. All database access and credential verification is kept here,
not in the routes. Operator-only transitions (revoke/reset) live in
services/admin_service.py and have no HTTP route.
"""

import asyncio
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from orion_backend.db.models import DeviceStatus, EnrolledDevice, Site
from orion_backend.errors import (
    DeviceNotFoundError,
    DeviceRevokedError,
    InvalidCredentialError,
)
from orion_backend.services.credential_hasher import CredentialHasher


@dataclass(frozen=True)
class OfflineAuthorizationWindow:
    """Server-decided window during which the device may run without
    re-verifying. The device derives its allowance from
    offline_valid_until - server_time and never extends it."""

    server_time: datetime
    offline_valid_until: datetime


@dataclass(frozen=True)
class VerifiedDevice:
    device_id: str
    organization_code: str
    site_code: str
    status: DeviceStatus
    window: OfflineAuthorizationWindow


def offline_window(now: datetime, offline_authorization_hours: int) -> OfflineAuthorizationWindow:
    return OfflineAuthorizationWindow(
        server_time=now,
        offline_valid_until=now + timedelta(hours=offline_authorization_hours),
    )


async def find_by_device_id(
    db: AsyncSession, device_id: str, *, for_update: bool = False
) -> EnrolledDevice | None:
    """Look up a device by device_id, eagerly loading its site and that
    site's organization.

    Callers need device.site.site_code and device.site.organization.
    organization_code; the async ORM does not support implicit lazy-loading,
    so those relationships are eagerly loaded here.

    for_update=True takes a row lock (SELECT ... FOR UPDATE) for the rest of
    the caller's transaction, serializing state transitions on one device
    (enrollment, revoke, reset, authorization issuance). Verification does
    not lock; see verify_device.
    """
    statement = (
        select(EnrolledDevice)
        .where(EnrolledDevice.device_id == device_id)
        .options(selectinload(EnrolledDevice.site).selectinload(Site.organization))
    )
    if for_update:
        statement = statement.with_for_update(of=EnrolledDevice).execution_options(
            populate_existing=True
        )
    result = await db.execute(statement)
    return result.scalar_one_or_none()


async def verify_device(
    db: AsyncSession,
    hasher: CredentialHasher,
    device_id: str,
    enrollment_credential: str,
    *,
    offline_authorization_hours: int,
) -> VerifiedDevice:
    """Verify a device credential.

    Order matters: the credential is checked BEFORE the device status is
    revealed, so a caller without the credential learns only "unknown
    device" or "invalid credential", never whether the device is revoked.
    A PENDING_ENROLLMENT device has no credential, so it always fails with
    invalid_credential.

    No row lock and no database connection is held while Argon2id runs:
    otherwise an unauthenticated caller who knows a device_id could queue
    on that device's row (blocking the real device and operator
    revoke/reset) and drain the connection pool. Instead, success is
    committed with a conditional UPDATE that re-checks the credential hash
    and ACTIVE status, so a reset or revoke that commits while the hash is
    being checked can never produce a 200.
    """
    device = await find_by_device_id(db, device_id)
    if device is None:
        raise DeviceNotFoundError(device_id)

    # Snapshot what we need, then end the read transaction (rollback expires
    # ORM attributes and returns the connection to the pool).
    row_id = device.id
    credential_hash = device.credential_hash
    status = device.status
    organization_code = device.site.organization.organization_code
    site_code = device.site.site_code
    await db.rollback()

    if credential_hash is None or not await asyncio.to_thread(
        hasher.verify, enrollment_credential, credential_hash
    ):
        raise InvalidCredentialError(device_id)
    if status == DeviceStatus.REVOKED:
        raise DeviceRevokedError(device_id)
    if status != DeviceStatus.ACTIVE:
        # Defensive: a non-ACTIVE, non-REVOKED device never holds a
        # credential (reset clears it), so treat it as an invalid credential.
        raise InvalidCredentialError(device_id)

    now = datetime.now(UTC)
    result = await db.execute(
        update(EnrolledDevice)
        .where(
            EnrolledDevice.id == row_id,
            EnrolledDevice.credential_hash == credential_hash,
            EnrolledDevice.status == DeviceStatus.ACTIVE,
        )
        .values(last_verified_at=now)
        .returning(EnrolledDevice.id)
        .execution_options(synchronize_session=False)
    )
    if result.first() is None:
        # The device changed while the credential was being checked.
        current = (
            await db.execute(
                select(EnrolledDevice.status, EnrolledDevice.credential_hash).where(
                    EnrolledDevice.id == row_id
                )
            )
        ).first()
        await db.rollback()
        if (
            current is not None
            and current.status == DeviceStatus.REVOKED
            and current.credential_hash == credential_hash
        ):
            raise DeviceRevokedError(device_id)
        raise InvalidCredentialError(device_id)
    await db.commit()

    return VerifiedDevice(
        device_id=device_id,
        organization_code=organization_code,
        site_code=site_code,
        status=DeviceStatus.ACTIVE,
        window=offline_window(now, offline_authorization_hours),
    )
