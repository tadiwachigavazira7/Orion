"""Idempotently seed a development organization/site for manual testing.

DEVELOPMENT ONLY. Refuses to run (exit code 2) unless ORION_ENVIRONMENT is
explicitly set to "development" (in the environment or .env). An unset value,
"test", or "production" is refused before any database connection is made.

Seeds:
  - Organization organization_code="TEST-ORG-001", name="Test Organization"
  - Site site_code="TEST-SITE-001" under that organization

Does NOT seed an EnrolledDevice row or an enrollment authorization, and
embeds no secrets. Devices enroll through POST /enrollment with an
authorization issued by the operator CLI:

    uv run python -m orion_backend.cli issue-enrollment-authorization \\
        --organization-code TEST-ORG-001 --site-code TEST-SITE-001 --device-id <device-id>

Safe to run repeatedly against a persistent dev database (checks before
inserting).

Usage (from orion_fastAPI/):
    ORION_ENVIRONMENT=development uv run python scripts/seed_dev_data.py
"""

import asyncio
import sys

from pydantic import ValidationError
from sqlalchemy import select

from orion_backend.config import Settings, get_settings
from orion_backend.db.base import build_engine, build_sessionmaker
from orion_backend.db.models import Organization, Site

ORGANIZATION_CODE = "TEST-ORG-001"
ORGANIZATION_NAME = "Test Organization"
SITE_CODE = "TEST-SITE-001"

EXIT_REFUSED = 2


def is_seeding_allowed(settings: Settings) -> bool:
    return settings.orion_environment == "development"


async def seed(settings: Settings) -> None:
    engine = build_engine(settings.database_url)
    sessionmaker = build_sessionmaker(engine)

    try:
        async with sessionmaker() as db:
            result = await db.execute(
                select(Organization).where(Organization.organization_code == ORGANIZATION_CODE)
            )
            organization = result.scalar_one_or_none()
            if organization is None:
                organization = Organization(
                    organization_code=ORGANIZATION_CODE, name=ORGANIZATION_NAME
                )
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
    finally:
        await engine.dispose()

    print(
        "Next: issue an enrollment authorization with\n"
        "  uv run python -m orion_backend.cli issue-enrollment-authorization "
        f"--organization-code {ORGANIZATION_CODE} --site-code {SITE_CODE} --device-id <device-id>"
    )


def main() -> int:
    try:
        settings = get_settings()
    except ValidationError as exc:
        fields = ", ".join(".".join(str(p) for p in e["loc"]) for e in exc.errors())
        print(f"Refusing to seed: invalid or missing settings: {fields}", file=sys.stderr)
        return EXIT_REFUSED
    if not is_seeding_allowed(settings):
        print(
            "Refusing to seed: scripts/seed_dev_data.py only runs when ORION_ENVIRONMENT="
            f"development is explicitly set (current: {settings.orion_environment or 'unset'}).",
            file=sys.stderr,
        )
        return EXIT_REFUSED
    asyncio.run(seed(settings))
    return 0


if __name__ == "__main__":
    sys.exit(main())
