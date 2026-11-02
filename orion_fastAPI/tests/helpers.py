from datetime import UTC, datetime, timedelta

from httpx import AsyncClient, Response
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import EnrolledDevice, EnrollmentAuthorization, Organization, Site
from orion_backend.services import enrollment_authorization_service
from orion_backend.services.enrollment_authorization_service import hash_authorization_secret

DEFAULT_ORGANIZATION_CODE = "TEST-ORG-001"
DEFAULT_SITE_CODE = "TEST-SITE-001"


async def create_organization_and_site(
    db_session: AsyncSession,
    organization_code: str = DEFAULT_ORGANIZATION_CODE,
    site_code: str = DEFAULT_SITE_CODE,
) -> tuple[Organization, Site]:
    organization = Organization(organization_code=organization_code, name="Test Organization")
    db_session.add(organization)
    await db_session.commit()
    await db_session.refresh(organization)

    site = Site(site_code=site_code, organization_id=organization.id)
    db_session.add(site)
    await db_session.commit()
    await db_session.refresh(site)

    return organization, site


async def issue_authorization(
    db_session: AsyncSession,
    device_id: str,
    organization_code: str = DEFAULT_ORGANIZATION_CODE,
    site_code: str = DEFAULT_SITE_CODE,
    ttl_minutes: int = 30,
) -> str:
    issued = await enrollment_authorization_service.issue_authorization(
        db_session, organization_code, site_code, device_id, ttl_minutes=ttl_minutes
    )
    return issued.secret


async def enroll(
    client: AsyncClient,
    device_id: str = "PDT-100",
    enrollment_authorization: str = "not-a-real-authorization",
    organization_code: str = DEFAULT_ORGANIZATION_CODE,
    site_code: str = DEFAULT_SITE_CODE,
) -> Response:
    return await client.post(
        "/enrollment",
        json={
            "organization_code": organization_code,
            "site_code": site_code,
            "device_id": device_id,
            "enrollment_authorization": enrollment_authorization,
        },
    )


async def authorize_and_enroll(
    client: AsyncClient,
    db_session: AsyncSession,
    device_id: str,
    organization_code: str = DEFAULT_ORGANIZATION_CODE,
    site_code: str = DEFAULT_SITE_CODE,
) -> Response:
    secret = await issue_authorization(db_session, device_id, organization_code, site_code)
    return await enroll(client, device_id, secret, organization_code, site_code)


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


async def fetch_authorization(db_session: AsyncSession, secret: str) -> EnrollmentAuthorization:
    db_session.expire_all()
    result = await db_session.execute(
        select(EnrollmentAuthorization).where(
            EnrollmentAuthorization.secret_hash == hash_authorization_secret(secret)
        )
    )
    return result.scalar_one()


async def expire_authorization(db_session: AsyncSession, secret: str) -> None:
    await db_session.execute(
        update(EnrollmentAuthorization)
        .where(EnrollmentAuthorization.secret_hash == hash_authorization_secret(secret))
        .values(expires_at=datetime.now(UTC) - timedelta(seconds=1))
    )
    await db_session.commit()


def parse_timestamp(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    assert parsed.tzinfo is not None, f"timestamp {value!r} has no UTC offset"
    return parsed
