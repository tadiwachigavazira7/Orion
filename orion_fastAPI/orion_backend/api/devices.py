"""Public device routes.

Only verification is exposed over HTTP. Device inspection, revocation, and
reset are operator actions with no HTTP route (see orion_backend/cli.py and
OPERATIONS.md); the former public GET/DELETE /enrolled_devices/{device_id}
routes were removed because they were unauthenticated.
"""

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_app_settings, get_credential_hasher, get_db
from orion_backend.config import Settings
from orion_backend.schemas import ErrorResponse, VerifyDeviceRequest, VerifyDeviceResponse
from orion_backend.services import device_service
from orion_backend.services.credential_hasher import CredentialHasher

router = APIRouter(tags=["enrolled_devices"])


@router.post(
    "/enrolled_devices/{device_id}/verify",
    response_model=VerifyDeviceResponse,
    responses={401: {"model": ErrorResponse}, 403: {"model": ErrorResponse}, 404: {"model": ErrorResponse}},
)
async def verify_device(
    device_id: str,
    body: VerifyDeviceRequest,
    db: AsyncSession = Depends(get_db),
    hasher: CredentialHasher = Depends(get_credential_hasher),
    settings: Settings = Depends(get_app_settings),
) -> VerifyDeviceResponse:
    verified = await device_service.verify_device(
        db,
        hasher,
        device_id,
        body.enrollment_credential,
        offline_authorization_hours=settings.offline_authorization_hours,
    )
    return VerifyDeviceResponse(
        verified=True,
        device_id=verified.device_id,
        organization_code=verified.organization_code,
        site_code=verified.site_code,
        status=verified.status,
        server_time=verified.window.server_time,
        offline_valid_until=verified.window.offline_valid_until,
    )
