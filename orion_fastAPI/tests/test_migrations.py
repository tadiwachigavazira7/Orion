"""Schema-drift guard: `alembic upgrade head` must produce exactly the
schema Base.metadata describes (tables, columns, types, nullability,
defaults, unique constraints/indexes, foreign keys, enum types).

Runs against the dedicated test database only. It wipes that database's
`public` schema before and after, so it must never be pointed at dev data.
"""
import asyncio
from pathlib import Path

from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.engine import make_url

from orion_backend.db import models  # noqa: F401 - registers tables on Base.metadata
from orion_backend.db.base import Base
from tests.conftest import TEST_DATABASE_URL

ALEMBIC_INI = Path(__file__).resolve().parent.parent / "alembic.ini"


def _assert_scratch_database() -> None:
    name = make_url(TEST_DATABASE_URL).database or ""
    assert name.endswith("_test"), f"refusing to wipe non-test database {name!r}"


async def _reset_public_schema() -> None:
    engine = create_async_engine(TEST_DATABASE_URL)
    try:
        async with engine.begin() as conn:
            await conn.execute(text("DROP SCHEMA public CASCADE"))
            await conn.execute(text("CREATE SCHEMA public"))
    finally:
        await engine.dispose()


async def _inspect_migrated_schema() -> tuple[list, list[str]]:
    engine = create_async_engine(TEST_DATABASE_URL)
    try:
        async with engine.connect() as conn:

            def diff(sync_conn):
                ctx = MigrationContext.configure(
                    sync_conn, opts={"compare_type": True, "compare_server_default": True}
                )
                return compare_metadata(ctx, Base.metadata)

            differences = await conn.run_sync(diff)
            result = await conn.execute(
                text(
                    "SELECT typname FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace "
                    "WHERE t.typtype = 'e' AND n.nspname = 'public' ORDER BY typname"
                )
            )
            return differences, [row[0] for row in result]
    finally:
        await engine.dispose()


def test_alembic_head_matches_orm_metadata():
    _assert_scratch_database()
    asyncio.run(_reset_public_schema())
    try:
        cfg = Config(str(ALEMBIC_INI))
        cfg.set_main_option("sqlalchemy.url", TEST_DATABASE_URL)
        command.upgrade(cfg, "head")

        differences, enum_types = asyncio.run(_inspect_migrated_schema())

        assert differences == [], f"migrated schema differs from ORM metadata: {differences}"
        # compare_metadata does not report stray enum types, so check them explicitly.
        assert enum_types == ["device_status"]
    finally:
        asyncio.run(_reset_public_schema())
