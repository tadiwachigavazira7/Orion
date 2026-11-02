"""Operator-only business rules: organizations, sites, subscriptions and
capacity, store enrollment codes, device revoke/reset, and sanitized
inspection.

There is deliberately no HTTP route to anything in this module; it is reached
only through the operator CLI (orion_backend/cli.py), which runs with direct
database access. Every change writes an admin_audit_events row in the same
transaction (services/audit_service.py). No-ops write nothing.

Rules (also documented in OPERATIONS.md):
  create-organization      same code + same name   -> no-op success
                           same code + other name  -> organization_conflict
  create-site              code already in org     -> site_already_exists
                           creates the subscription: INACTIVE unless a status
                           is given; 6 included slots, 0 additional
  set-subscription-status  same status             -> no-op success
  set-additional-slots     included + n < ACTIVE devices -> capacity_below_active_devices
                           same value              -> no-op success
  rotate-enrollment-code   always replaces the code (devices untouched)
  revoke-device            ACTIVE -> REVOKED; REVOKED no-op; PENDING_ENROLLMENT rejected
  reset-device             ACTIVE | REVOKED -> PENDING_ENROLLMENT; PENDING no-op
  unknown device                                   -> device_not_found
Revoke/reset never change a subscription or its slots; they free a slot
because only ACTIVE devices count. Capacity is never increased automatically.

Locking: capacity/status/code changes lock the site_subscriptions row
(the same row enrollment locks); revoke/reset lock only the device row.
"""

import asyncio
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import (
    DEFAULT_INCLUDED_DEVICE_SLOTS,
    DeviceStatus,
    EnrolledDevice,
    Organization,
    Site,
    SiteSubscription,
    SubscriptionStatus,
)
from orion_backend.errors import (
    CapacityBelowActiveDevicesError,
    ConcurrentOperationError,
    DeviceNotFoundError,
    InvalidDeviceTransitionError,
    OperatorInputError,
    OrganizationConflictError,
    SiteAlreadyExistsError,
    SubscriptionNotFoundError,
)
from orion_backend.services import audit_service, device_service, site_resolution, store_code_service
from orion_backend.services.audit_service import Actor
from orion_backend.services.credential_hasher import CredentialHasher
from orion_backend.services.validation import require_identifier, require_name

MAX_ADDITIONAL_DEVICE_SLOTS = 10_000


@dataclass(frozen=True)
class OrganizationResult:
    organization: Organization
    created: bool


@dataclass(frozen=True)
class SubscriptionView:
    status: SubscriptionStatus
    included_device_slots: int
    additional_device_slots: int
    total_authorized_slots: int
    status_changed_at: datetime


@dataclass(frozen=True)
class SubscriptionChangeResult:
    organization_code: str
    site_code: str
    previous: SubscriptionView
    current: SubscriptionView
    active_devices: int
    changed: bool


@dataclass(frozen=True)
class SiteResult:
    organization_code: str
    site_code: str
    subscription: SubscriptionView


@dataclass(frozen=True)
class RotatedCode:
    organization_code: str
    site_code: str
    rotated_at: datetime
    # Plaintext in display form, returned to the operator exactly once.
    # Never stored or logged.
    code: str


@dataclass(frozen=True)
class SiteInspection:
    """Sanitized: never contains the store code or its hash."""

    organization_code: str
    site_code: str
    subscription: SubscriptionView
    active_devices: int
    remaining_capacity: int
    has_enrollment_code: bool
    enrollment_code_rotated_at: datetime | None


@dataclass(frozen=True)
class DeviceTransitionResult:
    device_id: str
    organization_code: str
    site_code: str
    previous_status: DeviceStatus
    status: DeviceStatus
    changed: bool


@dataclass(frozen=True)
class DeviceInspection:
    """Sanitized view of a device: never contains a credential or a hash."""

    device_id: str
    organization_code: str
    site_code: str
    status: DeviceStatus
    has_credential: bool
    created_at: datetime
    updated_at: datetime
    last_verified_at: datetime | None


def _view(subscription: SiteSubscription) -> SubscriptionView:
    return SubscriptionView(
        status=subscription.status,
        included_device_slots=subscription.included_device_slots,
        additional_device_slots=subscription.additional_device_slots,
        total_authorized_slots=subscription.total_authorized_slots,
        status_changed_at=subscription.status_changed_at,
    )


def _subscription_audit(view: SubscriptionView) -> dict[str, object]:
    return {
        "status": view.status.value,
        "included_device_slots": view.included_device_slots,
        "additional_device_slots": view.additional_device_slots,
        "total_authorized_slots": view.total_authorized_slots,
    }


async def _resolve_site(db: AsyncSession, organization_code: str, site_code: str) -> tuple[Organization, Site]:
    organization_code = require_identifier("organization_code", organization_code)
    site_code = require_identifier("site_code", site_code)
    organization = await site_resolution.resolve_organization(db, organization_code)
    site = await site_resolution.resolve_site(db, organization, site_code)
    return organization, site


async def _locked_subscription(db: AsyncSession, site: Site) -> SiteSubscription:
    subscription = (
        await db.execute(
            select(SiteSubscription)
            .where(SiteSubscription.site_id == site.id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    ).scalar_one_or_none()
    if subscription is None:
        raise SubscriptionNotFoundError(site.site_code)
    return subscription


# --- organizations / sites ----------------------------------------------------


async def create_organization(
    db: AsyncSession, organization_code: str, name: str, actor: Actor
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
        await db.flush()
        audit_service.record(
            db,
            actor,
            "organization.create",
            organization_id=organization.id,
            after={"organization_code": organization_code, "name": name},
        )
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        raise ConcurrentOperationError("organization creation") from exc
    return OrganizationResult(organization=organization, created=True)


async def create_site(
    db: AsyncSession,
    organization_code: str,
    site_code: str,
    actor: Actor,
    status: SubscriptionStatus = SubscriptionStatus.INACTIVE,
) -> SiteResult:
    organization_code = require_identifier("organization_code", organization_code)
    site_code = require_identifier("site_code", site_code)

    organization = await site_resolution.resolve_organization(db, organization_code)
    if await site_resolution.find_site(db, organization, site_code) is not None:
        raise SiteAlreadyExistsError(site_code, organization_code)

    now = datetime.now(UTC)
    site = Site(site_code=site_code, organization_id=organization.id)
    db.add(site)
    try:
        await db.flush()
        subscription = SiteSubscription(
            site_id=site.id,
            status=status,
            included_device_slots=DEFAULT_INCLUDED_DEVICE_SLOTS,
            additional_device_slots=0,
            status_changed_at=now,
        )
        db.add(subscription)
        await db.flush()
        view = _view(subscription)
        audit_service.record(
            db,
            actor,
            "site.create",
            organization_id=organization.id,
            site_id=site.id,
            after={"site_code": site_code, **_subscription_audit(view)},
        )
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        raise SiteAlreadyExistsError(site_code, organization_code) from exc
    return SiteResult(organization_code=organization_code, site_code=site_code, subscription=view)


# --- subscription / capacity ----------------------------------------------------


async def set_subscription_status(
    db: AsyncSession,
    organization_code: str,
    site_code: str,
    status: SubscriptionStatus,
    actor: Actor,
) -> SubscriptionChangeResult:
    """Changes only whether NEW enrollments are allowed. Already-enrolled
    devices keep verifying regardless of status."""
    try:
        organization, site = await _resolve_site(db, organization_code, site_code)
        subscription = await _locked_subscription(db, site)
        previous = _view(subscription)
        active = await device_service.count_active_devices(db, site.id)

        changed = subscription.status != status
        if changed:
            subscription.status = status
            subscription.status_changed_at = datetime.now(UTC)
            await db.flush()
            await db.refresh(subscription)
            audit_service.record(
                db,
                actor,
                "subscription.set_status",
                organization_id=organization.id,
                site_id=site.id,
                before={"status": previous.status.value},
                after={"status": status.value},
            )
        current = _view(subscription)
        await db.commit()
    except Exception:
        await db.rollback()
        raise
    return SubscriptionChangeResult(
        organization_code=organization.organization_code,
        site_code=site.site_code,
        previous=previous,
        current=current,
        active_devices=active,
        changed=changed,
    )


async def set_additional_slots(
    db: AsyncSession,
    organization_code: str,
    site_code: str,
    additional_device_slots: int,
    actor: Actor,
) -> SubscriptionChangeResult:
    """Set the number of additional slots (total = included + additional).
    Rejected if the new total would be below the site's ACTIVE devices."""
    if not 0 <= additional_device_slots <= MAX_ADDITIONAL_DEVICE_SLOTS:
        raise OperatorInputError(
            f"additional_device_slots must be between 0 and {MAX_ADDITIONAL_DEVICE_SLOTS}."
        )
    try:
        organization, site = await _resolve_site(db, organization_code, site_code)
        # Holding this lock blocks concurrent enrollments at this site, so the
        # ACTIVE count below cannot grow before we commit.
        subscription = await _locked_subscription(db, site)
        previous = _view(subscription)
        active = await device_service.count_active_devices(db, site.id)

        requested_total = subscription.included_device_slots + additional_device_slots
        if requested_total < active:
            raise CapacityBelowActiveDevicesError(requested_total, active)

        changed = subscription.additional_device_slots != additional_device_slots
        if changed:
            subscription.additional_device_slots = additional_device_slots
            await db.flush()
            await db.refresh(subscription)
            current_view = _view(subscription)
            audit_service.record(
                db,
                actor,
                "subscription.set_additional_slots",
                organization_id=organization.id,
                site_id=site.id,
                before={
                    "additional_device_slots": previous.additional_device_slots,
                    "total_authorized_slots": previous.total_authorized_slots,
                },
                after={
                    "additional_device_slots": current_view.additional_device_slots,
                    "total_authorized_slots": current_view.total_authorized_slots,
                },
            )
        current = _view(subscription)
        await db.commit()
    except Exception:
        await db.rollback()
        raise
    return SubscriptionChangeResult(
        organization_code=organization.organization_code,
        site_code=site.site_code,
        previous=previous,
        current=current,
        active_devices=active,
        changed=changed,
    )


async def rotate_enrollment_code(
    db: AsyncSession,
    hasher: CredentialHasher,
    organization_code: str,
    site_code: str,
    actor: Actor,
) -> RotatedCode:
    """Generate a new store code and replace the stored hash. The old code
    stops working immediately; enrolled devices and their credentials are
    untouched."""
    organization, site = await _resolve_site(db, organization_code, site_code)
    resolved_organization_code = organization.organization_code
    resolved_site_code = site.site_code
    await db.rollback()  # release the connection while Argon2id runs

    normalized = store_code_service.generate_code()
    code_hash = await asyncio.to_thread(hasher.hash, normalized)

    try:
        organization, site = await _resolve_site(db, organization_code, site_code)
        await _locked_subscription(db, site)  # serializes with in-flight enrollments
        had_code = site.enrollment_code_hash is not None
        now = datetime.now(UTC)
        site.enrollment_code_hash = code_hash
        site.enrollment_code_rotated_at = now
        audit_service.record(
            db,
            actor,
            "site.rotate_enrollment_code",
            organization_id=organization.id,
            site_id=site.id,
            before={"had_enrollment_code": had_code},
            after={"rotated": True},
        )
        await db.commit()
    except Exception:
        await db.rollback()
        raise
    return RotatedCode(
        organization_code=resolved_organization_code,
        site_code=resolved_site_code,
        rotated_at=now,
        code=store_code_service.format_code(normalized),
    )


async def inspect_site(db: AsyncSession, organization_code: str, site_code: str) -> SiteInspection:
    organization, site = await _resolve_site(db, organization_code, site_code)
    subscription = (
        await db.execute(select(SiteSubscription).where(SiteSubscription.site_id == site.id))
    ).scalar_one_or_none()
    if subscription is None:
        raise SubscriptionNotFoundError(site.site_code)
    view = _view(subscription)
    active = await device_service.count_active_devices(db, site.id)
    return SiteInspection(
        organization_code=organization.organization_code,
        site_code=site.site_code,
        subscription=view,
        active_devices=active,
        remaining_capacity=max(0, view.total_authorized_slots - active),
        has_enrollment_code=site.enrollment_code_hash is not None,
        enrollment_code_rotated_at=site.enrollment_code_rotated_at,
    )


# --- devices --------------------------------------------------------------------


async def _locked_device(db: AsyncSession, device_id: str) -> EnrolledDevice:
    device_id = require_identifier("device_id", device_id)
    device = await device_service.find_by_device_id(db, device_id, for_update=True)
    if device is None:
        await db.rollback()
        raise DeviceNotFoundError(device_id)
    return device


def _transition_result(device: EnrolledDevice, previous: DeviceStatus) -> DeviceTransitionResult:
    return DeviceTransitionResult(
        device_id=device.device_id,
        organization_code=device.site.organization.organization_code,
        site_code=device.site.site_code,
        previous_status=previous,
        status=device.status,
        changed=previous != device.status,
    )


def _record_device_change(
    db: AsyncSession, actor: Actor, action: str, device: EnrolledDevice, previous: DeviceStatus
) -> None:
    audit_service.record(
        db,
        actor,
        action,
        organization_id=device.site.organization_id,
        site_id=device.site_id,
        device_id=device.device_id,
        before={"status": previous.value},
        after={"status": device.status.value},
    )


async def revoke_device(db: AsyncSession, device_id: str, actor: Actor) -> DeviceTransitionResult:
    """ACTIVE -> REVOKED. Keeps the credential hash so the device, presenting
    its real credential, is told "revoked" (an explicit denial). Frees a
    capacity slot; never changes the subscription."""
    device = await _locked_device(db, device_id)
    previous = device.status
    if previous == DeviceStatus.PENDING_ENROLLMENT:
        # Build the error before rollback: rollback expires ORM attributes.
        error = InvalidDeviceTransitionError(device.device_id, "revoke", previous.value)
        await db.rollback()
        raise error

    try:
        if previous != DeviceStatus.REVOKED:
            device.status = DeviceStatus.REVOKED
            _record_device_change(db, actor, "device.revoke", device, previous)
        result = _transition_result(device, previous)
        await db.commit()
    except Exception:
        await db.rollback()
        raise
    return result


async def reset_device(db: AsyncSession, device_id: str, actor: Actor) -> DeviceTransitionResult:
    """ACTIVE | REVOKED -> PENDING_ENROLLMENT.

    Clears the credential hash (the old credential fails verification
    immediately) and keeps the device record and its site binding. The
    device re-enrolls with the store code, which needs a free slot again.
    """
    device = await _locked_device(db, device_id)
    previous = device.status

    try:
        if previous != DeviceStatus.PENDING_ENROLLMENT:
            device.status = DeviceStatus.PENDING_ENROLLMENT
            device.credential_hash = None
            _record_device_change(db, actor, "device.reset", device, previous)
        result = _transition_result(device, previous)
        await db.commit()
    except Exception:
        await db.rollback()
        raise
    return result


async def inspect_device(db: AsyncSession, device_id: str) -> DeviceInspection:
    device_id = require_identifier("device_id", device_id)
    device = await device_service.find_by_device_id(db, device_id)
    if device is None:
        raise DeviceNotFoundError(device_id)
    return DeviceInspection(
        device_id=device.device_id,
        organization_code=device.site.organization.organization_code,
        site_code=device.site.site_code,
        status=device.status,
        has_credential=device.credential_hash is not None,
        created_at=device.created_at,
        updated_at=device.updated_at,
        last_verified_at=device.last_verified_at,
    )
