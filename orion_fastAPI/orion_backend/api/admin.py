import secrets

from fastapi import APIRouter, Depends, Header
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_db
from orion_backend.config import get_settings
from orion_backend.errors import AdminApiNotConfiguredError, InvalidAdminKeyError
from orion_backend.schemas import CapacityResponse, SetCapacityRequest
from orion_backend.services import admin_service


def require_admin_key(x_admin_key: str | None = Header(default=None)) -> None:
    expected = get_settings().admin_api_key
    if not expected:
        raise AdminApiNotConfiguredError()
    if x_admin_key is None or not secrets.compare_digest(
        x_admin_key.encode(), expected.encode()
    ):
        raise InvalidAdminKeyError()


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
