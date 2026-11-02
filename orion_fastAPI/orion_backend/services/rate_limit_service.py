"""DB-backed rate limiting of failed store-code attempts.

Stored in PostgreSQL so it holds across workers/processes. Two sliding
windows over enrollment_attempt_failures:
  * per (site, client IP): max_failures_per_client
  * per site, any client:  max_failures_per_site (bounds distributed guessing)
The check runs before any Argon2id work. Failures are recorded in their own
committed transaction, so a later rollback cannot erase them. Successful
enrollments never reset counters. The submitted code is never stored.

The limit is soft under concurrency: requests that pass the check at the
same moment may each record a failure, so a burst can exceed the limit by at
most the number of concurrent in-flight requests.
"""

import math
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import EnrollmentAttemptFailure
from orion_backend.errors import EnrollmentRateLimitedError

UNKNOWN_CLIENT = "unknown"


@dataclass(frozen=True)
class RateLimitPolicy:
    window: timedelta
    max_failures_per_client: int
    max_failures_per_site: int


def client_key(client_ip: str | None) -> str:
    return (client_ip or UNKNOWN_CLIENT)[:64]


async def _retry_after(
    db: AsyncSession, conditions: list, count: int, limit: int, now: datetime, window: timedelta
) -> int | None:
    if count < limit:
        return None
    # Allowed again once enough of the oldest failures leave the window for
    # the count to drop below the limit.
    expiring = (
        await db.execute(
            select(EnrollmentAttemptFailure.attempted_at)
            .where(*conditions)
            .order_by(EnrollmentAttemptFailure.attempted_at)
            .offset(count - limit)
            .limit(1)
        )
    ).scalar_one()
    return max(1, math.ceil((expiring + window - now).total_seconds()))


async def ensure_not_limited(
    db: AsyncSession,
    site_id: uuid.UUID,
    client_ip: str | None,
    policy: RateLimitPolicy,
    now: datetime,
) -> None:
    since = now - policy.window
    in_window = [
        EnrollmentAttemptFailure.site_id == site_id,
        EnrollmentAttemptFailure.attempted_at > since,
    ]
    per_client = [*in_window, EnrollmentAttemptFailure.client_ip == client_key(client_ip)]

    client_count = (
        await db.execute(select(func.count()).select_from(EnrollmentAttemptFailure).where(*per_client))
    ).scalar_one()
    site_count = (
        await db.execute(select(func.count()).select_from(EnrollmentAttemptFailure).where(*in_window))
    ).scalar_one()

    waits = [
        wait
        for wait in (
            await _retry_after(db, per_client, client_count, policy.max_failures_per_client, now, policy.window),
            await _retry_after(db, in_window, site_count, policy.max_failures_per_site, now, policy.window),
        )
        if wait is not None
    ]
    if waits:
        raise EnrollmentRateLimitedError(max(waits))


async def record_failure(
    db: AsyncSession,
    site_id: uuid.UUID,
    client_ip: str | None,
    policy: RateLimitPolicy,
    now: datetime,
) -> None:
    """Record one failed attempt and COMMIT it on its own. The caller must not
    have uncommitted work in this session (it is rolled back first)."""
    await db.rollback()
    db.add(
        EnrollmentAttemptFailure(site_id=site_id, client_ip=client_key(client_ip), attempted_at=now)
    )
    # Opportunistic pruning of this site's rows that no window can still see.
    await db.execute(
        delete(EnrollmentAttemptFailure).where(
            EnrollmentAttemptFailure.site_id == site_id,
            EnrollmentAttemptFailure.attempted_at <= now - policy.window,
        )
    )
    await db.commit()
