from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_credential_hasher, get_db
from orion_backend.schemas import (
    DeviceStatusResponse,
    VerifyDeviceRequest,
    VerifyDeviceResponse,
)
from orion_backend.services import device_service
from orion_backend.services.credential_hasher import CredentialHasher

router = APIRouter(tags=["enrolled_devices"])


@router.post(
    "/enrolled_devices/{device_id}/verify",
    response_model=VerifyDeviceResponse,
)
async def verify_device(
    device_id: str,
    body: VerifyDeviceRequest,
    db: AsyncSession = Depends(get_db),
    hasher: CredentialHasher = Depends(get_credential_hasher),
) -> VerifyDeviceResponse:
    device = await device_service.verify_device(db, hasher, device_id, body.enrollment_credential)
    return VerifyDeviceResponse(
        verified=True,
        device_id=device.device_id,
        organization_code=device.site.organization.organization_code,
        site_code=device.site.site_code,
        status=device.status,
    )


@router.get(
    "/enrolled_devices/{device_id}",
    response_model=DeviceStatusResponse,
)
async def get_device(
    device_id: str,
    db: AsyncSession = Depends(get_db),
) -> DeviceStatusResponse:
    device = await device_service.get_device(db, device_id)
    return DeviceStatusResponse(
        device_id=device.device_id,
        organization_code=device.site.organization.organization_code,
        site_code=device.site.site_code,
        status=device.status,
        last_verified_at=device.last_verified_at,
    )


@router.delete(
    "/enrolled_devices/{device_id}",
    response_model=DeviceStatusResponse,
)
async def revoke_device(
    device_id: str,
    db: AsyncSession = Depends(get_db),
) -> DeviceStatusResponse:
    device = await device_service.revoke_device(db, device_id)
    return DeviceStatusResponse(
        device_id=device.device_id,
        organization_code=device.site.organization.organization_code,
        site_code=device.site.site_code,
        status=device.status,
        last_verified_at=device.last_verified_at,
    )
