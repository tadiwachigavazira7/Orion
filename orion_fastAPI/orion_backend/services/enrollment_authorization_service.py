"""Operator-issued, short-lived, single-use enrollment authorizations.

Lifecycle of one authorization row:
  issued      -> consumed     (POST /enrollment succeeded, same transaction)
  issued      -> invalidated  (a newer authorization was issued for the same
                               device, or the device was revoked/reset)
  issued      -> expired      (expires_at passed; never usable afterwards)

Secrets are secrets.token_urlsafe(32) (256 bits). Only a SHA-256 hex digest is
stored. A slow, salted hash (Argon2id, as used for device credentials) is not
needed for a 256-bit random value - it cannot be guessed - and a
deterministic digest is what makes an indexed lookup and an atomic
conditional UPDATE possible at consumption time.

Concurrency: issuance and enrollment for the same device_id are serialized
by a transaction-scoped advisory lock (lock_device_id). A row lock alone is
not enough because a first-time device has no enrolled_devices row to lock;
without it, issuance racing a first enrollment could leave a live
authorization on a now-ACTIVE device.

Lock ordering (to avoid deadlocks): device_id advisory lock, then the
enrolled_devices row, then enrollment_authorizations rows. Revoke/reset take
only the row lock and later locks, which keeps the order acyclic.
"""

import hashlib
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import (
    DeviceStatus,
    EnrollmentAuthorization,
    Organization,
    Site,
)
from orion_backend.errors import (
    ConcurrentOperationError,
    DeviceResetRequiredError,
    DeviceSiteConflictError,
)
from orion_backend.services import device_service, site_resolution
from orion_backend.services.validation import require_identifier

_SECRET_BYTES = 32


def hash_authorization_secret(secret: str) -> str:
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


async def lock_device_id(db: AsyncSession, device_id: str) -> None:
    """Take a transaction-scoped advisory lock for device_id (released on
    commit/rollback). hashtext collisions only serialize unrelated devices;
    they never affect correctness."""
    await db.execute(select(func.pg_advisory_xact_lock(func.hashtext(device_id))))


@dataclass(frozen=True)
class IssuedAuthorization:
    organization_code: str
    site_code: str
    device_id: str
    expires_at: datetime
    # Plaintext, returned to the operator exactly once. Never stored or logged.
    secret: str


async def invalidate_outstanding(db: AsyncSession, device_id: str, now: datetime) -> int:
    """Invalidate every not-yet-consumed, not-yet-invalidated authorization for
    device_id (expired ones included, so they also leave the
    one-outstanding-per-device index). Does not commit."""
    result = await db.execute(
        update(EnrollmentAuthorization)
        .where(
            EnrollmentAuthorization.device_id == device_id,
            EnrollmentAuthorization.consumed_at.is_(None),
            EnrollmentAuthorization.invalidated_at.is_(None),
        )
        .values(invalidated_at=now)
    )
    return result.rowcount or 0


async def issue_authorization(
    db: AsyncSession,
    organization_code: str,
    site_code: str,
    device_id: str,
    *,
    ttl_minutes: int,
) -> IssuedAuthorization:
    """Issue a new authorization for device_id at organization/site.

    Rules:
      * organization and site must exist (and match).
      * No existing device record: allowed (first enrollment).
      * PENDING_ENROLLMENT device: allowed only at the site it is bound to.
      * ACTIVE or REVOKED device: rejected - reset it first.
      * Any previous outstanding authorization for the device is invalidated.
    Not idempotent by design: every call produces a fresh secret and voids
    the previous one.
    """
    organization_code = require_identifier("organization_code", organization_code)
    site_code = require_identifier("site_code", site_code)
    device_id = require_identifier("device_id", device_id)
    if ttl_minutes <= 0:
        raise ValueError("ttl_minutes must be positive")

    organization = await site_resolution.resolve_organization(db, organization_code)
    site = await site_resolution.resolve_site(db, organization, site_code)

    try:
        await lock_device_id(db, device_id)
        device = await device_service.find_by_device_id(db, device_id, for_update=True)
        if device is not None:
            if device.status in (DeviceStatus.ACTIVE, DeviceStatus.REVOKED):
                raise DeviceResetRequiredError(device_id, device.status.value)
            if device.site_id != site.id:
                raise DeviceSiteConflictError(
                    device_id,
                    device.site.organization.organization_code,
                    device.site.site_code,
                )

        now = datetime.now(UTC)
        await invalidate_outstanding(db, device_id, now)

        secret = secrets.token_urlsafe(_SECRET_BYTES)
        authorization = EnrollmentAuthorization(
            organization_id=organization.id,
            site_id=site.id,
            device_id=device_id,
            secret_hash=hash_authorization_secret(secret),
            expires_at=now + timedelta(minutes=ttl_minutes),
        )
        db.add(authorization)
        await db.commit()
    except IntegrityError as exc:
        # The partial unique index allows one outstanding authorization per
        # device; a concurrent issuance for the same device won the race.
        await db.rollback()
        raise ConcurrentOperationError("enrollment authorization issuance") from exc
    except Exception:
        await db.rollback()
        raise

    return IssuedAuthorization(
        organization_code=organization.organization_code,
        site_code=site.site_code,
        device_id=device_id,
        expires_at=authorization.expires_at,
        secret=secret,
    )


async def consume_authorization(
    db: AsyncSession,
    secret: str,
    organization: Organization,
    site: Site,
    device_id: str,
    now: datetime,
) -> bool:
    """Atomically mark a matching, usable authorization as consumed.

    A single conditional UPDATE: it matches only if the secret, device,
    site and organization all match and the authorization is unconsumed,
    not invalidated, and unexpired. Postgres row-locks the matched row, so a
    concurrent transaction consuming the same authorization blocks, then
    re-evaluates the WHERE clause after the first commits and matches
    nothing. Does not commit: the caller commits together with the device
    activation, so any later failure rolls the consumption back.

    Returns False (no detail about which condition failed) when unusable.
    """
    result = await db.execute(
        update(EnrollmentAuthorization)
        .where(
            EnrollmentAuthorization.secret_hash == hash_authorization_secret(secret),
            EnrollmentAuthorization.device_id == device_id,
            EnrollmentAuthorization.site_id == site.id,
            EnrollmentAuthorization.organization_id == organization.id,
            EnrollmentAuthorization.consumed_at.is_(None),
            EnrollmentAuthorization.invalidated_at.is_(None),
            EnrollmentAuthorization.expires_at > now,
        )
        .values(consumed_at=now)
        .returning(EnrollmentAuthorization.id)
    )
    return result.first() is not None
