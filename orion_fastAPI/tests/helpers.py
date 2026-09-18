from httpx import AsyncClient, Response
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import Organization, Site

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


async def enroll(
    client: AsyncClient,
    device_id: str = "PDT-100",
    organization_code: str = DEFAULT_ORGANIZATION_CODE,
    site_code: str = DEFAULT_SITE_CODE,
) -> Response:
    return await client.post(
        "/enrollment",
        json={
            "organization_code": organization_code,
            "site_code": site_code,
            "device_id": device_id,
        },
    )
