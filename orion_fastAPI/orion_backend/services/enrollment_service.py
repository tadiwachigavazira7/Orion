"""Business logic for device enrollment with a store enrollment code, under
the site's subscription capacity.

Route handlers (orion_backend/api/enrollment.py) stay thin: they parse the
request, call enroll_device_at_site, and translate the result/exception into
an HTTP response.

Check order (each step only runs if the previous ones passed):
  1. organization exists                       -> 404 organization_not_found
  2. site exists in organization               -> 404 site_not_found / site_organization_mismatch
  3. failed-attempt rate limit (before Argon2) -> 429 enrollment_rate_limited (+ Retry-After)
  4. device not ACTIVE / REVOKED               -> 409 device_already_enrolled
  5. store code verifies                       -> 403 invalid_enrollment_authorization
                                                  (failure recorded in its own committed txn)
  6. subscription PILOT / ACTIVE               -> 403 subscription_inactive
  7. one transaction: advisory lock(device_id) -> SELECT ... FOR UPDATE site_subscriptions
     row -> re-check code hash unchanged (rotation race -> 403) -> re-check device
     -> count ACTIVE devices -> 409 device_limit_reached if count >= total
     -> create/activate device, commit.
Step 6 is evaluated under the subscription row lock taken in step 7, so a
concurrent status change cannot slip in between.

Lock order: advisory(device_id) -> subscription row(site) -> device row.
Operator capacity/status/code changes lock only the subscription row;
revoke/reset lock only the device row. No cycles.

No database connection is held while Argon2id runs (steps 5 and credential
hashing), and no row lock is held during it either. A rejected request never
returns or persists a credential and never consumes a slot.

Step 4 precedes the code check deliberately (v2 behavior): a device whose
enrollment response was lost is told "already enrolled - ask an
administrator to reset". Subscription state (step 6) is only revealed to
callers holding the store code.
"""

import asyncio
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import (
    ENROLLABLE_SUBSCRIPTION_STATUSES,
    DeviceStatus,
    EnrolledDevice,
    Site,
    SiteSubscription,
)
from orion_backend.errors import (
    DeviceAlreadyEnrolledError,
    DeviceLimitReachedError,
    InvalidEnrollmentAuthorizationError,
    SubscriptionInactiveError,
)
from orion_backend.services import device_service, rate_limit_service, site_resolution
from orion_backend.services.credential_hasher import CredentialHasher
from orion_backend.services.device_service import OfflineAuthorizationWindow
from orion_backend.services.rate_limit_service import RateLimitPolicy
from orion_backend.services.store_code_service import normalize_code

# Credential length in bytes of entropy before URL-safe base64 encoding.
# 32 bytes (256 bits) comfortably exceeds what's needed for a bearer secret.
_CREDENTIAL_BYTES = 32


@dataclass(frozen=True)
class EnrollmentOutcome:
    device_id: str
    organization_code: str
    site_code: str
    status: DeviceStatus
    # Plaintext, returned to the device exactly once. Never stored or logged.
    credential: str
    window: OfflineAuthorizationWindow


def _already_enrolled(device: EnrolledDevice | None) -> bool:
    return device is not None and device.status in (DeviceStatus.ACTIVE, DeviceStatus.REVOKED)


async def enroll_device_at_site(
    db: AsyncSession,
    hasher: CredentialHasher,
    organization_code: str,
    site_code: str,
    device_id: str,
    store_code: str,
    *,
    client_ip: str | None,
    rate_limit: RateLimitPolicy,
    offline_authorization_hours: int,
) -> EnrollmentOutcome:
    # --- Steps 1-4: cheap checks, no locks ----------------------------------
    try:
        organization = await site_resolution.resolve_organization(db, organization_code)
        site = await site_resolution.resolve_site(db, organization, site_code)
        site_id = site.id
        resolved_organization_code = organization.organization_code
        resolved_site_code = site.site_code
        verified_code_hash = site.enrollment_code_hash

        await rate_limit_service.ensure_not_limited(
            db, site_id, client_ip, rate_limit, datetime.now(UTC)
        )

        if _already_enrolled(await device_service.find_by_device_id(db, device_id)):
            raise DeviceAlreadyEnrolledError(device_id)
    finally:
        # End the read transaction: returns the connection to the pool before
        # any Argon2id work.
        await db.rollback()

    # --- Step 5: store code (Argon2id, off the event loop, nothing held) ----
    normalized = normalize_code(store_code)
    code_ok = (
        verified_code_hash is not None
        and normalized != ""
        and await asyncio.to_thread(hasher.verify, normalized, verified_code_hash)
    )
    if not code_ok:
        await rate_limit_service.record_failure(
            db, site_id, client_ip, rate_limit, datetime.now(UTC)
        )
        raise InvalidEnrollmentAuthorizationError()

    # The caller holds this store's code, so hashing a credential now is not
    # an amplification vector. Done before taking locks.
    plaintext_credential = secrets.token_urlsafe(_CREDENTIAL_BYTES)
    credential_hash = await asyncio.to_thread(hasher.hash, plaintext_credential)

    # --- Steps 6-7: one transaction under locks -----------------------------
    try:
        await device_service.lock_device_id(db, device_id)

        subscription = (
            await db.execute(
                select(SiteSubscription)
                .where(SiteSubscription.site_id == site_id)
                .with_for_update()
            )
        ).scalar_one_or_none()
        if subscription is None or subscription.status not in ENROLLABLE_SUBSCRIPTION_STATUSES:
            raise SubscriptionInactiveError()

        current_code_hash = (
            await db.execute(select(Site.enrollment_code_hash).where(Site.id == site_id))
        ).scalar_one()
        if current_code_hash != verified_code_hash:
            # Rotated while the code was being checked: the submitted code is
            # no longer this store's code.
            raise InvalidEnrollmentAuthorizationError()

        existing = await device_service.find_by_device_id(db, device_id, for_update=True)
        if _already_enrolled(existing):
            raise DeviceAlreadyEnrolledError(device_id)
        if existing is not None and existing.site_id != site_id:
            # A PENDING_ENROLLMENT device only re-enrolls at its own site;
            # devices are never moved. Reported generically.
            raise InvalidEnrollmentAuthorizationError()

        active = await device_service.count_active_devices(db, site_id)
        if active >= subscription.total_authorized_slots:
            raise DeviceLimitReachedError()

        if existing is None:
            db.add(
                EnrolledDevice(
                    device_id=device_id,
                    site_id=site_id,
                    credential_hash=credential_hash,
                    status=DeviceStatus.ACTIVE,
                )
            )
        else:
            existing.credential_hash = credential_hash
            existing.status = DeviceStatus.ACTIVE
            existing.last_verified_at = None

        now = datetime.now(UTC)
        await db.commit()
    except IntegrityError as exc:
        # Unique device_id: a concurrent request created this device first.
        await db.rollback()
        raise DeviceAlreadyEnrolledError(device_id) from exc
    except Exception:
        await db.rollback()
        raise

    return EnrollmentOutcome(
        device_id=device_id,
        organization_code=resolved_organization_code,
        site_code=resolved_site_code,
        status=DeviceStatus.ACTIVE,
        credential=plaintext_credential,
        window=device_service.offline_window(now, offline_authorization_hours),
    )
