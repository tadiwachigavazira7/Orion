from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field

from orion_backend.db.models import DeviceStatus


class EnrollmentRequest(BaseModel):
    organization_code: str = Field(min_length=1, max_length=128)
    site_code: str = Field(min_length=1, max_length=128)
    device_id: str = Field(min_length=1, max_length=128)


class EnrollmentResponse(BaseModel):
    device_id: str
    organization_code: str
    site_code: str
    status: DeviceStatus
    enrollment_credential: str  # returned exactly once, at enrollment time only


class VerifyDeviceRequest(BaseModel):
    enrollment_credential: str = Field(min_length=1, max_length=4096)


class VerifyDeviceResponse(BaseModel):
    verified: bool
    device_id: str
    organization_code: str
    site_code: str
    status: DeviceStatus


class DeviceStatusResponse(BaseModel):
    device_id: str
    organization_code: str
    site_code: str
    status: DeviceStatus
    last_verified_at: datetime | None


class ErrorResponse(BaseModel):
    detail: str
    error_code: str


class OrganizationCreateRequest(BaseModel):
    organization_code: str = Field(min_length=1, max_length=128)
    name: str = Field(min_length=1, max_length=255)


class OrganizationResponse(BaseModel):
    id: UUID
    organization_code: str
    name: str
    created_at: datetime
    updated_at: datetime


class SiteCreateRequest(BaseModel):
    organization_code: str = Field(min_length=1, max_length=128)
    site_code: str = Field(min_length=1, max_length=128)


class SiteResponse(BaseModel):
    id: UUID
    organization_code: str
    site_code: str
    created_at: datetime
    updated_at: datetime
