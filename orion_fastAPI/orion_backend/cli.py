"""Orion operator CLI - the only way to manage organizations, sites,
subscriptions/capacity, store enrollment codes, and device revoke/reset.
There is no admin HTTP API.

Usage (from orion_fastAPI/, DATABASE_URL from the environment or .env):

    uv run python -m orion_backend.cli create-organization --organization-code ORG --name "Name"
    uv run python -m orion_backend.cli create-site --organization-code ORG --site-code SITE [--status PILOT]
    uv run python -m orion_backend.cli rotate-enrollment-code --organization-code ORG --site-code SITE
    uv run python -m orion_backend.cli inspect-site --organization-code ORG --site-code SITE
    uv run python -m orion_backend.cli set-subscription-status --organization-code ORG --site-code SITE --status ACTIVE
    uv run python -m orion_backend.cli set-additional-slots --organization-code ORG --site-code SITE --slots 2
    uv run python -m orion_backend.cli inspect-device --device-id PDT-1
    uv run python -m orion_backend.cli revoke-device --device-id PDT-1
    uv run python -m orion_backend.cli reset-device --device-id PDT-1

Every changing command accepts --actor NAME for the audit trail; without it
the OS user (getpass.getuser()) is recorded, and the audit row notes which.

This module is a thin shell over the services: it parses arguments, calls one
service function, and prints a sanitized result. Output never includes a
device credential or any hash; the only secret ever printed is a freshly
generated store enrollment code, printed exactly once to stdout.

Exit codes: 0 success, 1 rejected by a business rule (conflict, not found,
invalid input), 2 usage error, 3 configuration/database/unexpected error.
Errors never print stack traces or exception text that could carry SQL
parameters.
"""

import argparse
import asyncio
import contextlib
import getpass
import sys
from collections.abc import Callable, Sequence
from datetime import UTC, datetime
from typing import TextIO

from pydantic import ValidationError
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from orion_backend import pricing
from orion_backend.config import Settings, get_settings
from orion_backend.db.base import build_engine, build_sessionmaker
from orion_backend.db.models import SubscriptionStatus
from orion_backend.errors import OperatorInputError, OrionDeviceError
from orion_backend.services import admin_service
from orion_backend.services.audit_service import Actor
from orion_backend.services.credential_hasher import Argon2CredentialHasher, CredentialHasher

EXIT_OK = 0
EXIT_REJECTED = 1
EXIT_USAGE = 2
EXIT_ERROR = 3

CREATE_SITE_STATUSES = [s.value for s in (SubscriptionStatus.INACTIVE, SubscriptionStatus.PILOT, SubscriptionStatus.ACTIVE)]
ALL_STATUSES = [s.value for s in SubscriptionStatus]


def _ts(value: datetime | None) -> str:
    if value is None:
        return "never"
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _print_fields(out: TextIO, fields: Sequence[tuple[str, object]]) -> None:
    for key, value in fields:
        print(f"  {key}: {value}", file=out)


def resolve_actor(flag_value: str | None, os_user: Callable[[], str] = getpass.getuser) -> Actor:
    if flag_value is not None:
        return Actor(name=flag_value, source="flag")
    try:
        name = os_user()
    except Exception as exc:  # getpass raises OSError/KeyError when no user is known
        raise OperatorInputError(
            "Could not determine the OS user for the audit trail; pass --actor NAME."
        ) from exc
    return Actor(name=name, source="os_user")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m orion_backend.cli",
        description="Orion enrollment operator commands (see OPERATIONS.md).",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    actor = argparse.ArgumentParser(add_help=False)
    actor.add_argument(
        "--actor",
        help="Name recorded in the audit trail (default: the OS user).",
    )
    site = argparse.ArgumentParser(add_help=False)
    site.add_argument("--organization-code", required=True)
    site.add_argument("--site-code", required=True)
    device = argparse.ArgumentParser(add_help=False)
    device.add_argument("--device-id", required=True)

    p = sub.add_parser(
        "create-organization", parents=[actor], help="Create an organization (no-op if identical)."
    )
    p.add_argument("--organization-code", required=True)
    p.add_argument("--name", required=True)

    p = sub.add_parser(
        "create-site",
        parents=[actor, site],
        help="Create a site and its subscription (INACTIVE unless --status is given).",
    )
    p.add_argument("--status", choices=CREATE_SITE_STATUSES, default=SubscriptionStatus.INACTIVE.value)

    sub.add_parser(
        "rotate-enrollment-code",
        parents=[actor, site],
        help="Generate a new store enrollment code (printed once); the old code stops working.",
    )
    sub.add_parser("inspect-site", parents=[site], help="Show a site's subscription and capacity.")

    p = sub.add_parser(
        "set-subscription-status",
        parents=[actor, site],
        help="Set INACTIVE | PILOT | ACTIVE | SUSPENDED (affects new enrollments only).",
    )
    p.add_argument("--status", choices=ALL_STATUSES, required=True)

    p = sub.add_parser(
        "set-additional-slots",
        parents=[actor, site],
        help="Set additional device slots (total = included + additional).",
    )
    p.add_argument("--slots", type=int, required=True)

    sub.add_parser("inspect-device", parents=[device], help="Show a sanitized view of a device.")
    sub.add_parser("revoke-device", parents=[actor, device], help="Revoke an ACTIVE device.")
    sub.add_parser(
        "reset-device",
        parents=[actor, device],
        help="Reset a device to PENDING_ENROLLMENT (clears its credential).",
    )
    return parser


def _subscription_fields(view: admin_service.SubscriptionView) -> list[tuple[str, object]]:
    return [
        ("subscription_status", view.status.value),
        ("included_device_slots", view.included_device_slots),
        ("additional_device_slots", view.additional_device_slots),
        ("total_authorized_slots", view.total_authorized_slots),
    ]


async def _dispatch(
    args: argparse.Namespace,
    db: AsyncSession,
    hasher: CredentialHasher,
    out: TextIO,
    os_user: Callable[[], str],
) -> None:
    command = args.command
    actor = resolve_actor(args.actor, os_user) if hasattr(args, "actor") else None

    if command == "create-organization":
        result = await admin_service.create_organization(db, args.organization_code, args.name, actor)
        org = result.organization
        print(
            "Organization created." if result.created else "Organization already exists (no change).",
            file=out,
        )
        _print_fields(out, [("organization_code", org.organization_code), ("name", org.name)])

    elif command == "create-site":
        created = await admin_service.create_site(
            db, args.organization_code, args.site_code, actor, SubscriptionStatus(args.status)
        )
        print("Site created.", file=out)
        _print_fields(
            out,
            [
                ("organization_code", created.organization_code),
                ("site_code", created.site_code),
                *_subscription_fields(created.subscription),
                ("enrollment_code", "none yet - run rotate-enrollment-code"),
            ],
        )

    elif command == "rotate-enrollment-code":
        rotated = await admin_service.rotate_enrollment_code(
            db, hasher, args.organization_code, args.site_code, actor
        )
        print("Store enrollment code generated.", file=out)
        _print_fields(
            out,
            [
                ("organization_code", rotated.organization_code),
                ("site_code", rotated.site_code),
                ("rotated_at", _ts(rotated.rotated_at)),
                ("enrollment_code", rotated.code),
            ],
        )
        print(
            "This code is shown once and is not stored in readable form. Any previous code for "
            "this store no longer works; already-enrolled devices are unaffected.",
            file=out,
        )

    elif command == "inspect-site":
        info = await admin_service.inspect_site(db, args.organization_code, args.site_code)
        estimate = pricing.estimate_monthly(info.subscription.additional_device_slots)
        print("Site:", file=out)
        _print_fields(
            out,
            [
                ("organization_code", info.organization_code),
                ("site_code", info.site_code),
                *_subscription_fields(info.subscription),
                ("status_changed_at", _ts(info.subscription.status_changed_at)),
                ("active_devices", info.active_devices),
                ("remaining_capacity", info.remaining_capacity),
                ("enrollment_code", "configured" if info.has_enrollment_code else "none"),
                ("enrollment_code_rotated_at", _ts(info.enrollment_code_rotated_at)),
                (
                    "monthly_price_estimate",
                    f"{estimate.total} {estimate.currency} (base {estimate.base} + "
                    f"{estimate.additional_slots} x {pricing.ADDITIONAL_SLOT_MONTHLY_PRICE} "
                    f"additional; {estimate.label})",
                ),
            ],
        )

    elif command in ("set-subscription-status", "set-additional-slots"):
        if command == "set-subscription-status":
            change = await admin_service.set_subscription_status(
                db, args.organization_code, args.site_code, SubscriptionStatus(args.status), actor
            )
        else:
            change = await admin_service.set_additional_slots(
                db, args.organization_code, args.site_code, args.slots, actor
            )
        print("Subscription updated." if change.changed else "No change.", file=out)
        _print_fields(
            out,
            [
                ("organization_code", change.organization_code),
                ("site_code", change.site_code),
                ("previous_subscription_status", change.previous.status.value),
                ("previous_total_authorized_slots", change.previous.total_authorized_slots),
                *_subscription_fields(change.current),
                ("active_devices", change.active_devices),
            ],
        )

    elif command == "inspect-device":
        info = await admin_service.inspect_device(db, args.device_id)
        print("Device:", file=out)
        _print_fields(
            out,
            [
                ("device_id", info.device_id),
                ("organization_code", info.organization_code),
                ("site_code", info.site_code),
                ("status", info.status.value),
                ("has_credential", "yes" if info.has_credential else "no"),
                ("created_at", _ts(info.created_at)),
                ("updated_at", _ts(info.updated_at)),
                ("last_verified_at", _ts(info.last_verified_at)),
            ],
        )

    elif command in ("revoke-device", "reset-device"):
        action = admin_service.revoke_device if command == "revoke-device" else admin_service.reset_device
        result = await action(db, args.device_id, actor)
        verb = "revoked" if command == "revoke-device" else "reset"
        if result.changed:
            print(f"Device {verb}.", file=out)
        else:
            print(f"Device already {result.status.value} (no status change).", file=out)
        _print_fields(
            out,
            [
                ("device_id", result.device_id),
                ("organization_code", result.organization_code),
                ("site_code", result.site_code),
                ("previous_status", result.previous_status.value),
                ("status", result.status.value),
            ],
        )
        if command == "reset-device":
            print(
                "The old credential no longer verifies. The device can enroll again with its "
                "store's enrollment code (needs a free slot).",
                file=out,
            )

    else:  # pragma: no cover - argparse restricts choices
        raise AssertionError(f"unhandled command {command}")


async def run(
    argv: Sequence[str],
    sessionmaker: async_sessionmaker[AsyncSession],
    settings: Settings,
    out: TextIO,
    err: TextIO,
    *,
    hasher: CredentialHasher | None = None,
    os_user: Callable[[], str] = getpass.getuser,
) -> int:
    """Parse argv and run one command against sessionmaker. Used by main()
    and directly by tests (with the test database's sessionmaker)."""
    parser = build_parser()
    try:
        # argparse writes help/usage errors to sys.stdout/sys.stderr; route
        # them through the injected streams.
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            args = parser.parse_args(list(argv))
    except SystemExit as exc:
        return EXIT_USAGE if exc.code else EXIT_OK

    try:
        async with sessionmaker() as db:
            await _dispatch(args, db, hasher or Argon2CredentialHasher(), out, os_user)
    except OrionDeviceError as exc:
        print(f"error [{exc.error_code}]: {exc.message}", file=err)
        return EXIT_REJECTED
    except (SQLAlchemyError, OSError) as exc:
        # Exception text from the driver may include SQL parameters (hashes),
        # so only the exception type is reported.
        print(
            f"error [database_error]: {type(exc).__name__}. Check DATABASE_URL, that "
            "PostgreSQL is reachable, and that migrations are applied (alembic upgrade head).",
            file=err,
        )
        return EXIT_ERROR
    except Exception as exc:
        # No traceback: it could include local values or SQL parameters.
        print(f"error [unexpected_error]: {type(exc).__name__}", file=err)
        return EXIT_ERROR
    return EXIT_OK


def main(argv: Sequence[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    # Parse first so --help / usage errors work without any configuration.
    try:
        build_parser().parse_args(list(argv))
    except SystemExit as exc:
        return EXIT_USAGE if exc.code else EXIT_OK

    try:
        settings = get_settings()
    except ValidationError as exc:
        fields = ", ".join(".".join(str(p) for p in e["loc"]) for e in exc.errors())
        print(f"error [configuration_error]: invalid or missing settings: {fields}", file=sys.stderr)
        return EXIT_ERROR

    async def _main() -> int:
        engine = build_engine(settings.database_url)
        try:
            return await run(argv, build_sessionmaker(engine), settings, sys.stdout, sys.stderr)
        finally:
            await engine.dispose()

    return asyncio.run(_main())


if __name__ == "__main__":
    sys.exit(main())
