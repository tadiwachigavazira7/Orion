from datetime import datetime
from functools import lru_cache

from httpx import AsyncClient, Response
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import (
    Organization,
    Site,
    Subscription,
    SubscriptionPlan,
    SubscriptionStatus,
)
from orion_backend.services.credential_hasher import Argon2CredentialHasher

DEFAULT_ORGANIZATION_CODE = "TEST-ORG-001"
DEFAULT_SITE_CODE = "TEST-SITE-001"
DEFAULT_ENROLLMENT_CODE = "test-enrollment-code"


@lru_cache
def _argon_hash(code: str) -> str:
    return Argon2CredentialHasher().hash(code)


async def create_organization_and_site(
    db_session: AsyncSession,
    organization_code: str = DEFAULT_ORGANIZATION_CODE,
    site_code: str = DEFAULT_SITE_CODE,
    *,
    subscription_status: SubscriptionStatus | None = SubscriptionStatus.ACTIVE,
    period_end: datetime | None = None,
    enrollment_code: str | None = DEFAULT_ENROLLMENT_CODE,
    hash_fn=_argon_hash,
) -> tuple[Organization, Site]:
    """Provisions an ACTIVE subscription and an enrollment code by default.
    subscription_status=None => no subscription; enrollment_code=None => not provisioned."""
    organization = Organization(organization_code=organization_code, name="Test Organization")
    db_session.add(organization)
    await db_session.commit()
    await db_session.refresh(organization)

    site = Site(
        site_code=site_code,
        organization_id=organization.id,
        enrollment_code_hash=hash_fn(enrollment_code) if enrollment_code is not None else None,
    )
    db_session.add(site)
    await db_session.commit()
    await db_session.refresh(site)

    if subscription_status is not None:
        db_session.add(
            Subscription(
                site_id=site.id,
                plan_code=SubscriptionPlan.STANDARD,
                status=subscription_status,
                current_period_end=period_end,
            )
        )
        await db_session.commit()

    return organization, site


async def enroll(
    client: AsyncClient,
    device_id: str = "PDT-100",
    organization_code: str = DEFAULT_ORGANIZATION_CODE,
    site_code: str = DEFAULT_SITE_CODE,
    enrollment_code: str = DEFAULT_ENROLLMENT_CODE,
) -> Response:
    return await client.post(
        "/enrollment",
        json={
            "organization_code": organization_code,
            "site_code": site_code,
            "device_id": device_id,
            "enrollment_code": enrollment_code,
        },
    )
