from datetime import datetime

from pydantic import BaseModel, Field

from orion_backend.db.models import DeviceStatus


class EnrollDeviceRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    site_id: str = Field(min_length=1, max_length=128)
    enrollment_credential: str = Field(min_length=8, max_length=4096)


class EnrollDeviceResponse(BaseModel):
    device_id: str
    site_id: str
    status: DeviceStatus


class VerifyDeviceRequest(BaseModel):
    enrollment_credential: str = Field(min_length=1, max_length=4096)


class VerifyDeviceResponse(BaseModel):
    verified: bool
    device_id: str
    site_id: str


class DeviceStatusResponse(BaseModel):
    device_id: str
    site_id: str
    status: DeviceStatus
    last_verified_at: datetime | None


class ErrorResponse(BaseModel):
    detail: str
    error_code: str
