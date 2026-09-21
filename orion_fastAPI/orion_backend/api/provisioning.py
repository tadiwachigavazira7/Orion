from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_db, require_admin_api_key
from orion_backend.schemas import (
    OrganizationCreateRequest,
    OrganizationResponse,
    SiteCreateRequest,
    SiteResponse,
)
from orion_backend.services import provisioning_service

# Every route on this router requires the admin API key - this is Orion's
# own internal provisioning surface, structurally separate from the
# unauthenticated Android enrollment/verification endpoints (see CLAUDE.md).
router = APIRouter(prefix="/admin", tags=["provisioning"], dependencies=[Depends(require_admin_api_key)])


@router.post(
    "/organizations",
    response_model=OrganizationResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_organization(
    body: OrganizationCreateRequest,
    db: AsyncSession = Depends(get_db),
) -> OrganizationResponse:
    organization = await provisioning_service.create_organization(
        db, body.organization_code, body.name
    )
    return OrganizationResponse(
        id=organization.id,
        organization_code=organization.organization_code,
        name=organization.name,
        created_at=organization.created_at,
        updated_at=organization.updated_at,
    )


@router.post(
    "/sites",
    response_model=SiteResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_site(
    body: SiteCreateRequest,
    db: AsyncSession = Depends(get_db),
) -> SiteResponse:
    site, organization = await provisioning_service.create_site(
        db, body.organization_code, body.site_code
    )
    return SiteResponse(
        id=site.id,
        organization_code=organization.organization_code,
        site_code=site.site_code,
        created_at=site.created_at,
        updated_at=site.updated_at,
    )
