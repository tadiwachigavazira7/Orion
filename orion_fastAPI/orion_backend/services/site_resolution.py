"""Organization/site lookup shared by enrollment, authorization issuance, and
operator commands."""

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import Organization, Site
from orion_backend.errors import (
    OrganizationNotFoundError,
    SiteNotFoundError,
    SiteOrganizationMismatchError,
)


async def find_organization(db: AsyncSession, organization_code: str) -> Organization | None:
    result = await db.execute(
        select(Organization).where(Organization.organization_code == organization_code)
    )
    return result.scalar_one_or_none()


async def resolve_organization(db: AsyncSession, organization_code: str) -> Organization:
    organization = await find_organization(db, organization_code)
    if organization is None:
        raise OrganizationNotFoundError(organization_code)
    return organization


async def find_site(db: AsyncSession, organization: Organization, site_code: str) -> Site | None:
    result = await db.execute(
        select(Site).where(
            Site.organization_id == organization.id,
            Site.site_code == site_code,
        )
    )
    return result.scalar_one_or_none()


async def resolve_site(db: AsyncSession, organization: Organization, site_code: str) -> Site:
    site = await find_site(db, organization, site_code)
    if site is not None:
        return site

    # Not found under this organization - determine whether the site_code
    # exists under a *different* organization (mismatch) or doesn't exist at
    # all (not found), so the two failure modes are distinguishable.
    result = await db.execute(select(Site.id).where(Site.site_code == site_code).limit(1))
    if result.first() is not None:
        raise SiteOrganizationMismatchError(site_code, organization.organization_code)
    raise SiteNotFoundError(site_code)
