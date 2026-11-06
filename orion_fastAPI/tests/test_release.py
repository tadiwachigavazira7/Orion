"""Admin protection of device endpoints and operator-authorized release (recovery)."""

import asyncio
import importlib.util
from pathlib import Path

import pytest
from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api import deps
from orion_backend.config import Settings
from orion_backend.db.models import AuditEvent, DeviceStatus, EnrolledDevice
from orion_backend.errors import DeviceSiteMismatchError
from orion_backend.services.credential_hasher import Argon2CredentialHasher
from tests.helpers import (
    ADMIN,
    DEFAULT_ORGANIZATION_CODE as ORG,
    DEFAULT_SITE_CODE as SITE,
    create_organization_and_site,
    enroll,
)

_path = Path(__file__).resolve().parent.parent / "scripts" / "orion_ops.py"
_spec = importlib.util.spec_from_file_location("orion_ops_release", _path)
ops = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ops)

PROTECTED = [
    ("delete", "/enrolled_devices/D-1"),
    ("get", "/enrolled_devices/D-1"),
    ("post", "/enrolled_devices/D-1/release"),
]


def set_admin_key(monkeypatch, key):
    monkeypatch.setattr(deps, "get_settings", lambda: Settings(database_url="x", admin_api_key=key))


@pytest.mark.parametrize("method,url", PROTECTED)
async def test_admin_endpoints_reject_missing_and_wrong_key(client: AsyncClient, method, url):
    for headers in ({}, {"X-Admin-Key": "wrong"}):
        r = await client.request(method, url, headers=headers)
        assert r.status_code == 401 and r.json()["error_code"] == "invalid_admin_key"


@pytest.mark.parametrize("method,url", PROTECTED)
async def test_admin_endpoints_503_when_key_unconfigured(client: AsyncClient, monkeypatch, method, url):
    set_admin_key(monkeypatch, None)
    r = await client.request(method, url, headers=ADMIN)
    assert r.status_code == 503 and r.json()["error_code"] == "admin_api_not_configured"


async def test_wrong_key_does_not_revoke(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await enroll(client, "D-1")
    assert (await client.delete("/enrolled_devices/D-1", headers={"X-Admin-Key": "wrong"})).status_code == 401
    assert (await client.get("/enrolled_devices/D-1", headers=ADMIN)).json()["status"] == "ACTIVE"


async def test_enroll_and_verify_need_no_admin_key(client: AsyncClient, db_session: AsyncSession, monkeypatch):
    await create_organization_and_site(db_session)
    set_admin_key(monkeypatch, None)  # even with the admin API disabled
    r = await enroll(client, "D-1")
    assert r.status_code == 201
    v = await client.post(
        "/enrolled_devices/D-1/verify", json={"enrollment_credential": r.json()["enrollment_credential"]}
    )
    assert v.status_code == 200


async def audit_rows(db: AsyncSession, action: str) -> list[AuditEvent]:
    db.expire_all()
    return list((await db.execute(select(AuditEvent).where(AuditEvent.action == action))).scalars())


async def device_count(db: AsyncSession) -> int:
    db.expire_all()
    return (await db.execute(select(func.count()).select_from(EnrolledDevice))).scalar_one()


async def test_revoke_via_api_audits_admin_api(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await enroll(client, "D-1")
    assert (await client.delete("/enrolled_devices/D-1", headers=ADMIN)).status_code == 200
    assert [e.actor for e in await audit_rows(db_session, "device_revoked")] == ["admin-api"]


async def test_lost_response_recovery_end_to_end(client: AsyncClient, db_session: AsyncSession):
    """Server committed the enrollment, client lost the credential."""
    await create_organization_and_site(db_session)
    lost = await enroll(client, "D-1")
    assert lost.status_code == 201
    old_credential = lost.json()["enrollment_credential"]

    retry = await enroll(client, "D-1")
    assert retry.status_code == 409 and retry.json()["error_code"] == "device_already_enrolled"

    rel = await client.post("/enrolled_devices/D-1/release", headers=ADMIN)
    assert rel.status_code == 200
    assert rel.json() == {
        "device_id": "D-1", "organization_code": ORG, "site_code": SITE, "previous_status": "ACTIVE",
    }
    assert "credential" not in rel.text

    again = await enroll(client, "D-1")
    assert again.status_code == 201
    new_credential = again.json()["enrollment_credential"]
    assert new_credential != old_credential
    assert await device_count(db_session) == 1

    async def verify(c):
        return await client.post("/enrolled_devices/D-1/verify", json={"enrollment_credential": c})

    assert (await verify(new_credential)).status_code == 200
    assert (await verify(old_credential)).status_code == 401


async def test_release_revoked_device_then_reenroll(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await enroll(client, "D-1")
    await client.delete("/enrolled_devices/D-1", headers=ADMIN)
    rel = await client.post("/enrolled_devices/D-1/release", headers=ADMIN)
    assert rel.status_code == 200 and rel.json()["previous_status"] == "REVOKED"
    assert (await enroll(client, "D-1")).status_code == 201


async def test_release_unknown_device_404(client: AsyncClient):
    r = await client.post("/enrolled_devices/nope/release", headers=ADMIN)
    assert r.status_code == 404 and r.json()["error_code"] == "device_not_found"


async def test_release_frees_slot_at_full_site(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    for i in range(6):
        assert (await enroll(client, f"D-{i}")).status_code == 201
    full = await enroll(client, "D-6")
    assert full.status_code == 409 and full.json()["error_code"] == "site_capacity_exceeded"
    assert (await client.post("/enrolled_devices/D-0/release", headers=ADMIN)).status_code == 200
    assert (await enroll(client, "D-6")).status_code == 201
    assert (await enroll(client, "D-7")).status_code == 409


async def test_release_audit_has_no_credentials(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    cred = (await enroll(client, "D-1")).json()["enrollment_credential"]
    await client.post("/enrolled_devices/D-1/release", headers=ADMIN)
    (row,) = await audit_rows(db_session, "device_released")
    assert row.actor == "admin-api"
    assert row.details == {"device_id": "D-1", "previous_status": "ACTIVE"}
    assert cred not in str(row.details) and "$argon2" not in str(row.details)


# ---- CLI ----

HASHER = Argon2CredentialHasher()


async def cli(db: AsyncSession, *argv: str) -> list[str]:
    return await ops.run(ops.parse(list(argv)), db, HASHER)


@pytest.mark.parametrize("action", ["revoke", "release"])
def test_device_write_commands_require_operator(action, capsys):
    with pytest.raises(SystemExit) as exc:
        ops.parse(["device", action, ORG, SITE, "D-1"])
    assert exc.value.code == 2 and "--operator" in capsys.readouterr().err
    ops.parse(["device", "show", ORG, SITE, "D-1"])


async def test_cli_show_revoke_release(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await enroll(client, "D-1")
    assert "status=ACTIVE" in (await cli(db_session, "device", "show", ORG, SITE, "D-1"))[0]
    out = await cli(db_session, "device", "revoke", ORG, SITE, "D-1", "--operator", "alice")
    assert "status=REVOKED" in out[0]
    await cli(db_session, "device", "release", ORG, SITE, "D-1", "--operator", "alice")
    assert await device_count(db_session) == 0
    assert (await enroll(client, "D-1")).status_code == 201
    assert [e.actor for e in await audit_rows(db_session, "device_released")] == ["alice"]
    assert [e.actor for e in await audit_rows(db_session, "device_revoked")] == ["alice"]


async def test_cli_refuses_wrong_site_and_changes_nothing(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await create_organization_and_site(db_session, "ORG-2", "SITE-2")
    await enroll(client, "D-1")
    for argv in (
        ["device", "release", "ORG-2", "SITE-2", "D-1", "--operator", "me"],
        ["device", "revoke", ORG, "SITE-2", "D-1", "--operator", "me"],
        ["device", "show", "ORG-2", SITE, "D-1"],
    ):
        with pytest.raises(DeviceSiteMismatchError):
            await cli(db_session, *argv)
    db_session.expire_all()
    dev = (await db_session.execute(select(EnrolledDevice))).scalar_one()
    assert dev.status == DeviceStatus.ACTIVE
    assert await audit_rows(db_session, "device_released") == []


# ---- concurrency ----


async def test_release_vs_enroll_race_never_exceeds_capacity(
    client: AsyncClient, db_session: AsyncSession, race_decisions
):
    """5/6 full; release one while 3 new devices race for slots. Legit max is 6 active.
    Without the site row lock all three see 5 and the site ends with 7."""
    _, site = await create_organization_and_site(db_session, hash_fn=lambda c: "h:" + c)
    for i in range(5):
        db_session.add(EnrolledDevice(device_id=f"S-{i}", site_id=site.id, credential_hash="x"))
    await db_session.commit()

    results = await asyncio.gather(
        client.post("/enrolled_devices/S-0/release", headers=ADMIN),
        *(enroll(client, f"N-{i}") for i in range(3)),
    )
    assert results[0].status_code == 200
    admitted = sum(r.status_code == 201 for r in results[1:])
    assert all(r.status_code in (201, 409) for r in results[1:])
    db_session.expire_all()
    active = (
        await db_session.execute(
            select(func.count()).select_from(EnrolledDevice).where(EnrolledDevice.status == DeviceStatus.ACTIVE)
        )
    ).scalar_one()
    assert active <= 6
    assert active == 4 + admitted
