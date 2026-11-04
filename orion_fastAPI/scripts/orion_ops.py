"""Operator CLI for per-site subscriptions, capacity and enrollment codes.

Calls the same service functions as the API, against settings.database_url
(DATABASE_URL / .env). It is a dev/ops tool: tests exercise run() against the
TEST database only. All write commands require --operator NAME (audited).

Usage (from orion_fastAPI/):
    python scripts/orion_ops.py subscription create ORG SITE --operator me --plan STANDARD
    python scripts/orion_ops.py subscription activate ORG SITE --operator me --plan STANDARD
    python scripts/orion_ops.py subscription suspend ORG SITE --operator me
    python scripts/orion_ops.py subscription set-plan ORG SITE --operator me \
        --period-start 2026-11-01T00:00:00+00:00 --period-end 2026-12-01T00:00:00+00:00
    python scripts/orion_ops.py subscription show ORG SITE
    python scripts/orion_ops.py capacity show ORG SITE
    python scripts/orion_ops.py capacity set ORG SITE --operator me --extra 2 [--included 6]
    python scripts/orion_ops.py enrollment-code rotate ORG SITE --operator me   # prints code ONCE
    python scripts/orion_ops.py audit show --limit 20
"""

import argparse
import asyncio
import sys
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.config import get_settings
from orion_backend.db.base import build_engine, build_sessionmaker
from orion_backend.db.models import AuditEvent, SubscriptionPlan, SubscriptionStatus
from orion_backend.errors import OrionDeviceError
from orion_backend.services import admin_service, subscription_service
from orion_backend.services.credential_hasher import Argon2CredentialHasher, CredentialHasher

_STATUS_COMMANDS = {
    "activate": SubscriptionStatus.ACTIVE,
    "suspend": SubscriptionStatus.SUSPENDED,
    "expire": SubscriptionStatus.EXPIRED,
    "cancel": SubscriptionStatus.CANCELED,
}


def _plan(value: str) -> SubscriptionPlan:
    try:
        return SubscriptionPlan(value.upper())
    except ValueError:
        raise argparse.ArgumentTypeError(f"plan must be one of {[p.value for p in SubscriptionPlan]}")


def _dt(value: str) -> datetime:
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not an ISO-8601 datetime: {value}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="orion_ops")
    groups = parser.add_subparsers(dest="group", required=True)

    def leaf(subs, name, write, site=True):
        sp = subs.add_parser(name)
        if site:
            sp.add_argument("org_code")
            sp.add_argument("site_code")
        if write:
            sp.add_argument("--operator", required=True, metavar="NAME")
        sp.set_defaults(command=name)
        return sp

    sub = groups.add_parser("subscription").add_subparsers(dest="action", required=True)
    leaf(sub, "show", False)
    c = leaf(sub, "create", True)
    c.add_argument("--plan", type=_plan, required=True)
    c.add_argument("--price-cents", type=int, default=subscription_service.DEFAULT_MONTHLY_PRICE_CENTS)
    c.add_argument("--period-start", type=_dt)
    c.add_argument("--period-end", type=_dt)
    leaf(sub, "activate", True).add_argument("--plan", type=_plan, required=True)
    for name in ("suspend", "expire", "cancel"):
        leaf(sub, name, True)
    s = leaf(sub, "set-plan", True)
    s.add_argument("--plan", type=_plan)
    s.add_argument("--period-start", type=_dt)
    s.add_argument("--period-end", type=_dt)
    s.add_argument("--price-cents", type=int)

    cap = groups.add_parser("capacity").add_subparsers(dest="action", required=True)
    leaf(cap, "show", False)
    cs = leaf(cap, "set", True)
    cs.add_argument("--extra", type=int)
    cs.add_argument("--included", type=int)

    code = groups.add_parser("enrollment-code").add_subparsers(dest="action", required=True)
    leaf(code, "rotate", True)

    aud = groups.add_parser("audit").add_subparsers(dest="action", required=True)
    a = leaf(aud, "show", False, site=False)
    a.add_argument("--limit", type=int, default=20)
    return parser


def parse(argv: list[str]) -> argparse.Namespace:
    parser = build_parser()
    args = parser.parse_args(argv)
    if (args.group, args.command) == ("capacity", "set") and args.extra is None and args.included is None:
        parser.error("capacity set needs --extra and/or --included")
    return args


def _fmt_sub(sub) -> str:
    return (
        f"plan={sub.plan_code.value} status={sub.status.value} "
        f"price_cents={sub.monthly_price_cents} "
        f"period_start={sub.current_period_start} period_end={sub.current_period_end}"
    )


async def run(args: argparse.Namespace, db: AsyncSession, hasher: CredentialHasher) -> list[str]:
    """Execute one parsed command; returns output lines. Raises OrionDeviceError on failure."""
    org, site_code = getattr(args, "org_code", None), getattr(args, "site_code", None)
    actor = getattr(args, "operator", None)
    if args.group == "subscription":
        if args.command == "show":
            return [_fmt_sub(await subscription_service.get_subscription(db, org, site_code))]
        if args.command == "create":
            sub = await subscription_service.create_subscription(
                db, org, site_code, actor, args.plan, args.price_cents, args.period_start, args.period_end
            )
        elif args.command in _STATUS_COMMANDS:
            sub = await subscription_service.set_status(
                db, org, site_code, actor, _STATUS_COMMANDS[args.command], getattr(args, "plan", None)
            )
        else:  # set-plan; omitted flags leave the field unchanged
            fields = {}
            if args.period_start is not None:
                fields["current_period_start"] = args.period_start
            if args.period_end is not None:
                fields["current_period_end"] = args.period_end
            sub = await subscription_service.update_plan_and_period(
                db, org, site_code, actor, plan_code=args.plan, monthly_price_cents=args.price_cents, **fields
            )
        return [_fmt_sub(sub)]
    if args.group == "capacity":
        if args.command == "set":
            await admin_service.set_capacity(
                db, org, site_code, actor, extra_device_slots=args.extra, included_device_slots=args.included
            )
        site, active = await admin_service.get_capacity(db, org, site_code)
        return [
            f"included={site.included_device_slots} extra={site.extra_device_slots} "
            f"max_devices={site.max_devices} active={active} "
            f"available={max(site.max_devices - active, 0)}"
        ]
    if args.group == "enrollment-code":
        code = await admin_service.rotate_enrollment_code(db, hasher, org, site_code, actor)
        return [f"New enrollment code (shown once, store it securely): {code}"]
    rows = await db.execute(select(AuditEvent).order_by(AuditEvent.occurred_at.desc()).limit(args.limit))
    return [f"{e.occurred_at.isoformat()} {e.actor} {e.action} site={e.site_id} {e.details}" for e in rows.scalars()]


async def _main(args: argparse.Namespace) -> int:
    engine = build_engine(get_settings().database_url)
    try:
        async with build_sessionmaker(engine)() as db:
            try:
                for line in await run(args, db, Argon2CredentialHasher()):
                    print(line)
            except OrionDeviceError as exc:
                print(f"error: {exc.error_code}: {exc.message}", file=sys.stderr)
                return 1
    finally:
        await engine.dispose()
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(_main(parse(sys.argv[1:]))))
