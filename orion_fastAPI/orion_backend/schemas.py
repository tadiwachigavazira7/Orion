from datetime import datetime

from pydantic import BaseModel, Field

from orion_backend.db.models import DeviceStatus


MAX_DEVICE_SLOTS = 10000  # sanity cap; also keeps values far from the DB integer limit


class EnrollmentRequest(BaseModel):
    organization_code: str = Field(min_length=1, max_length=128)
    site_code: str = Field(min_length=1, max_length=128)
    device_id: str = Field(min_length=1, max_length=128)
    enrollment_code: str = Field(min_length=1, max_length=128)


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


class DeviceReleasedResponse(BaseModel):
    device_id: str
    organization_code: str
    site_code: str
    previous_status: DeviceStatus


class SetCapacityRequest(BaseModel):
    extra_device_slots: int = Field(ge=0, le=MAX_DEVICE_SLOTS)
    included_device_slots: int | None = Field(default=None, ge=0, le=MAX_DEVICE_SLOTS)


class CapacityResponse(BaseModel):
    organization_code: str
    site_code: str
    included_slots: int
    extra_device_slots: int
    capacity: int
    active_devices: int
    # Additive fields
    included_device_slots: int
    max_devices: int
    available_slots: int


class ErrorResponse(BaseModel):
    detail: str
    error_code: str
