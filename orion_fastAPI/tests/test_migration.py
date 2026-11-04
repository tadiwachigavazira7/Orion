import os
import subprocess
import sys
import uuid
from pathlib import Path

from sqlalchemy import text
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import create_async_engine

from tests.conftest import TEST_DATABASE_URL

ROOT = Path(__file__).resolve().parent.parent
SCRATCH_DB = "orion_migration_test"  # created and dropped here; never the dev database


def _alembic(url: str, *args: str) -> None:
    env = {**os.environ, "DATABASE_URL": url}
    done = subprocess.run(
        [sys.executable, "-m", "alembic", *args], cwd=ROOT, env=env, capture_output=True, text=True
    )
    assert done.returncode == 0, done.stdout + done.stderr


async def test_upgrade_preserves_data_and_authorizes_nothing():
    base = make_url(TEST_DATABASE_URL)
    admin = create_async_engine(base.set(database="postgres"), isolation_level="AUTOCOMMIT")
    async with admin.connect() as c:
        await c.execute(text(f'DROP DATABASE IF EXISTS "{SCRATCH_DB}"'))
        await c.execute(text(f'CREATE DATABASE "{SCRATCH_DB}"'))
    scratch = base.set(database=SCRATCH_DB)
    url = scratch.render_as_string(hide_password=False)
    eng = create_async_engine(scratch)
    try:
        _alembic(url, "upgrade", "a1c3e5f7b9d2")
        org, site = uuid.uuid4(), uuid.uuid4()
        async with eng.begin() as c:
            await c.execute(
                text("insert into organizations (id, organization_code, name) values (:i, 'O', 'O')"),
                {"i": org},
            )
            await c.execute(
                text(
                    "insert into sites (id, site_code, organization_id, extra_device_slots) "
                    "values (:i, 'S', :o, 2)"
                ),
                {"i": site, "o": org},
            )
            for dev, status in (("D-ACTIVE", "ACTIVE"), ("D-REVOKED", "REVOKED")):
                await c.execute(
                    text(
                        "insert into enrolled_devices (id, device_id, site_id, credential_hash, status) "
                        "values (:i, :d, :s, 'h', :st)"
                    ),
                    {"i": uuid.uuid4(), "d": dev, "s": site, "st": status},
                )

        _alembic(url, "upgrade", "head")

        async with eng.connect() as c:
            row = (
                await c.execute(
                    text(
                        "select included_device_slots, extra_device_slots, max_devices, "
                        "enrollment_code_hash from sites"
                    )
                )
            ).one()
            assert tuple(row) == (6, 2, 8, None)
            devices = (
                await c.execute(text("select device_id, status::text from enrolled_devices order by 1"))
            ).all()
            assert [tuple(d) for d in devices] == [("D-ACTIVE", "ACTIVE"), ("D-REVOKED", "REVOKED")]
            assert (await c.execute(text("select count(*) from subscriptions"))).scalar_one() == 0
            assert (await c.execute(text("select count(*) from audit_events"))).scalar_one() == 0

        _alembic(url, "downgrade", "a1c3e5f7b9d2")  # downgrade path stays usable
    finally:
        await eng.dispose()
        async with admin.connect() as c:
            await c.execute(text(f'DROP DATABASE IF EXISTS "{SCRATCH_DB}" WITH (FORCE)'))
        await admin.dispose()
