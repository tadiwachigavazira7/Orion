from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_app_settings, get_credential_hasher, get_db
from orion_backend.config import Settings
from orion_backend.schemas import EnrollmentRequest, EnrollmentResponse, ErrorResponse
from orion_backend.services import enrollment_service
from orion_backend.services.credential_hasher import CredentialHasher

router = APIRouter(tags=["enrollment"])


@router.post(
    "/enrollment",
    response_model=EnrollmentResponse,
    status_code=status.HTTP_201_CREATED,
    responses={403: {"model": ErrorResponse}, 404: {"model": ErrorResponse}, 409: {"model": ErrorResponse}},
)
async def enroll(
    body: EnrollmentRequest,
    db: AsyncSession = Depends(get_db),
    hasher: CredentialHasher = Depends(get_credential_hasher),
    settings: Settings = Depends(get_app_settings),
) -> EnrollmentResponse:
    outcome = await enrollment_service.enroll_device_at_site(
        db,
        hasher,
        body.organization_code,
        body.site_code,
        body.device_id,
        body.enrollment_authorization,
        offline_authorization_hours=settings.offline_authorization_hours,
    )
    return EnrollmentResponse(
        device_id=outcome.device.device_id,
        organization_code=outcome.organization.organization_code,
        site_code=outcome.site.site_code,
        status=outcome.device.status,
        enrollment_credential=outcome.credential,
        server_time=outcome.window.server_time,
        offline_valid_until=outcome.window.offline_valid_until,
    )
