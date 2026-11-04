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

from orion_backend.db.base import Base, build_engine, build_sessionmaker  # noqa: E402
from orion_backend.main import create_app  # noqa: E402

TEST_DATABASE_URL = os.environ.get(
    "TEST_DATABASE_URL",
    "postgresql+asyncpg://orion:orion@localhost:55432/orion_test",
)


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
    app = create_app()
    # ASGITransport never fires FastAPI's lifespan, so app.state.sessionmaker
    # (normally set up in main.lifespan from DATABASE_URL) is wired directly
    # to this test's database sessionmaker instead.
    app.state.sessionmaker = sessionmaker_

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


class FastHasher:
    """Argon2 blocks the event loop and serializes requests, hiding races."""

    def hash(self, credential: str) -> str:
        return "h:" + credential

    def verify(self, credential: str, credential_hash: str) -> bool:
        return credential_hash == "h:" + credential


@pytest_asyncio.fixture
async def race_decisions(client: AsyncClient, sessionmaker_, monkeypatch) -> list[float]:
    """Sets up a real race (fast hasher, pre-warmed pool, widened window after the
    admission check). Returns the monotonic times at which enrollments finished
    their admission check; sites must be created with hash_fn=lambda c: "h:" + c."""
    import asyncio
    import time

    from sqlalchemy import text

    from orion_backend.api.deps import get_credential_hasher
    from orion_backend.services import capacity

    client._transport.app.dependency_overrides[get_credential_hasher] = lambda: FastHasher()

    async def hold():
        async with sessionmaker_() as s:
            await s.execute(text("select pg_sleep(0.3)"))

    await asyncio.gather(*(hold() for _ in range(12)))

    decisions: list[float] = []
    real = capacity.count_active

    async def slow_count(db, site_id):
        n = await real(db, site_id)
        decisions.append(time.monotonic())
        await asyncio.sleep(0.2)
        return n

    monkeypatch.setattr(capacity, "count_active", slow_count)
    return decisions
