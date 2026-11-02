import uuid
from dataclasses import dataclass
from datetime import datetime

from httpx import ASGITransport, AsyncClient, Response
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from orion_backend.db.models import (
    AdminAuditEvent,
    EnrolledDevice,
    Organization,
    Site,
    SiteSubscription,
    SubscriptionStatus,
)
from orion_backend.main import create_app
from orion_backend.services import admin_service
from orion_backend.services.audit_service import Actor
from orion_backend.services.credential_hasher import Argon2CredentialHasher

DEFAULT_ORGANIZATION_CODE = "TEST-ORG-001"
DEFAULT_SITE_CODE = "TEST-SITE-001"
TEST_ACTOR = Actor(name="pytest-operator", source="flag")


@dataclass(frozen=True)
class Store:
    """Plain values (no ORM objects, which expire between requests)."""

    organization_code: str
    site_code: str
    organization_id: uuid.UUID
    site_id: uuid.UUID
    # Store enrollment code in display form; None if never generated.
    code: str | None


async def create_store(
    db_session: AsyncSession,
    organization_code: str = DEFAULT_ORGANIZATION_CODE,
    site_code: str = DEFAULT_SITE_CODE,
    *,
    status: SubscriptionStatus = SubscriptionStatus.PILOT,
    additional_slots: int = 0,
    with_code: bool = True,
) -> Store:
    """Create (or reuse) the organization, then a new site with a
    subscription and, by default, a store enrollment code - all through the
    operator services."""
    await admin_service.create_organization(
        db_session, organization_code, "Test Organization", TEST_ACTOR
    )
    await admin_service.create_site(db_session, organization_code, site_code, TEST_ACTOR, status)
    if additional_slots:
        await admin_service.set_additional_slots(
            db_session, organization_code, site_code, additional_slots, TEST_ACTOR
        )
    code = None
    if with_code:
        code = await rotate_code(db_session, organization_code, site_code)

    db_session.expire_all()
    organization = (
        await db_session.execute(
            select(Organization).where(Organization.organization_code == organization_code)
        )
    ).scalar_one()
    site = (
        await db_session.execute(
            select(Site).where(Site.organization_id == organization.id, Site.site_code == site_code)
        )
    ).scalar_one()
    return Store(
        organization_code=organization.organization_code,
        site_code=site.site_code,
        organization_id=organization.id,
        site_id=site.id,
        code=code,
    )


async def rotate_code(
    db_session: AsyncSession,
    organization_code: str = DEFAULT_ORGANIZATION_CODE,
    site_code: str = DEFAULT_SITE_CODE,
) -> str:
    rotated = await admin_service.rotate_enrollment_code(
        db_session, Argon2CredentialHasher(), organization_code, site_code, TEST_ACTOR
    )
    return rotated.code


async def enroll(
    client: AsyncClient,
    device_id: str,
    code: str | None,
    organization_code: str = DEFAULT_ORGANIZATION_CODE,
    site_code: str = DEFAULT_SITE_CODE,
) -> Response:
    return await client.post(
        "/enrollment",
        json={
            "organization_code": organization_code,
            "site_code": site_code,
            "device_id": device_id,
            "enrollment_authorization": code or "WRONG-CODE-00000",
        },
    )


async def enroll_at(client: AsyncClient, store: Store, device_id: str, code: str | None = None) -> Response:
    return await enroll(
        client, device_id, code or store.code, store.organization_code, store.site_code
    )


async def verify(client: AsyncClient, device_id: str, credential: str) -> Response:
    return await client.post(
        f"/enrolled_devices/{device_id}/verify", json={"enrollment_credential": credential}
    )


async def fetch_device(db_session: AsyncSession, device_id: str) -> EnrolledDevice | None:
    # Re-fetch from the DB rather than trusting db_session's identity map,
    # since the app commits through a *different* session.
    db_session.expire_all()
    result = await db_session.execute(
        select(EnrolledDevice).where(EnrolledDevice.device_id == device_id)
    )
    return result.scalar_one_or_none()


async def fetch_subscription(db_session: AsyncSession, site_id: uuid.UUID) -> SiteSubscription:
    db_session.expire_all()
    return (
        await db_session.execute(select(SiteSubscription).where(SiteSubscription.site_id == site_id))
    ).scalar_one()


async def fetch_site(db_session: AsyncSession, site_id: uuid.UUID) -> Site:
    db_session.expire_all()
    return (await db_session.execute(select(Site).where(Site.id == site_id))).scalar_one()


async def count_devices(db_session: AsyncSession, site_id: uuid.UUID) -> int:
    db_session.expire_all()
    return len(
        (await db_session.execute(select(EnrolledDevice).where(EnrolledDevice.site_id == site_id)))
        .scalars()
        .all()
    )


async def audit_events(db_session: AsyncSession, action: str | None = None) -> list[AdminAuditEvent]:
    db_session.expire_all()
    statement = select(AdminAuditEvent).order_by(AdminAuditEvent.occurred_at)
    if action is not None:
        statement = statement.where(AdminAuditEvent.action == action)
    return list((await db_session.execute(statement)).scalars().all())


def make_client(
    sessionmaker_: async_sessionmaker[AsyncSession], client_ip: str = "127.0.0.1", **settings
) -> AsyncClient:
    from tests.conftest import make_settings

    app = create_app(make_settings(**settings))
    app.state.sessionmaker = sessionmaker_
    return AsyncClient(
        transport=ASGITransport(app=app, client=(client_ip, 50000)), base_url="http://test"
    )


def parse_timestamp(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    assert parsed.tzinfo is not None, f"timestamp {value!r} has no UTC offset"
    return parsed
