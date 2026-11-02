from datetime import timedelta

from fastapi import APIRouter, Depends, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_app_settings, get_credential_hasher, get_db
from orion_backend.config import Settings
from orion_backend.schemas import EnrollmentRequest, EnrollmentResponse, ErrorResponse
from orion_backend.services import enrollment_service
from orion_backend.services.credential_hasher import CredentialHasher
from orion_backend.services.rate_limit_service import RateLimitPolicy

router = APIRouter(tags=["enrollment"])


@router.post(
    "/enrollment",
    response_model=EnrollmentResponse,
    status_code=status.HTTP_201_CREATED,
    responses={
        403: {"model": ErrorResponse},
        404: {"model": ErrorResponse},
        409: {"model": ErrorResponse},
        429: {"model": ErrorResponse},
    },
)
async def enroll(
    body: EnrollmentRequest,
    request: Request,
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
        # Direct peer address only; proxy headers are deliberately not
        # trusted until a deployment (and its proxy) is chosen.
        client_ip=request.client.host if request.client else None,
        rate_limit=RateLimitPolicy(
            window=timedelta(minutes=settings.enrollment_rate_limit_window_minutes),
            max_failures_per_client=settings.enrollment_rate_limit_max_failures_per_client,
            max_failures_per_site=settings.enrollment_rate_limit_max_failures_per_site,
        ),
        offline_authorization_hours=settings.offline_authorization_hours,
    )
    return EnrollmentResponse(
        device_id=outcome.device_id,
        organization_code=outcome.organization_code,
        site_code=outcome.site_code,
        status=outcome.status,
        enrollment_credential=outcome.credential,
        server_time=outcome.window.server_time,
        offline_valid_until=outcome.window.offline_valid_until,
    )
