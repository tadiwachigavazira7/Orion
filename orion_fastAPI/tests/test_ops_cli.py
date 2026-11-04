import importlib.util
from pathlib import Path

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import AuditEvent, Site
from orion_backend.errors import CapacityBelowOccupancyError, SubscriptionNotFoundError
from orion_backend.services.credential_hasher import Argon2CredentialHasher
from tests.helpers import (
    DEFAULT_ORGANIZATION_CODE as ORG,
    DEFAULT_SITE_CODE as SITE,
    create_organization_and_site,
    enroll,
)

_path = Path(__file__).resolve().parent.parent / "scripts" / "orion_ops.py"
_spec = importlib.util.spec_from_file_location("orion_ops", _path)
ops = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ops)

HASHER = Argon2CredentialHasher()


async def cli(db: AsyncSession, *argv: str) -> list[str]:
    return await ops.run(ops.parse(list(argv)), db, HASHER)


async def actions(db: AsyncSession) -> list[tuple[str, str]]:
    rows = await db.execute(select(AuditEvent).order_by(AuditEvent.occurred_at))
    return [(e.actor, e.action) for e in rows.scalars()]


@pytest.mark.parametrize(
    "argv",
    [
        ["subscription", "create", ORG, SITE, "--plan", "STANDARD"],
        ["subscription", "suspend", ORG, SITE],
        ["subscription", "activate", ORG, SITE, "--plan", "STANDARD"],
        ["capacity", "set", ORG, SITE, "--extra", "1"],
        ["enrollment-code", "rotate", ORG, SITE],
    ],
)
def test_write_commands_require_operator(argv, capsys):
    with pytest.raises(SystemExit) as exc:
        ops.parse(argv)
    assert exc.value.code == 2
    assert "--operator" in capsys.readouterr().err


def test_read_commands_need_no_operator_and_activate_needs_plan():
    ops.parse(["subscription", "show", ORG, SITE])
    ops.parse(["capacity", "show", ORG, SITE])
    ops.parse(["audit", "show", "--limit", "5"])
    with pytest.raises(SystemExit):
        ops.parse(["subscription", "activate", ORG, SITE, "--operator", "me"])


async def test_subscription_lifecycle_through_cli_with_audit(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session, subscription_status=None, enrollment_code=None)
    with pytest.raises(SubscriptionNotFoundError):
        await cli(db_session, "subscription", "show", ORG, SITE)
    await cli(db_session, "subscription", "create", ORG, SITE, "--operator", "alice", "--plan", "pilot")
    assert "status=PENDING" in (await cli(db_session, "subscription", "show", ORG, SITE))[0]
    await cli(db_session, "subscription", "activate", ORG, SITE, "--operator", "alice", "--plan", "STANDARD")
    out = await cli(
        db_session, "subscription", "set-plan", ORG, SITE, "--operator", "bob",
        "--period-end", "2999-01-01T00:00:00+00:00", "--price-cents", "123",
    )
    assert "status=ACTIVE" in out[0] and "price_cents=123" in out[0] and "2999" in out[0]
    await cli(db_session, "subscription", "suspend", ORG, SITE, "--operator", "bob")
    assert "status=SUSPENDED" in (await cli(db_session, "subscription", "show", ORG, SITE))[0]
    assert await actions(db_session) == [
        ("alice", "subscription_created"),
        ("alice", "subscription_status_changed"),
        ("bob", "subscription_plan_updated"),
        ("bob", "subscription_status_changed"),
    ]
    assert len(await cli(db_session, "audit", "show", "--limit", "2")) == 2


async def test_capacity_set_and_show(db_session: AsyncSession):
    await create_organization_and_site(db_session)
    out = await cli(db_session, "capacity", "set", ORG, SITE, "--operator", "carol", "--extra", "2")
    assert out == ["included=6 extra=2 max_devices=8 active=0 available=8"]
    out = await cli(db_session, "capacity", "set", ORG, SITE, "--operator", "carol", "--included", "3")
    assert out == ["included=3 extra=2 max_devices=5 active=0 available=5"]
    assert await cli(db_session, "capacity", "show", ORG, SITE) == out
    assert await actions(db_session) == [("carol", "capacity_changed")] * 2


async def test_rotate_code_enables_enrollment_and_stores_only_hash(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session, enrollment_code=None)
    assert (await enroll(client, "A")).json()["error_code"] == "invalid_enrollment_code"
    out = await cli(db_session, "enrollment-code", "rotate", ORG, SITE, "--operator", "dave")
    code = out[0].rsplit(": ", 1)[1]
    site = (await db_session.execute(select(Site))).scalar_one()
    await db_session.refresh(site)
    assert site.enrollment_code_hash.startswith("$argon2id$") and code not in site.enrollment_code_hash
    assert (await enroll(client, "A", enrollment_code=code)).status_code == 201
    # Rotation invalidates the old code, and the audit row never contains the code or hash.
    out2 = await cli(db_session, "enrollment-code", "rotate", ORG, SITE, "--operator", "dave")
    assert (await enroll(client, "B", enrollment_code=code)).status_code == 403
    assert (await enroll(client, "B", enrollment_code=out2[0].rsplit(": ", 1)[1])).status_code == 201
    events = (await db_session.execute(select(AuditEvent).where(AuditEvent.action == "enrollment_code_rotated"))).scalars().all()
    assert len(events) == 2
    assert all(code not in str(e.details) and site.enrollment_code_hash not in str(e.details) for e in events)


async def test_cli_capacity_below_active_rejected(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    for i in range(3):
        assert (await enroll(client, f"D-{i}")).status_code == 201
    with pytest.raises(CapacityBelowOccupancyError):
        await cli(db_session, "capacity", "set", ORG, SITE, "--operator", "x", "--included", "2")
