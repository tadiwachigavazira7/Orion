from datetime import datetime

from pydantic import BaseModel, Field

from orion_backend.db.models import DeviceStatus


class EnrollmentRequest(BaseModel):
    organization_code: str = Field(min_length=1, max_length=128)
    site_code: str = Field(min_length=1, max_length=128)
    device_id: str = Field(min_length=1, max_length=128)
    # The store's reusable enrollment code (field name kept from v2 for
    # client compatibility). Normalized server-side; see OPERATIONS.md.
    enrollment_authorization: str = Field(min_length=1, max_length=256)


class EnrollmentResponse(BaseModel):
    device_id: str
    organization_code: str
    site_code: str
    status: DeviceStatus
    enrollment_credential: str  # returned exactly once, at enrollment time only
    server_time: datetime
    offline_valid_until: datetime


class VerifyDeviceRequest(BaseModel):
    enrollment_credential: str = Field(min_length=1, max_length=4096)


class VerifyDeviceResponse(BaseModel):
    verified: bool
    device_id: str
    organization_code: str
    site_code: str
    status: DeviceStatus
    server_time: datetime
    offline_valid_until: datetime


class ErrorResponse(BaseModel):
    detail: str
    error_code: str
