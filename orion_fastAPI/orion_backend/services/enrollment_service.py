"""Business logic for device enrollment against an organization/site pair,
gated by an operator-issued enrollment authorization.

Route handlers (orion_backend/api/enrollment.py) stay thin: they parse the
request, call enroll_device_at_site, and translate the result/exception into
an HTTP response.

Check order:
  1. organization / site exist and match           -> 404 (codes are not secrets)
  2. device not ACTIVE / REVOKED                   -> 409 device_already_enrolled
  3. authorization consumed atomically             -> 403 invalid_enrollment_authorization
  4. device created (first enrollment) or reactivated from PENDING_ENROLLMENT
Steps 3-4 commit in one transaction; any failure rolls back, leaving the
authorization unconsumed. Step 2 comes before step 3 so a device whose
enrollment response was lost (server says ACTIVE, device has no credential)
is told "already enrolled - ask an administrator to reset", not "bad
authorization".
"""

import asyncio
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import DeviceStatus, EnrolledDevice, Organization, Site
from orion_backend.errors import (
    DeviceAlreadyEnrolledError,
    InvalidEnrollmentAuthorizationError,
)
from orion_backend.services import device_service, enrollment_authorization_service, site_resolution
from orion_backend.services.credential_hasher import CredentialHasher
from orion_backend.services.device_service import OfflineAuthorizationWindow

# Credential length in bytes of entropy before URL-safe base64 encoding.
# 32 bytes (256 bits) comfortably exceeds what's needed for a bearer secret.
_CREDENTIAL_BYTES = 32


@dataclass(frozen=True)
class EnrollmentOutcome:
    device: EnrolledDevice
    organization: Organization
    site: Site
    # Plaintext, returned to the device exactly once. Never stored or logged.
    credential: str
    window: OfflineAuthorizationWindow


async def enroll_device_at_site(
    db: AsyncSession,
    hasher: CredentialHasher,
    organization_code: str,
    site_code: str,
    device_id: str,
    enrollment_authorization: str,
    *,
    offline_authorization_hours: int,
) -> EnrollmentOutcome:
    try:
        organization = await site_resolution.resolve_organization(db, organization_code)
        site = await site_resolution.resolve_site(db, organization, site_code)

        # Serializes with authorization issuance for this device_id (also
        # when no device row exists yet), see enrollment_authorization_service.
        await enrollment_authorization_service.lock_device_id(db, device_id)
        existing = await device_service.find_by_device_id(db, device_id, for_update=True)
        if existing is not None and existing.status in (
            DeviceStatus.ACTIVE,
            DeviceStatus.REVOKED,
        ):
            # Never reactivates or overwrites an ACTIVE/REVOKED device;
            # re-enrollment requires an explicit operator reset first.
            raise DeviceAlreadyEnrolledError(device_id)
        if existing is not None and existing.site_id != site.id:
            # A PENDING_ENROLLMENT device only re-enrolls at its own site.
            # Issuance already prevents such an authorization; this is the
            # defensive server-side check, reported generically.
            raise InvalidEnrollmentAuthorizationError()

        now = datetime.now(UTC)
        consumed = await enrollment_authorization_service.consume_authorization(
            db, enrollment_authorization, organization, site, device_id, now
        )
        if not consumed:
            raise InvalidEnrollmentAuthorizationError()

        # Only hash once the request is authorized, so invalid requests cannot
        # make the server spend Argon2id time. Runs off the event loop; the
        # row locks held meanwhile only affect this one device.
        plaintext_credential = secrets.token_urlsafe(_CREDENTIAL_BYTES)
        credential_hash = await asyncio.to_thread(hasher.hash, plaintext_credential)

        if existing is None:
            device = EnrolledDevice(
                device_id=device_id,
                site_id=site.id,
                credential_hash=credential_hash,
                status=DeviceStatus.ACTIVE,
            )
            db.add(device)
        else:
            device = existing
            device.credential_hash = credential_hash
            device.status = DeviceStatus.ACTIVE
            device.last_verified_at = None

        await db.commit()
    except IntegrityError as exc:
        # Unique device_id violated: a concurrent request created this device
        # first. Rolled back, so this request's authorization stays unconsumed.
        await db.rollback()
        raise DeviceAlreadyEnrolledError(device_id) from exc
    except Exception:
        await db.rollback()
        raise

    return EnrollmentOutcome(
        device=device,
        organization=organization,
        site=site,
        credential=plaintext_credential,
        window=device_service.offline_window(now, offline_authorization_hours),
    )
