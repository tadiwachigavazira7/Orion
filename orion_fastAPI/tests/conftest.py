import os

# Set before any orion_backend import so pydantic-settings has a value to
# validate against. Tests never connect using this URL - the real test
# database URL is TEST_DATABASE_URL below, wired in directly via
# app.state.sessionmaker (see the `client` fixture), bypassing app startup.
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://unused:unused@localhost/unused")

from collections.abc import AsyncIterator  # noqa: E402

import pytest_asyncio  # noqa: E402
from httpx import ASGITransport, AsyncClient  # noqa: E402
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker  # noqa: E402

from orion_backend.config import Settings  # noqa: E402
from orion_backend.db.base import Base, build_engine, build_sessionmaker  # noqa: E402
from orion_backend.main import create_app  # noqa: E402

TEST_DATABASE_URL = os.environ.get(
    "TEST_DATABASE_URL",
    "postgresql+asyncpg://orion:orion@localhost:55432/orion_test",
)

OFFLINE_AUTHORIZATION_HOURS = 24
RATE_LIMIT_WINDOW_MINUTES = 15
RATE_LIMIT_MAX_FAILURES_PER_CLIENT = 5
RATE_LIMIT_MAX_FAILURES_PER_SITE = 50


def make_settings(**overrides: object) -> Settings:
    """Explicit settings for tests - never read from a developer's .env."""
    values: dict[str, object] = {
        "database_url": TEST_DATABASE_URL,
        "cors_origins": "",
        "offline_authorization_hours": OFFLINE_AUTHORIZATION_HOURS,
        "enrollment_rate_limit_window_minutes": RATE_LIMIT_WINDOW_MINUTES,
        "enrollment_rate_limit_max_failures_per_client": RATE_LIMIT_MAX_FAILURES_PER_CLIENT,
        "enrollment_rate_limit_max_failures_per_site": RATE_LIMIT_MAX_FAILURES_PER_SITE,
        "orion_environment": "test",
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


@pytest_asyncio.fixture
async def engine() -> AsyncIterator[AsyncEngine]:
    """A fresh engine/schema per test, against a dedicated test database
    (never the dev/production database).

    Function-scoped deliberately: pytest-asyncio gives each test its own
    event loop by default, and asyncpg connections cannot be reused across
    event loops (a session-scoped engine here produces "Event loop is
    closed" errors on Windows). Creating/dropping the schema per test keeps
    every test on a single event loop and keeps tests isolated from
    each other without a separate truncate step.
    """
    eng = build_engine(TEST_DATABASE_URL)
    async with eng.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield eng
    async with eng.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
    await eng.dispose()


@pytest_asyncio.fixture
def sessionmaker_(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    return build_sessionmaker(engine)


@pytest_asyncio.fixture
async def db_session(
    sessionmaker_: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncSession]:
    async with sessionmaker_() as session:
        yield session


@pytest_asyncio.fixture
async def client(
    sessionmaker_: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncClient]:
    app = create_app(make_settings())
    # ASGITransport never fires FastAPI's lifespan, so app.state.sessionmaker
    # (normally set up in main.lifespan from DATABASE_URL) is wired directly
    # to this test's database sessionmaker instead.
    app.state.sessionmaker = sessionmaker_

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
