from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_credential_hasher, get_db
from orion_backend.schemas import (
    DeviceStatusResponse,
    EnrollDeviceRequest,
    EnrollDeviceResponse,
    VerifyDeviceRequest,
    VerifyDeviceResponse,
)
from orion_backend.services import device_service
from orion_backend.services.credential_hasher import CredentialHasher

router = APIRouter(tags=["enrolled_devices"])


@router.post(
    "/enrolled_devices",
    response_model=EnrollDeviceResponse,
    status_code=status.HTTP_201_CREATED,
)
async def enroll_device(
    body: EnrollDeviceRequest,
    db: AsyncSession = Depends(get_db),
    hasher: CredentialHasher = Depends(get_credential_hasher),
) -> EnrollDeviceResponse:
    device = await device_service.enroll_device(
        db, hasher, body.device_id, body.site_id, body.enrollment_credential
    )
    return EnrollDeviceResponse(device_id=device.device_id, site_id=device.site_id, status=device.status)


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
    return VerifyDeviceResponse(verified=True, device_id=device.device_id, site_id=device.site_id)


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
        site_id=device.site_id,
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
        site_id=device.site_id,
        status=device.status,
        last_verified_at=device.last_verified_at,
    )
