"""Alembic migrations against a throwaway database.

The test creates a uniquely named database on the same server as
TEST_DATABASE_URL, runs `alembic upgrade head`, `alembic check` (models and
migrations agree), `alembic downgrade base`, and `upgrade head` again, then
drops the database. It never touches the configured databases (the test
schema in TEST_DATABASE_URL, or the manual-enrollment `orion` database) and
skips cleanly if the server is unreachable.
"""

import os
import subprocess
import sys
import uuid
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio
from sqlalchemy import text
from sqlalchemy.engine import URL, make_url
from sqlalchemy.ext.asyncio import create_async_engine

from tests.conftest import TEST_DATABASE_URL

PROJECT_ROOT = Path(__file__).resolve().parent.parent
PROTECTED_DATABASES = {"orion", "orion_test", "postgres", "template0", "template1"}


@pytest_asyncio.fixture
async def throwaway_database_url() -> AsyncIterator[URL]:
    name = f"orion_migration_test_{uuid.uuid4().hex[:12]}"
    assert name not in PROTECTED_DATABASES
    server_url = make_url(TEST_DATABASE_URL).set(database="postgres")
    admin = create_async_engine(server_url, isolation_level="AUTOCOMMIT")
    try:
        async with admin.connect() as conn:
            await conn.execute(text(f'CREATE DATABASE "{name}"'))
    except Exception as exc:  # noqa: BLE001 - server unreachable or no CREATE DATABASE right: skip
        await admin.dispose()
        pytest.skip(f"cannot create a throwaway database ({type(exc).__name__})")

    try:
        yield make_url(TEST_DATABASE_URL).set(database=name)
    finally:
        async with admin.connect() as conn:
            await conn.execute(text(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)'))
        await admin.dispose()


def _alembic(database_url: URL, *args: str) -> subprocess.CompletedProcess[str]:
    assert database_url.database not in PROTECTED_DATABASES
    env = {k: v for k, v in os.environ.items() if not k.startswith("ORION_")}
    env["DATABASE_URL"] = database_url.render_as_string(hide_password=False)
    env["PYTHONPATH"] = str(PROJECT_ROOT)
    # cwd has no .env, so only DATABASE_URL above is used.
    return subprocess.run(
        [sys.executable, "-m", "alembic", "-c", str(PROJECT_ROOT / "alembic.ini"), *args],
        cwd=PROJECT_ROOT / "tests",
        env=env,
        capture_output=True,
        text=True,
        timeout=120,
    )


async def _scalar(database_url: URL, sql: str):
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as conn:
            return (await conn.execute(text(sql))).scalar()
    finally:
        await engine.dispose()


async def test_migrations_upgrade_check_downgrade_roundtrip(throwaway_database_url: URL):
    url = throwaway_database_url

    upgrade = _alembic(url, "upgrade", "head")
    assert upgrade.returncode == 0, upgrade.stderr
    assert await _scalar(url, "SELECT enum_range(NULL::device_status)::text") == (
        "{ACTIVE,REVOKED,PENDING_ENROLLMENT}"
    )
    assert await _scalar(
        url,
        "SELECT is_nullable FROM information_schema.columns "
        "WHERE table_name = 'enrolled_devices' AND column_name = 'credential_hash'",
    ) == "YES"
    assert await _scalar(url, "SELECT to_regclass('enrollment_authorizations')::text") == (
        "enrollment_authorizations"
    )

    check = _alembic(url, "check")
    assert check.returncode == 0, check.stdout + check.stderr

    downgrade = _alembic(url, "downgrade", "base")
    assert downgrade.returncode == 0, downgrade.stderr
    assert await _scalar(url, "SELECT to_regclass('enrolled_devices')::text") is None

    again = _alembic(url, "upgrade", "head")
    assert again.returncode == 0, again.stderr


async def test_downgrade_refuses_when_pending_devices_exist(throwaway_database_url: URL):
    url = throwaway_database_url
    assert _alembic(url, "upgrade", "head").returncode == 0

    engine = create_async_engine(url)
    try:
        async with engine.begin() as conn:
            await conn.execute(
                text(
                    "INSERT INTO organizations (id, organization_code, name) "
                    "VALUES (gen_random_uuid(), 'O', 'Org');"
                )
            )
            await conn.execute(
                text(
                    "INSERT INTO sites (id, site_code, organization_id) "
                    "SELECT gen_random_uuid(), 'S', id FROM organizations"
                )
            )
            await conn.execute(
                text(
                    "INSERT INTO enrolled_devices (id, device_id, site_id, credential_hash, status) "
                    "SELECT gen_random_uuid(), 'PDT-P', id, NULL, 'PENDING_ENROLLMENT' FROM sites"
                )
            )
    finally:
        await engine.dispose()

    downgrade = _alembic(url, "downgrade", "-1")

    assert downgrade.returncode != 0
    assert "Cannot downgrade" in downgrade.stderr
    assert await _scalar(url, "SELECT version_num FROM alembic_version") == "7c3e9a41b2d6"
