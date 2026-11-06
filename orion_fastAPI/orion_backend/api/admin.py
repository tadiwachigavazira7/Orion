from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_db, require_admin_key
from orion_backend.schemas import CapacityResponse, SetCapacityRequest
from orion_backend.services import admin_service


router = APIRouter(tags=["admin"], dependencies=[Depends(require_admin_key)])


@router.put(
    "/organizations/{organization_code}/sites/{site_code}/capacity",
    response_model=CapacityResponse,
)
async def set_capacity(
    organization_code: str,
    site_code: str,
    body: SetCapacityRequest,
    db: AsyncSession = Depends(get_db),
) -> CapacityResponse:
    site, active = await admin_service.set_capacity(
        db, organization_code, site_code, "admin-api",
        extra_device_slots=body.extra_device_slots,
        included_device_slots=body.included_device_slots,
    )
    return CapacityResponse(
        organization_code=organization_code,
        site_code=site_code,
        included_slots=site.included_device_slots,
        extra_device_slots=site.extra_device_slots,
        capacity=site.max_devices,
        active_devices=active,
        included_device_slots=site.included_device_slots,
        max_devices=site.max_devices,
        available_slots=max(site.max_devices - active, 0),
    )
