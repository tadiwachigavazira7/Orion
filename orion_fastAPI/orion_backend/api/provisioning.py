from uuid import UUID

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_db, require_admin_api_key
from orion_backend.db.models import EnrolledDevice
from orion_backend.schemas import (
    AdminDeviceResponse,
    OrganizationCreateRequest,
    OrganizationResponse,
    ProvisioningInfoResponse,
    SiteCreateRequest,
    SiteResponse,
)
from orion_backend.services import device_service, provisioning_service

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


def _device_response(device: EnrolledDevice) -> AdminDeviceResponse:
    return AdminDeviceResponse(
        device_id=device.device_id,
        site_code=device.site.site_code,
        organization_code=device.site.organization.organization_code,
        status=device.status,
        created_at=device.created_at,
        updated_at=device.updated_at,
        last_verified_at=device.last_verified_at,
    )


@router.get(
    "/organizations/{organization_code}/sites",
    response_model=list[SiteResponse],
)
async def list_sites(
    organization_code: str,
    db: AsyncSession = Depends(get_db),
) -> list[SiteResponse]:
    sites, organization = await provisioning_service.list_sites(db, organization_code)
    return [
        SiteResponse(
            id=site.id,
            organization_code=organization.organization_code,
            site_code=site.site_code,
            created_at=site.created_at,
            updated_at=site.updated_at,
        )
        for site in sites
    ]


@router.get(
    "/organizations/{organization_code}/sites/{site_code}/provisioning",
    response_model=ProvisioningInfoResponse,
)
async def get_provisioning_info(
    organization_code: str,
    site_code: str,
    db: AsyncSession = Depends(get_db),
) -> ProvisioningInfoResponse:
    site, organization = await provisioning_service.get_site(db, organization_code, site_code)
    return ProvisioningInfoResponse(
        organization_code=organization.organization_code,
        organization_name=organization.name,
        site_code=site.site_code,
        site_id=site.id,
    )


@router.get(
    "/sites/{site_id}/devices",
    response_model=list[AdminDeviceResponse],
)
async def list_site_devices(
    site_id: UUID,
    db: AsyncSession = Depends(get_db),
) -> list[AdminDeviceResponse]:
    devices = await device_service.list_devices_for_site(db, site_id)
    return [_device_response(device) for device in devices]


@router.get(
    "/devices/{device_id}",
    response_model=AdminDeviceResponse,
)
async def get_device(
    device_id: str,
    db: AsyncSession = Depends(get_db),
) -> AdminDeviceResponse:
    return _device_response(await device_service.get_device(db, device_id))


@router.post(
    "/devices/{device_id}/deactivate",
    response_model=AdminDeviceResponse,
)
async def deactivate_device(
    device_id: str,
    db: AsyncSession = Depends(get_db),
) -> AdminDeviceResponse:
    # Idempotent: device_service.revoke_device keeps the row and is a no-op
    # when the device is already REVOKED.
    return _device_response(await device_service.revoke_device(db, device_id))
