"""Idempotently seed a development organization/site for manual testing.

Seeds:
  - Organization organization_code="TEST-ORG-001", name="Test Organization"
  - Site site_code="TEST-SITE-001" under that organization

Does NOT seed an EnrolledDevice row - devices are meant to be enrolled
through POST /enrollment as a real first-launch would, so this script leaves
that endpoint free to be exercised manually against the seeded org/site.

Safe to run repeatedly against a persistent dev database (checks before
inserting).

Usage (from orion_fastAPI/, with the project's venv active or invoked
directly):
    python scripts/seed_dev_data.py
"""

import asyncio

from sqlalchemy import select

from orion_backend.config import get_settings
from orion_backend.db.base import build_engine, build_sessionmaker
from orion_backend.db.models import Organization, Site

ORGANIZATION_CODE = "TEST-ORG-001"
ORGANIZATION_NAME = "Test Organization"
SITE_CODE = "TEST-SITE-001"


async def seed() -> None:
    settings = get_settings()
    engine = build_engine(settings.database_url)
    sessionmaker = build_sessionmaker(engine)

    async with sessionmaker() as db:
        result = await db.execute(
            select(Organization).where(Organization.organization_code == ORGANIZATION_CODE)
        )
        organization = result.scalar_one_or_none()
        if organization is None:
            organization = Organization(organization_code=ORGANIZATION_CODE, name=ORGANIZATION_NAME)
            db.add(organization)
            await db.commit()
            await db.refresh(organization)
            print(f"Created organization '{ORGANIZATION_CODE}' ({organization.id})")
        else:
            print(f"Organization '{ORGANIZATION_CODE}' already exists ({organization.id})")

        result = await db.execute(
            select(Site).where(
                Site.organization_id == organization.id,
                Site.site_code == SITE_CODE,
            )
        )
        site = result.scalar_one_or_none()
        if site is None:
            site = Site(site_code=SITE_CODE, organization_id=organization.id)
            db.add(site)
            await db.commit()
            await db.refresh(site)
            print(f"Created site '{SITE_CODE}' ({site.id}) under '{ORGANIZATION_CODE}'")
        else:
            print(f"Site '{SITE_CODE}' already exists ({site.id}) under '{ORGANIZATION_CODE}'")

    await engine.dispose()


if __name__ == "__main__":
    asyncio.run(seed())
