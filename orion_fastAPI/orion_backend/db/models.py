import enum
import uuid
from datetime import datetime

from sqlalchemy import DateTime, Enum as SAEnum, String, func
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column

from orion_backend.db.base import Base


class DeviceStatus(str, enum.Enum):
    ACTIVE = "ACTIVE"
    REVOKED = "REVOKED"


class EnrolledDevice(Base):
    """A PDT device trusted to run Orion. Never stores the plaintext
    enrollment credential — only an Argon2id hash (see services.credential_hasher).
    """

    __tablename__ = "enrolled_devices"

    id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    device_id: Mapped[str] = mapped_column(String(128), unique=True, nullable=False, index=True)
    site_id: Mapped[str] = mapped_column(String(128), nullable=False)
    credential_hash: Mapped[str] = mapped_column(String(512), nullable=False)
    status: Mapped[DeviceStatus] = mapped_column(
        SAEnum(DeviceStatus, name="device_status", native_enum=True),
        nullable=False,
        default=DeviceStatus.ACTIVE,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )
    last_verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
