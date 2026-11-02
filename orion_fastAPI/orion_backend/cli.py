"""Orion operator CLI - the only way to manage organizations, sites, device
revoke/reset, and enrollment authorizations. There is no admin HTTP API.

Usage (from orion_fastAPI/, DATABASE_URL from the environment or .env):

    uv run python -m orion_backend.cli create-organization --organization-code ORG --name "Name"
    uv run python -m orion_backend.cli create-site --organization-code ORG --site-code SITE
    uv run python -m orion_backend.cli issue-enrollment-authorization \\
        --organization-code ORG --site-code SITE --device-id PDT-1
    uv run python -m orion_backend.cli inspect-device --device-id PDT-1
    uv run python -m orion_backend.cli revoke-device --device-id PDT-1
    uv run python -m orion_backend.cli reset-device --device-id PDT-1

This module is a thin shell over the services: it parses arguments, calls one
service function, and prints a sanitized result. Output never includes a
device credential or any hash; the only secret ever printed is a freshly
issued enrollment authorization, printed exactly once to stdout.

Exit codes: 0 success, 1 rejected by a business rule (conflict, not found,
invalid input), 2 usage error, 3 configuration/database/unexpected error.
Errors never print stack traces or exception text that could carry SQL
parameters.
"""

import argparse
import asyncio
import contextlib
import sys
from collections.abc import Sequence
from datetime import UTC, datetime
from typing import TextIO

from pydantic import ValidationError
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from orion_backend.config import Settings, get_settings
from orion_backend.db.base import build_engine, build_sessionmaker
from orion_backend.errors import OrionDeviceError
from orion_backend.services import admin_service, enrollment_authorization_service

EXIT_OK = 0
EXIT_REJECTED = 1
EXIT_USAGE = 2
EXIT_ERROR = 3


def _ts(value: datetime | None) -> str:
    if value is None:
        return "never"
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _print_fields(out: TextIO, fields: Sequence[tuple[str, object]]) -> None:
    for key, value in fields:
        print(f"  {key}: {value}", file=out)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m orion_backend.cli",
        description="Orion enrollment operator commands (see OPERATIONS.md).",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("create-organization", help="Create an organization (no-op if identical).")
    p.add_argument("--organization-code", required=True)
    p.add_argument("--name", required=True)

    p = sub.add_parser("create-site", help="Create a site within an existing organization.")
    p.add_argument("--organization-code", required=True)
    p.add_argument("--site-code", required=True)

    p = sub.add_parser(
        "issue-enrollment-authorization",
        help="Issue a single-use enrollment authorization for one device at one site.",
    )
    p.add_argument("--organization-code", required=True)
    p.add_argument("--site-code", required=True)
    p.add_argument("--device-id", required=True)

    for name, help_text in (
        ("inspect-device", "Show a sanitized view of a device."),
        ("revoke-device", "Revoke an ACTIVE device."),
        ("reset-device", "Reset a device to PENDING_ENROLLMENT (clears its credential)."),
    ):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("--device-id", required=True)

    return parser


async def _dispatch(
    args: argparse.Namespace, db: AsyncSession, settings: Settings, out: TextIO
) -> None:
    command = args.command

    if command == "create-organization":
        result = await admin_service.create_organization(db, args.organization_code, args.name)
        org = result.organization
        print(
            "Organization created." if result.created else "Organization already exists (no change).",
            file=out,
        )
        _print_fields(out, [("organization_code", org.organization_code), ("name", org.name)])

    elif command == "create-site":
        site = await admin_service.create_site(db, args.organization_code, args.site_code)
        print("Site created.", file=out)
        _print_fields(
            out, [("organization_code", args.organization_code), ("site_code", site.site_code)]
        )

    elif command == "issue-enrollment-authorization":
        issued = await enrollment_authorization_service.issue_authorization(
            db,
            args.organization_code,
            args.site_code,
            args.device_id,
            ttl_minutes=settings.enrollment_authorization_ttl_minutes,
        )
        print("Enrollment authorization issued.", file=out)
        _print_fields(
            out,
            [
                ("organization_code", issued.organization_code),
                ("site_code", issued.site_code),
                ("device_id", issued.device_id),
                ("expires_at", _ts(issued.expires_at)),
                ("enrollment_authorization", issued.secret),
            ],
        )
        print(
            "This authorization is shown once and is not stored. It is single-use, works only "
            "for this device at this site, and any previously issued authorization for this "
            "device is now void.",
            file=out,
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
                ("usable_enrollment_authorizations", info.usable_authorizations),
                (
                    "latest_authorization_expires_at",
                    _ts(info.latest_usable_authorization_expires_at)
                    if info.latest_usable_authorization_expires_at
                    else "none",
                ),
            ],
        )

    elif command in ("revoke-device", "reset-device"):
        action = admin_service.revoke_device if command == "revoke-device" else admin_service.reset_device
        result = await action(db, args.device_id)
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
                ("invalidated_enrollment_authorizations", result.invalidated_authorizations),
            ],
        )
        if command == "reset-device":
            print(
                "The old credential no longer verifies. No new authorization was issued; run "
                "issue-enrollment-authorization to let this device re-enroll at its site.",
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
            await _dispatch(args, db, settings, out)
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
