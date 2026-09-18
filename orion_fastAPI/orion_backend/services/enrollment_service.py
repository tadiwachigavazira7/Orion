"""Business logic for first-launch device enrollment against an
organization/site pair.

Route handlers (orion_backend/api/enrollment.py) stay thin: they parse the
request, call enroll_device_at_site, and translate the result/exception into
an HTTP response. This module owns organization/site resolution, the
device-already-enrolled check, and server-side credential generation.
"""

import secrets

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import DeviceStatus, EnrolledDevice, Organization, Site
from orion_backend.errors import (
    DeviceAlreadyEnrolledError,
    OrganizationNotFoundError,
    SiteNotFoundError,
    SiteOrganizationMismatchError,
)
from orion_backend.services import device_service
from orion_backend.services.credential_hasher import CredentialHasher

# Credential length in bytes of entropy before URL-safe base64 encoding.
# 32 bytes (256 bits) comfortably exceeds what's needed for a bearer secret.
_CREDENTIAL_BYTES = 32


async def _resolve_organization(db: AsyncSession, organization_code: str) -> Organization:
    result = await db.execute(
        select(Organization).where(Organization.organization_code == organization_code)
    )
    organization = result.scalar_one_or_none()
    if organization is None:
        raise OrganizationNotFoundError(organization_code)
    return organization


async def _resolve_site(db: AsyncSession, organization: Organization, site_code: str) -> Site:
    result = await db.execute(
        select(Site).where(
            Site.organization_id == organization.id,
            Site.site_code == site_code,
        )
    )
    site = result.scalar_one_or_none()
    if site is not None:
        return site

    # Not found under this organization - determine whether the site_code
    # exists under a *different* organization (mismatch) or doesn't exist at
    # all (not found), so the two failure modes are distinguishable.
    result = await db.execute(select(Site).where(Site.site_code == site_code))
    other_site = result.scalars().first()
    if other_site is not None:
        raise SiteOrganizationMismatchError(site_code, organization.organization_code)
    raise SiteNotFoundError(site_code)


async def enroll_device_at_site(
    db: AsyncSession,
    hasher: CredentialHasher,
    organization_code: str,
    site_code: str,
    device_id: str,
) -> tuple[EnrolledDevice, Organization, Site, str]:
    organization = await _resolve_organization(db, organization_code)
    site = await _resolve_site(db, organization, site_code)

    existing = await device_service.find_by_device_id(db, device_id)
    if existing is not None:
        # Enrollment never silently overwrites an existing record, ACTIVE or
        # REVOKED - re-enrolling a revoked device is a separate, deliberate
        # decision this endpoint does not make on its own.
        raise DeviceAlreadyEnrolledError(device_id)

    plaintext_credential = secrets.token_urlsafe(_CREDENTIAL_BYTES)
    device = EnrolledDevice(
        device_id=device_id,
        site_id=site.id,
        credential_hash=hasher.hash(plaintext_credential),
        status=DeviceStatus.ACTIVE,
    )
    db.add(device)
    await db.commit()
    await db.refresh(device)
    return device, organization, site, plaintext_credential
