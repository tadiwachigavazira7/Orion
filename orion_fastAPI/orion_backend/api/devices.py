from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_credential_hasher, get_db, require_admin_key
from orion_backend.schemas import (
    DeviceReleasedResponse,
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
    dependencies=[Depends(require_admin_key)],
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
    dependencies=[Depends(require_admin_key)],
)
async def revoke_device(
    device_id: str,
    db: AsyncSession = Depends(get_db),
) -> DeviceStatusResponse:
    device = await device_service.revoke_device(db, device_id, "admin-api")
    return DeviceStatusResponse(
        device_id=device.device_id,
        organization_code=device.site.organization.organization_code,
        site_code=device.site.site_code,
        status=device.status,
        last_verified_at=device.last_verified_at,
    )


@router.post(
    "/enrolled_devices/{device_id}/release",
    response_model=DeviceReleasedResponse,
    dependencies=[Depends(require_admin_key)],
)
async def release_device(
    device_id: str,
    db: AsyncSession = Depends(get_db),
) -> DeviceReleasedResponse:
    """Operator recovery: delete the record so the PDT can enroll again. Returns no credential."""
    device = await device_service.release_device(db, device_id, "admin-api")
    return DeviceReleasedResponse(
        device_id=device.device_id,
        organization_code=device.site.organization.organization_code,
        site_code=device.site.site_code,
        previous_status=device.status,
    )
