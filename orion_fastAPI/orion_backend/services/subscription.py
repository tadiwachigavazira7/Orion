"""Entitlement policy: may this site use Orion right now?

Shortcuts: no scheduler and no automatic status change when time passes (an
ACTIVE subscription past current_period_end is simply not entitled); a future
current_period_start does not block - add a start check if prepaid starts matter.
"""

import uuid
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import Subscription, SubscriptionStatus
from orion_backend.errors import SubscriptionNotActiveError
from orion_backend.services.audit import logger


def _reason(subscription: Subscription | None, now: datetime) -> str | None:
    """None when entitled, else the precise (server-side only) reason."""
    if now.tzinfo is None:
        raise ValueError("now must be timezone-aware")
    if subscription is None:
        return "no_subscription"
    if subscription.status != SubscriptionStatus.ACTIVE:
        return f"status_{subscription.status.value.lower()}"
    end = subscription.current_period_end
    if end is not None and not end > now:
        return "period_ended"
    return None


def is_entitled(subscription: Subscription | None, now: datetime) -> bool:
    return _reason(subscription, now) is None


async def require_entitled(db: AsyncSession, site_id: uuid.UUID) -> None:
    result = await db.execute(
        select(Subscription)
        .where(Subscription.site_id == site_id)
        .execution_options(populate_existing=True)
    )
    reason = _reason(result.scalar_one_or_none(), datetime.now(UTC))
    if reason is not None:
        logger.info("not entitled site_id=%s reason=%s", site_id, reason)
        raise SubscriptionNotActiveError()
