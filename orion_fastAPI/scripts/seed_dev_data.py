"""Idempotently seed a development organization/site for manual testing.

DEVELOPMENT ONLY. Refuses to run (exit code 2) unless ORION_ENVIRONMENT is
explicitly set to "development". An unset value, "test", or "production" is
refused before any database connection is made.

Seeds, through the same services the operator CLI uses (so audit rows are
written):
  - Organization organization_code="TEST-ORG-001", name="Test Organization"
  - Site site_code="TEST-SITE-001" with a PILOT subscription (6 included slots)
  - A freshly generated store enrollment code, printed ONCE - only if the
    site has no code yet. No code is hardcoded; rerunning never rotates an
    existing code (use the CLI's rotate-enrollment-code for that).

Does NOT seed any EnrolledDevice row.

Usage (from orion_fastAPI/):
    ORION_ENVIRONMENT=development uv run python scripts/seed_dev_data.py
"""

import asyncio
import getpass
import sys

from pydantic import ValidationError

from orion_backend.config import Settings, get_settings
from orion_backend.db.base import build_engine, build_sessionmaker
from orion_backend.db.models import SubscriptionStatus
from orion_backend.services import admin_service, site_resolution
from orion_backend.services.audit_service import Actor
from orion_backend.services.credential_hasher import Argon2CredentialHasher

ORGANIZATION_CODE = "TEST-ORG-001"
ORGANIZATION_NAME = "Test Organization"
SITE_CODE = "TEST-SITE-001"

EXIT_REFUSED = 2


def is_seeding_allowed(settings: Settings) -> bool:
    return settings.orion_environment == "development"


def _actor() -> Actor:
    try:
        user = getpass.getuser()
    except Exception:  # no OS user known (e.g. minimal container)
        user = "unknown"
    return Actor(name=f"{user} (seed_dev_data)", source="os_user")


async def seed(settings: Settings) -> None:
    engine = build_engine(settings.database_url)
    sessionmaker = build_sessionmaker(engine)
    actor = _actor()

    try:
        async with sessionmaker() as db:
            org = await admin_service.create_organization(
                db, ORGANIZATION_CODE, ORGANIZATION_NAME, actor
            )
            print(
                f"Organization '{ORGANIZATION_CODE}' "
                + ("created" if org.created else "already exists")
            )

            organization = await site_resolution.resolve_organization(db, ORGANIZATION_CODE)
            if await site_resolution.find_site(db, organization, SITE_CODE) is None:
                await admin_service.create_site(
                    db, ORGANIZATION_CODE, SITE_CODE, actor, SubscriptionStatus.PILOT
                )
                print(f"Site '{SITE_CODE}' created with a PILOT subscription (6 included slots)")
            else:
                print(f"Site '{SITE_CODE}' already exists")

            info = await admin_service.inspect_site(db, ORGANIZATION_CODE, SITE_CODE)
            await db.rollback()
            print(
                f"Subscription: {info.subscription.status.value}, "
                f"{info.subscription.total_authorized_slots} slots, "
                f"{info.active_devices} active device(s)"
            )

            if info.has_enrollment_code:
                print(
                    "Site already has a store enrollment code (not shown again). To get a new one:\n"
                    "  uv run python -m orion_backend.cli rotate-enrollment-code "
                    f"--organization-code {ORGANIZATION_CODE} --site-code {SITE_CODE}"
                )
            else:
                rotated = await admin_service.rotate_enrollment_code(
                    db, Argon2CredentialHasher(), ORGANIZATION_CODE, SITE_CODE, actor
                )
                print("Development store enrollment code (shown once, not stored readably):")
                print(f"  enrollment_code: {rotated.code}")
    finally:
        await engine.dispose()


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
