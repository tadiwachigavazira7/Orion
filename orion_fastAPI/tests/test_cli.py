"""Operator CLI: conflict rules, sanitized output, exit codes."""

import os
import re
import subprocess
import sys
from io import StringIO
from pathlib import Path

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from orion_backend import cli
from orion_backend.db.base import build_engine, build_sessionmaker
from orion_backend.db.models import DeviceStatus
from orion_backend.services import admin_service
from tests.conftest import TEST_DATABASE_URL, make_settings
from tests.helpers import enroll, fetch_authorization, fetch_device, verify

ORG = "CLI-ORG"
SITE = "CLI-SITE"
PROJECT_ROOT = Path(__file__).resolve().parent.parent


class Result:
    def __init__(self, code: int, out: str, err: str):
        self.code, self.out, self.err = code, out, err


@pytest.fixture
def run_cli(sessionmaker_: async_sessionmaker[AsyncSession]):
    async def _run(*argv: str, **settings_overrides: object) -> Result:
        out, err = StringIO(), StringIO()
        code = await cli.run(argv, sessionmaker_, make_settings(**settings_overrides), out, err)
        return Result(code, out.getvalue(), err.getvalue())

    return _run


def _secret_from(output: str) -> str:
    match = re.search(r"^  enrollment_authorization: (\S+)$", output, re.MULTILINE)
    assert match, output
    return match.group(1)


async def _org_and_site(run_cli, org: str = ORG, site: str = SITE) -> None:
    assert (await run_cli("create-organization", "--organization-code", org, "--name", "Org")).code == 0
    assert (await run_cli("create-site", "--organization-code", org, "--site-code", site)).code == 0


async def _issue(run_cli, device_id: str, org: str = ORG, site: str = SITE) -> Result:
    return await run_cli(
        "issue-enrollment-authorization",
        "--organization-code", org,
        "--site-code", site,
        "--device-id", device_id,
    )


async def _enroll_via_cli_authorization(run_cli, client: AsyncClient, device_id: str) -> str:
    issued = await _issue(run_cli, device_id)
    assert issued.code == 0, issued.err
    response = await enroll(client, device_id, _secret_from(issued.out), ORG, SITE)
    assert response.status_code == 201
    return response.json()["enrollment_credential"]


# --- organizations / sites -----------------------------------------------


async def test_create_organization_is_idempotent_for_identical_input(run_cli):
    first = await run_cli("create-organization", "--organization-code", ORG, "--name", "Org")
    second = await run_cli("create-organization", "--organization-code", ORG, "--name", "Org")

    assert first.code == 0 and "Organization created." in first.out
    assert second.code == 0 and "already exists (no change)" in second.out


async def test_create_organization_with_different_name_is_rejected(run_cli):
    await run_cli("create-organization", "--organization-code", ORG, "--name", "Org")

    result = await run_cli("create-organization", "--organization-code", ORG, "--name", "Renamed")

    assert result.code == cli.EXIT_REJECTED
    assert "organization_conflict" in result.err


async def test_create_site_conflicts_and_unknown_organization(run_cli):
    await _org_and_site(run_cli)

    duplicate = await run_cli("create-site", "--organization-code", ORG, "--site-code", SITE)
    unknown_org = await run_cli("create-site", "--organization-code", "NOPE", "--site-code", SITE)

    assert duplicate.code == cli.EXIT_REJECTED and "site_already_exists" in duplicate.err
    assert unknown_org.code == cli.EXIT_REJECTED and "organization_not_found" in unknown_org.err


async def test_invalid_identifiers_are_rejected(run_cli):
    padded = await run_cli("create-organization", "--organization-code", " ORG ", "--name", "Org")
    empty = await run_cli("create-organization", "--organization-code", "", "--name", "Org")
    too_long = await run_cli("create-organization", "--organization-code", "X" * 129, "--name", "Org")

    for result in (padded, empty, too_long):
        assert result.code == cli.EXIT_REJECTED
        assert "invalid_input" in result.err


async def test_usage_error_exits_with_usage_code(run_cli):
    result = await run_cli("issue-enrollment-authorization", "--device-id", "PDT-1")

    assert result.code == cli.EXIT_USAGE
    assert "usage:" in result.err
    assert "--organization-code" in result.err


# --- issuing authorizations ------------------------------------------------


async def test_issue_prints_secret_once_with_expiry_and_it_enrolls(
    run_cli, client: AsyncClient, db_session: AsyncSession
):
    await _org_and_site(run_cli)

    result = await _issue(run_cli, "PDT-60")

    assert result.code == 0
    assert result.err == ""
    secret = _secret_from(result.out)
    assert result.out.count(secret) == 1
    assert re.search(r"^  expires_at: \d{4}-\d\d-\d\dT.*Z$", result.out, re.MULTILINE)
    stored = await fetch_authorization(db_session, secret)
    assert secret not in stored.secret_hash

    assert (await enroll(client, "PDT-60", secret, ORG, SITE)).status_code == 201


async def test_issue_uses_configured_ttl(run_cli, db_session: AsyncSession):
    await _org_and_site(run_cli)

    result = await run_cli(
        "issue-enrollment-authorization",
        "--organization-code", ORG, "--site-code", SITE, "--device-id", "PDT-61",
        enrollment_authorization_ttl_minutes=5,
    )

    authorization = await fetch_authorization(db_session, _secret_from(result.out))
    lifetime = authorization.expires_at - authorization.created_at
    assert 4 * 60 < lifetime.total_seconds() <= 5 * 60 + 5


@pytest.mark.parametrize("make_revoked", [False, True])
async def test_issue_for_active_or_revoked_device_requires_reset(
    make_revoked: bool, run_cli, client: AsyncClient, db_session: AsyncSession
):
    await _org_and_site(run_cli)
    await _enroll_via_cli_authorization(run_cli, client, "PDT-62")
    if make_revoked:
        assert (await run_cli("revoke-device", "--device-id", "PDT-62")).code == 0

    result = await _issue(run_cli, "PDT-62")

    assert result.code == cli.EXIT_REJECTED
    assert "device_reset_required" in result.err
    assert "enrollment_authorization:" not in result.out


async def test_issue_for_pending_device_at_other_site_or_org_is_rejected(
    run_cli, client: AsyncClient, db_session: AsyncSession
):
    await _org_and_site(run_cli)
    await run_cli("create-site", "--organization-code", ORG, "--site-code", "OTHER-SITE")
    await _org_and_site(run_cli, org="OTHER-ORG", site=SITE)
    await _enroll_via_cli_authorization(run_cli, client, "PDT-63")
    await run_cli("reset-device", "--device-id", "PDT-63")

    other_site = await _issue(run_cli, "PDT-63", ORG, "OTHER-SITE")
    other_org = await _issue(run_cli, "PDT-63", "OTHER-ORG", SITE)
    same_site = await _issue(run_cli, "PDT-63", ORG, SITE)

    assert other_site.code == cli.EXIT_REJECTED and "device_site_conflict" in other_site.err
    assert other_org.code == cli.EXIT_REJECTED and "device_site_conflict" in other_org.err
    assert same_site.code == 0
    # Never moved.
    device = await fetch_device(db_session, "PDT-63")
    assert device.status == DeviceStatus.PENDING_ENROLLMENT


async def test_issue_for_unknown_org_or_site_is_rejected(run_cli):
    await _org_and_site(run_cli)

    assert "organization_not_found" in (await _issue(run_cli, "PDT-64", "NOPE", SITE)).err
    assert "site_not_found" in (await _issue(run_cli, "PDT-64", ORG, "NOPE")).err


# --- inspect / revoke / reset ------------------------------------------------


async def test_inspect_device_is_sanitized(run_cli, client: AsyncClient, db_session: AsyncSession):
    await _org_and_site(run_cli)
    credential = await _enroll_via_cli_authorization(run_cli, client, "PDT-65")
    stored_hash = (await fetch_device(db_session, "PDT-65")).credential_hash

    result = await run_cli("inspect-device", "--device-id", "PDT-65")

    assert result.code == 0
    assert "status: ACTIVE" in result.out
    assert "has_credential: yes" in result.out
    assert f"organization_code: {ORG}" in result.out
    assert credential not in result.out
    assert stored_hash not in result.out
    assert "argon2" not in result.out.lower()
    assert "hash" not in result.out.lower()


async def test_inspect_unknown_device_is_rejected(run_cli):
    result = await run_cli("inspect-device", "--device-id", "PDT-nope")

    assert result.code == cli.EXIT_REJECTED
    assert "device_not_found" in result.err


async def test_revoke_rules(run_cli, client: AsyncClient):
    await _org_and_site(run_cli)
    await _enroll_via_cli_authorization(run_cli, client, "PDT-66")

    first = await run_cli("revoke-device", "--device-id", "PDT-66")
    again = await run_cli("revoke-device", "--device-id", "PDT-66")
    missing = await run_cli("revoke-device", "--device-id", "PDT-nope")
    await run_cli("reset-device", "--device-id", "PDT-66")
    pending = await run_cli("revoke-device", "--device-id", "PDT-66")

    assert first.code == 0 and "Device revoked." in first.out and "status: REVOKED" in first.out
    assert again.code == 0 and "no status change" in again.out
    assert missing.code == cli.EXIT_REJECTED and "device_not_found" in missing.err
    assert pending.code == cli.EXIT_REJECTED and "invalid_device_transition" in pending.err


async def test_reset_then_new_authorization_reenrolls(
    run_cli, client: AsyncClient, db_session: AsyncSession
):
    await _org_and_site(run_cli)
    old_credential = await _enroll_via_cli_authorization(run_cli, client, "PDT-67")

    reset = await run_cli("reset-device", "--device-id", "PDT-67")

    assert reset.code == 0
    assert "status: PENDING_ENROLLMENT" in reset.out
    assert "enrollment_authorization:" not in reset.out  # reset never issues one
    assert (await verify(client, "PDT-67", old_credential)).status_code == 401

    new_credential = await _enroll_via_cli_authorization(run_cli, client, "PDT-67")
    assert (await verify(client, "PDT-67", new_credential)).status_code == 200


async def test_reset_unknown_device_is_rejected(run_cli):
    result = await run_cli("reset-device", "--device-id", "PDT-nope")

    assert result.code == cli.EXIT_REJECTED
    assert "device_not_found" in result.err


# --- failure reporting -------------------------------------------------------


async def test_database_failure_is_reported_without_traceback():
    engine = build_engine("postgresql+asyncpg://orion:not-the-password@127.0.0.1:1/nowhere")
    out, err = StringIO(), StringIO()
    try:
        code = await cli.run(
            ["inspect-device", "--device-id", "PDT-1"],
            build_sessionmaker(engine),
            make_settings(),
            out,
            err,
        )
    finally:
        await engine.dispose()

    assert code == cli.EXIT_ERROR
    assert "database_error" in err.getvalue()
    assert "Traceback" not in err.getvalue()
    assert "not-the-password" not in err.getvalue() + out.getvalue()


async def test_module_entrypoint_issues_authorization(
    engine, client: AsyncClient, db_session: AsyncSession
):
    """Runs the real `python -m orion_backend.cli` against the test database
    (from a directory without a .env, so nothing else is picked up)."""
    await admin_service.create_organization(db_session, ORG, "Org")
    await admin_service.create_site(db_session, ORG, SITE)

    env = {k: v for k, v in os.environ.items() if not k.startswith("ORION_")}
    env["DATABASE_URL"] = TEST_DATABASE_URL
    env["PYTHONPATH"] = str(PROJECT_ROOT)
    completed = subprocess.run(
        [
            sys.executable, "-m", "orion_backend.cli", "issue-enrollment-authorization",
            "--organization-code", ORG, "--site-code", SITE, "--device-id", "PDT-68",
        ],
        cwd=PROJECT_ROOT / "tests",
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )

    assert completed.returncode == 0, completed.stderr
    secret = _secret_from(completed.stdout)
    assert (await enroll(client, "PDT-68", secret, ORG, SITE)).status_code == 201
