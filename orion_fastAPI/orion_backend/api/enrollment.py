from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.api.deps import get_credential_hasher, get_db
from orion_backend.schemas import EnrollmentRequest, EnrollmentResponse
from orion_backend.services import enrollment_service
from orion_backend.services.credential_hasher import CredentialHasher

router = APIRouter(tags=["enrollment"])


@router.post(
    "/enrollment",
    response_model=EnrollmentResponse,
    status_code=status.HTTP_201_CREATED,
)
async def enroll(
    body: EnrollmentRequest,
    db: AsyncSession = Depends(get_db),
    hasher: CredentialHasher = Depends(get_credential_hasher),
) -> EnrollmentResponse:
    device, organization, site, credential = await enrollment_service.enroll_device_at_site(
        db, hasher, body.organization_code, body.site_code, body.device_id
    )
    return EnrollmentResponse(
        device_id=device.device_id,
        organization_code=organization.organization_code,
        site_code=site.site_code,
        status=device.status,
        enrollment_credential=credential,
    )
