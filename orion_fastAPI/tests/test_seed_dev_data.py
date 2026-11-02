"""scripts/seed_dev_data.py must refuse to run unless ORION_ENVIRONMENT is
explicitly "development"."""

import os
import subprocess
import sys
from pathlib import Path

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession

from orion_backend.db.models import EnrollmentAuthorization, EnrolledDevice, Organization, Site
from tests.conftest import TEST_DATABASE_URL

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SEED_SCRIPT = PROJECT_ROOT / "scripts" / "seed_dev_data.py"
# Nothing listens here: if the guard ever failed, the script could not
# touch any real database.
UNREACHABLE_DATABASE_URL = "postgresql+asyncpg://unused:unused@127.0.0.1:1/unused"


def _run_seed(environment: str | None, database_url: str) -> subprocess.CompletedProcess[str]:
    env = {k: v for k, v in os.environ.items() if not k.startswith("ORION_")}
    env["DATABASE_URL"] = database_url
    env["PYTHONPATH"] = str(PROJECT_ROOT)
    if environment is not None:
        env["ORION_ENVIRONMENT"] = environment
    # cwd has no .env, so only the environment above is seen.
    return subprocess.run(
        [sys.executable, str(SEED_SCRIPT)],
        cwd=PROJECT_ROOT / "tests",
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )


@pytest.mark.parametrize("environment", [None, "production", "test"])
def test_seed_refuses_unless_development(environment: str | None):
    completed = _run_seed(environment, UNREACHABLE_DATABASE_URL)

    assert completed.returncode == 2
    assert "Refusing to seed" in completed.stderr
    assert "Created" not in completed.stdout


def test_seed_refuses_invalid_environment_value():
    completed = _run_seed("Development ", UNREACHABLE_DATABASE_URL)

    assert completed.returncode == 2
    assert "Refusing to seed" in completed.stderr
    assert "Traceback" not in completed.stderr
    assert "Created" not in completed.stdout


async def test_seed_runs_in_development_and_creates_no_devices_or_secrets(
    engine: AsyncEngine, db_session: AsyncSession
):
    completed = _run_seed("development", TEST_DATABASE_URL)

    assert completed.returncode == 0, completed.stderr
    assert "issue-enrollment-authorization" in completed.stdout
    org = (
        await db_session.execute(
            select(Organization).where(Organization.organization_code == "TEST-ORG-001")
        )
    ).scalar_one()
    assert (
        await db_session.execute(select(Site).where(Site.organization_id == org.id))
    ).scalar_one().site_code == "TEST-SITE-001"
    assert (await db_session.execute(select(EnrolledDevice))).first() is None
    assert (await db_session.execute(select(EnrollmentAuthorization))).first() is None

    # Idempotent.
    assert _run_seed("development", TEST_DATABASE_URL).returncode == 0
