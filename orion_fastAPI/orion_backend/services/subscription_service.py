"""Operator-driven subscription changes. Every function takes the site-row
lock FIRST (capacity.lock_site) so it serializes with enrollment, then audits
old->new values in the same transaction. Subscriptions hold no capacity.
"""

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import Site, Subscription, SubscriptionPlan, SubscriptionStatus
from orion_backend.errors import (
    InvalidSubscriptionInputError,
    SubscriptionAlreadyExistsError,
    SubscriptionNotFoundError,
)
from orion_backend.services import audit, capacity
from orion_backend.services.enrollment_service import _resolve_organization, _resolve_site

DEFAULT_MONTHLY_PRICE_CENTS = 40000


def _require_aware(name: str, value: datetime | None) -> None:
    if value is not None and value.tzinfo is None:
        raise InvalidSubscriptionInputError(f"{name} must be timezone-aware.")


def _require_period_order(start: datetime | None, end: datetime | None) -> None:
    if start is not None and end is not None and end <= start:
        raise InvalidSubscriptionInputError("current_period_end must be after current_period_start.")


def _snapshot(sub: Subscription) -> dict:
    return {
        "plan_code": sub.plan_code.value,
        "status": sub.status.value,
        "monthly_price_cents": sub.monthly_price_cents,
        "current_period_start": sub.current_period_start.isoformat() if sub.current_period_start else None,
        "current_period_end": sub.current_period_end.isoformat() if sub.current_period_end else None,
    }


async def _lock(db: AsyncSession, organization_code: str, site_code: str) -> Site:
    organization = await _resolve_organization(db, organization_code)
    site = await _resolve_site(db, organization, site_code)
    return await capacity.lock_site(db, site.id)


async def _load(db: AsyncSession, site: Site) -> Subscription:
    result = await db.execute(
        select(Subscription).where(Subscription.site_id == site.id).execution_options(populate_existing=True)
    )
    sub = result.scalar_one_or_none()
    if sub is None:
        raise SubscriptionNotFoundError(site.site_code)
    return sub


async def get_subscription(db: AsyncSession, organization_code: str, site_code: str) -> Subscription:
    organization = await _resolve_organization(db, organization_code)
    site = await _resolve_site(db, organization, site_code)
    return await _load(db, site)


async def create_subscription(
    db: AsyncSession,
    organization_code: str,
    site_code: str,
    actor: str,
    plan_code: SubscriptionPlan,
    monthly_price_cents: int = DEFAULT_MONTHLY_PRICE_CENTS,
    current_period_start: datetime | None = None,
    current_period_end: datetime | None = None,
) -> Subscription:
    site = await _lock(db, organization_code, site_code)
    _require_aware("current_period_start", current_period_start)
    _require_aware("current_period_end", current_period_end)
    _require_period_order(current_period_start, current_period_end)
    if monthly_price_cents < 0:
        raise InvalidSubscriptionInputError("monthly_price_cents must be >= 0.")
    existing = await db.execute(select(Subscription.id).where(Subscription.site_id == site.id))
    if existing.scalar_one_or_none() is not None:
        raise SubscriptionAlreadyExistsError(site.site_code)
    sub = Subscription(
        site_id=site.id,
        plan_code=plan_code,
        status=SubscriptionStatus.PENDING,
        monthly_price_cents=monthly_price_cents,
        current_period_start=current_period_start,
        current_period_end=current_period_end,
    )
    db.add(sub)
    await db.flush()
    audit.record(db, actor, "subscription_created", site.id, {"new": _snapshot(sub)})
    await db.commit()
    await db.refresh(sub)
    return sub


async def set_status(
    db: AsyncSession,
    organization_code: str,
    site_code: str,
    actor: str,
    new_status: SubscriptionStatus,
    plan_code: SubscriptionPlan | None = None,
) -> Subscription:
    """Activating requires an operator-supplied plan_code (no silent default)."""
    site = await _lock(db, organization_code, site_code)
    if new_status == SubscriptionStatus.ACTIVE and plan_code is None:
        raise InvalidSubscriptionInputError("Activating a subscription requires an explicit plan.")
    sub = await _load(db, site)
    old = _snapshot(sub)
    sub.status = new_status
    if plan_code is not None:
        sub.plan_code = plan_code
    audit.record(db, actor, "subscription_status_changed", site.id, {"old": old, "new": _snapshot(sub)})
    await db.commit()
    await db.refresh(sub)
    return sub


_UNSET = object()


async def update_plan_and_period(
    db: AsyncSession,
    organization_code: str,
    site_code: str,
    actor: str,
    plan_code: SubscriptionPlan | None = None,
    current_period_start: datetime | None | object = _UNSET,
    current_period_end: datetime | None | object = _UNSET,
    monthly_price_cents: int | None = None,
) -> Subscription:
    """Fields left at their default are unchanged; pass None to clear a period bound."""
    site = await _lock(db, organization_code, site_code)
    sub = await _load(db, site)
    old = _snapshot(sub)
    if plan_code is not None:
        sub.plan_code = plan_code
    if monthly_price_cents is not None:
        if monthly_price_cents < 0:
            raise InvalidSubscriptionInputError("monthly_price_cents must be >= 0.")
        sub.monthly_price_cents = monthly_price_cents
    if current_period_start is not _UNSET:
        _require_aware("current_period_start", current_period_start)
        sub.current_period_start = current_period_start
    if current_period_end is not _UNSET:
        _require_aware("current_period_end", current_period_end)
        sub.current_period_end = current_period_end
    _require_period_order(sub.current_period_start, sub.current_period_end)
    audit.record(db, actor, "subscription_plan_updated", site.id, {"old": old, "new": _snapshot(sub)})
    await db.commit()
    await db.refresh(sub)
    return sub
