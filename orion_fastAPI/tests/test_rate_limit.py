"""DB-backed rate limiting of failed store-code attempts."""

from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from orion_backend.api.deps import get_credential_hasher
from orion_backend.db.models import EnrollmentAttemptFailure
from orion_backend.services.credential_hasher import Argon2CredentialHasher
from tests.conftest import RATE_LIMIT_MAX_FAILURES_PER_CLIENT, RATE_LIMIT_WINDOW_MINUTES
from tests.helpers import create_store, enroll_at, make_client

WRONG = "AAAAA-AAAAA-AAAAA-AAAAA"


class CountingHasher(Argon2CredentialHasher):
    def __init__(self) -> None:
        super().__init__()
        self.verify_calls = 0

    def verify(self, credential: str, credential_hash: str) -> bool:
        self.verify_calls += 1
        return super().verify(credential, credential_hash)


async def _failures(db_session: AsyncSession) -> list[EnrollmentAttemptFailure]:
    db_session.expire_all()
    return list((await db_session.execute(select(EnrollmentAttemptFailure))).scalars().all())


async def test_per_client_limit_returns_429_with_retry_after_and_skips_argon2(
    db_session: AsyncSession, sessionmaker_: async_sessionmaker[AsyncSession]
):
    store = await create_store(db_session)
    hasher = CountingHasher()
    async with make_client(sessionmaker_, "10.0.0.1") as c:
        c._transport.app.dependency_overrides[get_credential_hasher] = lambda: hasher
        for i in range(RATE_LIMIT_MAX_FAILURES_PER_CLIENT):
            assert (await enroll_at(c, store, f"PDT-{i}", code=WRONG)).status_code == 403
        calls_before = hasher.verify_calls

        limited = await enroll_at(c, store, "PDT-x", code=WRONG)
        # Even the right code is refused while limited, without Argon2 work.
        limited_right = await enroll_at(c, store, "PDT-y")

    for response in (limited, limited_right):
        assert response.status_code == 429
        assert response.json()["error_code"] == "enrollment_rate_limited"
        retry_after = int(response.headers["Retry-After"])
        assert 1 <= retry_after <= RATE_LIMIT_WINDOW_MINUTES * 60
    assert hasher.verify_calls == calls_before
    # 429s are not recorded as further failures.
    assert len(await _failures(db_session)) == RATE_LIMIT_MAX_FAILURES_PER_CLIENT


async def test_limit_is_per_client_and_per_site(
    db_session: AsyncSession, sessionmaker_: async_sessionmaker[AsyncSession]
):
    store = await create_store(db_session, site_code="SITE-A")
    other_store = await create_store(db_session, site_code="SITE-B")
    async with make_client(sessionmaker_, "10.0.0.2") as attacker:
        for i in range(RATE_LIMIT_MAX_FAILURES_PER_CLIENT):
            await enroll_at(attacker, store, f"PDT-{i}", code=WRONG)
        assert (await enroll_at(attacker, store, "PDT-z", code=WRONG)).status_code == 429
        # Same client, other site: not limited.
        assert (await enroll_at(attacker, other_store, "PDT-b1")).status_code == 201
    async with make_client(sessionmaker_, "10.0.0.3") as bystander:
        # Other client, same site: not limited, and its success ...
        assert (await enroll_at(bystander, store, "PDT-ok")).status_code == 201
    async with make_client(sessionmaker_, "10.0.0.2") as attacker:
        # ... does not reset the attacker's counter.
        assert (await enroll_at(attacker, store, "PDT-z2", code=WRONG)).status_code == 429


async def test_per_site_ceiling_bounds_distributed_guessing(
    db_session: AsyncSession, sessionmaker_: async_sessionmaker[AsyncSession]
):
    store = await create_store(db_session)
    settings = {"enrollment_rate_limit_max_failures_per_site": 3}
    for i in range(3):
        async with make_client(sessionmaker_, f"10.1.0.{i}", **settings) as c:
            assert (await enroll_at(c, store, f"PDT-{i}", code=WRONG)).status_code == 403

    async with make_client(sessionmaker_, "10.1.0.99", **settings) as fresh_client:
        response = await enroll_at(fresh_client, store, "PDT-fresh")

    assert response.status_code == 429
    assert "Retry-After" in response.headers


async def test_failed_attempt_is_committed_and_never_stores_the_code(
    db_session: AsyncSession, sessionmaker_: async_sessionmaker[AsyncSession]
):
    store = await create_store(db_session)
    marker = "MARKR-SECRT-GUESS-12345"

    async with make_client(sessionmaker_, "10.2.0.1") as c:
        response = await enroll_at(c, store, "PDT-f", code=marker)

    # The request failed (its transaction rolled back), yet the attempt
    # persists - it was committed in its own transaction.
    assert response.status_code == 403
    failures = await _failures(db_session)
    assert [(f.site_id, f.client_ip) for f in failures] == [(store.site_id, "10.2.0.1")]
    for failure in failures:
        assert marker not in repr(vars(failure))


async def test_window_expiry_lifts_the_limit_and_old_rows_are_pruned(
    db_session: AsyncSession, sessionmaker_: async_sessionmaker[AsyncSession]
):
    store = await create_store(db_session)
    async with make_client(sessionmaker_, "10.3.0.1") as c:
        for i in range(RATE_LIMIT_MAX_FAILURES_PER_CLIENT):
            await enroll_at(c, store, f"PDT-{i}", code=WRONG)
        assert (await enroll_at(c, store, "PDT-q", code=WRONG)).status_code == 429

        # Age every failure past the window.
        await db_session.execute(
            update(EnrollmentAttemptFailure).values(
                attempted_at=datetime.now(UTC) - timedelta(minutes=RATE_LIMIT_WINDOW_MINUTES + 1)
            )
        )
        await db_session.commit()

        assert (await enroll_at(c, store, "PDT-q", code=WRONG)).status_code == 403

    db_session.expire_all()
    remaining = (
        await db_session.execute(select(func.count()).select_from(EnrollmentAttemptFailure))
    ).scalar_one()
    assert remaining == 1  # the aged rows were pruned when the new one was recorded
