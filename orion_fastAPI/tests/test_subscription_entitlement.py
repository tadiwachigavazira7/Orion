import asyncio
import logging
from datetime import UTC, datetime, timedelta

import pytest
from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import (
    AuditEvent,
    DeviceStatus,
    EnrolledDevice,
    Subscription,
    SubscriptionPlan,
    SubscriptionStatus,
)
from orion_backend.errors import (
    InvalidSubscriptionInputError,
    SubscriptionAlreadyExistsError,
    SubscriptionNotFoundError,
)
from orion_backend.services import subscription_service
from orion_backend.services.subscription import is_entitled
from tests.helpers import (
    DEFAULT_ORGANIZATION_CODE as ORG,
    DEFAULT_SITE_CODE as SITE,
    create_organization_and_site,
    enroll,
)

NOW = datetime.now(UTC)
NOT_ACTIVE = [
    SubscriptionStatus.PENDING,
    SubscriptionStatus.SUSPENDED,
    SubscriptionStatus.EXPIRED,
    SubscriptionStatus.CANCELED,
]


async def device_rows(db: AsyncSession) -> int:
    return (await db.execute(select(func.count()).select_from(EnrolledDevice))).scalar_one()


async def audit_actions(db: AsyncSession) -> list[str]:
    return list((await db.execute(select(AuditEvent.action).order_by(AuditEvent.occurred_at))).scalars())


async def set_status(db: AsyncSession, status: SubscriptionStatus, org=ORG, site=SITE):
    await subscription_service.set_status(db, org, site, "tester", status, SubscriptionPlan.STANDARD)


def test_is_entitled_policy():
    def sub(status, end=None):
        return Subscription(status=status, current_period_end=end)

    assert not is_entitled(None, NOW)
    assert is_entitled(sub(SubscriptionStatus.ACTIVE), NOW)  # NULL end allowed
    assert is_entitled(sub(SubscriptionStatus.ACTIVE, NOW + timedelta(days=1)), NOW)
    assert not is_entitled(sub(SubscriptionStatus.ACTIVE, NOW - timedelta(seconds=1)), NOW)
    assert not is_entitled(sub(SubscriptionStatus.ACTIVE, NOW), NOW)  # end must be strictly after now
    for s in NOT_ACTIVE:
        assert not is_entitled(sub(s), NOW)
    with pytest.raises(ValueError):
        is_entitled(None, datetime.now())  # naive now rejected


async def test_active_enrolls_and_audits(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    assert (await enroll(client, "A")).status_code == 201
    assert await audit_actions(db_session) == ["device_enrolled"]


async def test_missing_subscription_rejected(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session, subscription_status=None)
    r = await enroll(client, "A")
    assert r.status_code == 403 and r.json()["error_code"] == "subscription_not_active"
    assert await device_rows(db_session) == 0 and await audit_actions(db_session) == []


@pytest.mark.parametrize("status", NOT_ACTIVE)
async def test_non_active_statuses_rejected_with_one_generic_error(
    client: AsyncClient, db_session: AsyncSession, status
):
    await create_organization_and_site(db_session, subscription_status=status)
    r = await enroll(client, "A")
    assert r.status_code == 403
    assert r.json() == {
        "detail": "This site is not currently authorized to use Orion.",
        "error_code": "subscription_not_active",
    }
    assert await device_rows(db_session) == 0 and await audit_actions(db_session) == []


async def test_expired_period_blocks_enroll_and_verify_null_end_allowed(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)  # ACTIVE, NULL end
    cred = (await enroll(client, "A")).json()["enrollment_credential"]
    ok = await client.post("/enrolled_devices/A/verify", json={"enrollment_credential": cred})
    assert ok.status_code == 200
    await subscription_service.update_plan_and_period(
        db_session, ORG, SITE, "tester", current_period_end=NOW - timedelta(minutes=1)
    )
    r = await enroll(client, "B")
    assert r.status_code == 403 and r.json()["error_code"] == "subscription_not_active"
    v = await client.post("/enrolled_devices/A/verify", json={"enrollment_credential": cred})
    assert v.status_code == 403 and v.json()["error_code"] == "subscription_not_active"


async def test_verify_blocked_while_inactive_then_reactivation_restores(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    cred = (await enroll(client, "A")).json()["enrollment_credential"]
    body = {"enrollment_credential": cred}
    for status in (SubscriptionStatus.SUSPENDED, SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELED):
        await set_status(db_session, status)
        r = await client.post("/enrolled_devices/A/verify", json=body)
        assert r.status_code == 403 and r.json()["error_code"] == "subscription_not_active"
        got = (await client.get("/enrolled_devices/A")).json()
        assert got["status"] == "ACTIVE" and got["last_verified_at"] is None  # not updated, not revoked
    await set_status(db_session, SubscriptionStatus.ACTIVE)
    r = await client.post("/enrolled_devices/A/verify", json=body)  # same credential, no re-enroll
    assert r.status_code == 200
    assert (await client.get("/enrolled_devices/A")).json()["last_verified_at"] is not None


async def test_verify_checks_credential_before_revealing_subscription(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    await enroll(client, "A")
    await set_status(db_session, SubscriptionStatus.SUSPENDED)
    r = await client.post("/enrolled_devices/A/verify", json={"enrollment_credential": "wrong"})
    assert r.status_code == 401 and r.json()["error_code"] == "invalid_credential"


async def test_revoked_device_reports_revoked_even_when_suspended_and_revoke_is_audited(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    cred = (await enroll(client, "A")).json()["enrollment_credential"]
    assert (await client.delete("/enrolled_devices/A")).status_code == 200
    await set_status(db_session, SubscriptionStatus.SUSPENDED)
    r = await client.post("/enrolled_devices/A/verify", json={"enrollment_credential": cred})
    assert r.status_code == 403 and r.json()["error_code"] == "device_revoked"
    assert "device_revoked" in await audit_actions(db_session)


async def test_wrong_or_unprovisioned_code_rejected_before_subscription_info(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session, subscription_status=None)  # also not entitled
    r = await enroll(client, "A", enrollment_code="nope")
    assert r.status_code == 403 and r.json()["error_code"] == "invalid_enrollment_code"
    await create_organization_and_site(db_session, "ORG-2", "SITE-2", enrollment_code=None)
    r = await enroll(client, "A", "ORG-2", "SITE-2")  # NULL hash => fail closed, same error
    assert r.status_code == 403 and r.json()["error_code"] == "invalid_enrollment_code"
    assert await device_rows(db_session) == 0 and await audit_actions(db_session) == []


async def test_enrollment_code_is_required_in_body(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    body = {"organization_code": ORG, "site_code": SITE, "device_id": "A"}
    assert (await client.post("/enrollment", json=body)).status_code == 422
    assert (await client.post("/enrollment", json={**body, "enrollment_code": ""})).status_code == 422
    assert (await client.post("/enrollment", json={**body, "enrollment_code": "x" * 129})).status_code == 422


async def test_rejection_logged_without_code(client: AsyncClient, db_session: AsyncSession, caplog):
    _, site = await create_organization_and_site(
        db_session, subscription_status=SubscriptionStatus.SUSPENDED
    )
    with caplog.at_level(logging.INFO, logger="orion_backend.audit"):
        await enroll(client, "A")
        await enroll(client, "B", enrollment_code="secret-wrong-code")
    assert str(site.id) in caplog.text
    assert "subscription_not_active" in caplog.text and "invalid_enrollment_code" in caplog.text
    assert "secret-wrong-code" not in caplog.text


async def test_org_and_site_isolation(client: AsyncClient, db_session: AsyncSession):
    # Same site_code under two orgs; each has its own code and subscription.
    await create_organization_and_site(db_session, "ORG-A", "SHARED", enrollment_code="code-a")
    await create_organization_and_site(
        db_session, "ORG-B", "SHARED", enrollment_code="code-b",
        subscription_status=SubscriptionStatus.SUSPENDED,
    )
    wrong = await enroll(client, "A1", "ORG-A", "SHARED", "code-b")
    assert wrong.json()["error_code"] == "invalid_enrollment_code"
    assert (await enroll(client, "A1", "ORG-A", "SHARED", "code-a")).status_code == 201
    r = await enroll(client, "B1", "ORG-B", "SHARED", "code-b")  # B's own code, but B is suspended
    assert r.status_code == 403 and r.json()["error_code"] == "subscription_not_active"
    r = await enroll(client, "B1", "ORG-B", "SHARED", "code-a")
    assert r.json()["error_code"] == "invalid_enrollment_code"
    # Devices are counted per site: fill A to its max, B (once active) is unaffected.
    for i in range(5):
        assert (await enroll(client, f"A-{i}", "ORG-A", "SHARED", "code-a")).status_code == 201
    assert (await enroll(client, "A-x", "ORG-A", "SHARED", "code-a")).status_code == 409
    await subscription_service.set_status(
        db_session, "ORG-B", "SHARED", "tester", SubscriptionStatus.ACTIVE, SubscriptionPlan.PILOT
    )
    assert (await enroll(client, "B1", "ORG-B", "SHARED", "code-b")).status_code == 201


async def test_subscription_service_errors_and_audit(db_session: AsyncSession):
    await create_organization_and_site(db_session, subscription_status=None)
    with pytest.raises(SubscriptionNotFoundError):
        await subscription_service.get_subscription(db_session, ORG, SITE)
    with pytest.raises(InvalidSubscriptionInputError):  # naive datetime from an operator
        await subscription_service.create_subscription(
            db_session, ORG, SITE, "op", SubscriptionPlan.STANDARD, current_period_end=datetime(2030, 1, 1)
        )
    await db_session.rollback()
    sub = await subscription_service.create_subscription(
        db_session, ORG, SITE, "op", SubscriptionPlan.STANDARD
    )
    assert sub.status == SubscriptionStatus.PENDING and sub.monthly_price_cents == 40000
    with pytest.raises(SubscriptionAlreadyExistsError):
        await subscription_service.create_subscription(db_session, ORG, SITE, "op", SubscriptionPlan.PILOT)
    await db_session.rollback()
    with pytest.raises(InvalidSubscriptionInputError):  # activate needs an explicit plan
        await subscription_service.set_status(db_session, ORG, SITE, "op", SubscriptionStatus.ACTIVE)
    await db_session.rollback()
    with pytest.raises(InvalidSubscriptionInputError):  # end before start
        await subscription_service.update_plan_and_period(
            db_session, ORG, SITE, "op",
            current_period_start=NOW, current_period_end=NOW - timedelta(days=1),
        )
    await db_session.rollback()
    await subscription_service.set_status(
        db_session, ORG, SITE, "op", SubscriptionStatus.ACTIVE, SubscriptionPlan.PILOT
    )
    rows = (await db_session.execute(select(AuditEvent).order_by(AuditEvent.occurred_at))).scalars().all()
    assert [r.action for r in rows] == ["subscription_created", "subscription_status_changed"]
    assert rows[1].actor == "op"
    assert rows[1].details["old"]["status"] == "PENDING" and rows[1].details["new"]["status"] == "ACTIVE"


async def test_suspend_vs_enroll_race(client: AsyncClient, db_session: AsyncSession, sessionmaker_):
    _, site = await create_organization_and_site(db_session)

    async def suspend():
        async with sessionmaker_() as s:
            await subscription_service.set_status(
                s, ORG, SITE, "tester", SubscriptionStatus.SUSPENDED, SubscriptionPlan.STANDARD
            )

    results = await asyncio.gather(suspend(), *(enroll(client, f"R-{i}") for i in range(8)))
    for r in results[1:]:
        assert r.status_code in (201, 403)
    # Once the suspend has committed (gather finished) no later enrollment may succeed.
    late = await enroll(client, "LATE")
    assert late.status_code == 403 and late.json()["error_code"] == "subscription_not_active"
    active = (
        await db_session.execute(
            select(func.count()).select_from(EnrolledDevice).where(EnrolledDevice.status == DeviceStatus.ACTIVE)
        )
    ).scalar_one()
    await db_session.refresh(site)
    assert active <= site.max_devices
