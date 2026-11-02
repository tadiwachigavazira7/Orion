"""Operator-only business rules: organizations, sites, device revoke/reset,
and sanitized device inspection.

There is deliberately no HTTP route to anything in this module; it is reached
only through the operator CLI (orion_backend/cli.py), which runs with direct
database access. Enrollment authorizations are issued by
services/enrollment_authorization_service.py.

Idempotency rules (also documented in OPERATIONS.md):
  create-organization  same code + same name   -> no-op success
                       same code + other name  -> organization_conflict
  create-site          code already in org     -> site_already_exists
  revoke-device        ACTIVE                  -> REVOKED
                       REVOKED                 -> no-op success
                       PENDING_ENROLLMENT      -> invalid_device_transition
  reset-device         ACTIVE | REVOKED        -> PENDING_ENROLLMENT
                       PENDING_ENROLLMENT      -> no-op success (still voids
                                                  outstanding authorizations)
  revoke/reset of an unknown device            -> device_not_found
Revoke and reset both invalidate the device's outstanding enrollment
authorizations. Reset never issues a new one.
"""

from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import (
    DeviceStatus,
    EnrolledDevice,
    EnrollmentAuthorization,
    Organization,
    Site,
)
from orion_backend.errors import (
    ConcurrentOperationError,
    DeviceNotFoundError,
    InvalidDeviceTransitionError,
    OrganizationConflictError,
    SiteAlreadyExistsError,
)
from orion_backend.services import device_service, enrollment_authorization_service, site_resolution
from orion_backend.services.validation import require_identifier, require_name


@dataclass(frozen=True)
class OrganizationResult:
    organization: Organization
    created: bool


@dataclass(frozen=True)
class DeviceTransitionResult:
    device_id: str
    organization_code: str
    site_code: str
    previous_status: DeviceStatus
    status: DeviceStatus
    changed: bool
    invalidated_authorizations: int


@dataclass(frozen=True)
class DeviceInspection:
    """Sanitized view of a device: never contains a credential, a credential
    hash, or an authorization secret/hash."""

    device_id: str
    organization_code: str
    site_code: str
    status: DeviceStatus
    has_credential: bool
    created_at: datetime
    updated_at: datetime
    last_verified_at: datetime | None
    usable_authorizations: int
    latest_usable_authorization_expires_at: datetime | None


async def create_organization(
    db: AsyncSession, organization_code: str, name: str
) -> OrganizationResult:
    organization_code = require_identifier("organization_code", organization_code)
    name = require_name("name", name)

    existing = await site_resolution.find_organization(db, organization_code)
    if existing is not None:
        if existing.name != name:
            raise OrganizationConflictError(organization_code)
        return OrganizationResult(organization=existing, created=False)

    organization = Organization(organization_code=organization_code, name=name)
    db.add(organization)
    try:
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        raise ConcurrentOperationError("organization creation") from exc
    return OrganizationResult(organization=organization, created=True)


async def create_site(db: AsyncSession, organization_code: str, site_code: str) -> Site:
    organization_code = require_identifier("organization_code", organization_code)
    site_code = require_identifier("site_code", site_code)

    organization = await site_resolution.resolve_organization(db, organization_code)
    if await site_resolution.find_site(db, organization, site_code) is not None:
        raise SiteAlreadyExistsError(site_code, organization_code)

    site = Site(site_code=site_code, organization_id=organization.id)
    db.add(site)
    try:
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        raise SiteAlreadyExistsError(site_code, organization_code) from exc
    return site


async def _locked_device(db: AsyncSession, device_id: str) -> EnrolledDevice:
    device_id = require_identifier("device_id", device_id)
    device = await device_service.find_by_device_id(db, device_id, for_update=True)
    if device is None:
        await db.rollback()
        raise DeviceNotFoundError(device_id)
    return device


def _transition_result(
    device: EnrolledDevice, previous: DeviceStatus, invalidated: int
) -> DeviceTransitionResult:
    return DeviceTransitionResult(
        device_id=device.device_id,
        organization_code=device.site.organization.organization_code,
        site_code=device.site.site_code,
        previous_status=previous,
        status=device.status,
        changed=previous != device.status,
        invalidated_authorizations=invalidated,
    )


async def revoke_device(db: AsyncSession, device_id: str) -> DeviceTransitionResult:
    """ACTIVE -> REVOKED. Keeps the credential hash so the device, presenting
    its real credential, is told "revoked" (an explicit denial)."""
    device = await _locked_device(db, device_id)
    previous = device.status
    if previous == DeviceStatus.PENDING_ENROLLMENT:
        # Build the error before rollback: rollback expires ORM attributes.
        error = InvalidDeviceTransitionError(device.device_id, "revoke", previous.value)
        await db.rollback()
        raise error

    try:
        now = datetime.now(UTC)
        device.status = DeviceStatus.REVOKED
        invalidated = await enrollment_authorization_service.invalidate_outstanding(
            db, device.device_id, now
        )
        await db.commit()
    except Exception:
        await db.rollback()
        raise
    return _transition_result(device, previous, invalidated)


async def reset_device(db: AsyncSession, device_id: str) -> DeviceTransitionResult:
    """ACTIVE | REVOKED -> PENDING_ENROLLMENT.

    Clears the credential hash (the old credential fails verification
    immediately), keeps the device record and its site binding, invalidates
    outstanding authorizations, and does NOT issue a new authorization.
    """
    device = await _locked_device(db, device_id)
    previous = device.status

    try:
        now = datetime.now(UTC)
        device.status = DeviceStatus.PENDING_ENROLLMENT
        device.credential_hash = None
        invalidated = await enrollment_authorization_service.invalidate_outstanding(
            db, device.device_id, now
        )
        await db.commit()
    except Exception:
        await db.rollback()
        raise
    return _transition_result(device, previous, invalidated)


async def inspect_device(db: AsyncSession, device_id: str) -> DeviceInspection:
    device_id = require_identifier("device_id", device_id)
    device = await device_service.find_by_device_id(db, device_id)
    if device is None:
        raise DeviceNotFoundError(device_id)

    now = datetime.now(UTC)
    result = await db.execute(
        select(
            func.count(EnrollmentAuthorization.id),
            func.max(EnrollmentAuthorization.expires_at),
        ).where(
            EnrollmentAuthorization.device_id == device_id,
            EnrollmentAuthorization.consumed_at.is_(None),
            EnrollmentAuthorization.invalidated_at.is_(None),
            EnrollmentAuthorization.expires_at > now,
        )
    )
    usable_count, latest_expiry = result.one()

    return DeviceInspection(
        device_id=device.device_id,
        organization_code=device.site.organization.organization_code,
        site_code=device.site.site_code,
        status=device.status,
        has_credential=device.credential_hash is not None,
        created_at=device.created_at,
        updated_at=device.updated_at,
        last_verified_at=device.last_verified_at,
        usable_authorizations=usable_count,
        latest_usable_authorization_expires_at=latest_expiry,
    )
