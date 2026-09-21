"""Business logic for Orion's internal organization/site provisioning.

Route handlers (orion_backend/api/provisioning.py) stay thin: they parse the
request, call one function here, and translate the result/exception into an
HTTP response. This module is the only place that creates Organization/Site
rows - enrollment_service (orion_backend/services/enrollment_service.py)
only ever resolves and rejects, it never creates them (see CLAUDE.md). This
keeps the Android enrollment surface read-only with respect to orgs/sites,
structurally separate from Orion's own provisioning surface.
"""

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import Organization, Site
from orion_backend.errors import (
    OrganizationCodeAlreadyExistsError,
    OrganizationNotFoundError,
    SiteCodeAlreadyExistsError,
)


async def _resolve_organization(db: AsyncSession, organization_code: str) -> Organization:
    result = await db.execute(
        select(Organization).where(Organization.organization_code == organization_code)
    )
    organization = result.scalar_one_or_none()
    if organization is None:
        raise OrganizationNotFoundError(organization_code)
    return organization


async def create_organization(db: AsyncSession, organization_code: str, name: str) -> Organization:
    result = await db.execute(
        select(Organization).where(Organization.organization_code == organization_code)
    )
    existing = result.scalar_one_or_none()
    if existing is not None:
        # Explicit pre-check first (same pattern as
        # enrollment_service.enroll_device_at_site checking
        # device_service.find_by_device_id) - the organization_code unique
        # index remains the DB-level backstop against a race between the
        # check and the insert.
        raise OrganizationCodeAlreadyExistsError(organization_code)

    organization = Organization(organization_code=organization_code, name=name)
    db.add(organization)
    try:
        await db.commit()
    except IntegrityError:
        # A concurrent request won the race between the pre-check above and
        # this commit and inserted the same organization_code first. Without
        # this, the unique-index violation propagates as an unhandled
        # IntegrityError (bare 500) instead of the intended 409.
        await db.rollback()
        raise OrganizationCodeAlreadyExistsError(organization_code) from None
    await db.refresh(organization)
    return organization


async def create_site(
    db: AsyncSession,
    organization_code: str,
    site_code: str,
) -> tuple[Site, Organization]:
    # A site is never auto-created under a nonexistent organization - the
    # organization must already exist via create_organization first.
    organization = await _resolve_organization(db, organization_code)

    result = await db.execute(
        select(Site).where(
            Site.organization_id == organization.id,
            Site.site_code == site_code,
        )
    )
    existing = result.scalar_one_or_none()
    if existing is not None:
        raise SiteCodeAlreadyExistsError(site_code, organization_code)

    site = Site(site_code=site_code, organization_id=organization.id)
    db.add(site)
    try:
        await db.commit()
    except IntegrityError:
        # Same race as create_organization above, scoped to
        # (organization_id, site_code) via uq_sites_organization_id_site_code.
        await db.rollback()
        raise SiteCodeAlreadyExistsError(site_code, organization_code) from None
    await db.refresh(site)
    return site, organization
