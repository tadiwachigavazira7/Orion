import enum
import uuid
from datetime import datetime

from sqlalchemy import (
    DateTime,
    Enum as SAEnum,
    ForeignKey,
    Index,
    String,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from orion_backend.db.base import Base


class DeviceStatus(str, enum.Enum):
    """Device lifecycle (see OPERATIONS.md for the full transition table).

    ACTIVE             - enrolled, holds a credential, may verify.
    REVOKED            - blocked by an operator; keeps its credential hash so a
                         verify with the right credential is told "revoked".
    PENDING_ENROLLMENT - reset by an operator; has no credential and may only
                         become ACTIVE again via POST /enrollment with a new,
                         operator-issued enrollment authorization.
    """

    ACTIVE = "ACTIVE"
    REVOKED = "REVOKED"
    PENDING_ENROLLMENT = "PENDING_ENROLLMENT"


class EnrolledDevice(Base):
    """A PDT device trusted to run Orion. Never stores the plaintext
    enrollment credential — only an Argon2id hash (see services.credential_hasher).

    credential_hash is NULL exactly when the device is PENDING_ENROLLMENT
    (after an operator reset), so the old credential can never verify again.
    """

    __tablename__ = "enrolled_devices"

    id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    device_id: Mapped[str] = mapped_column(String(128), unique=True, nullable=False, index=True)
    site_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("sites.id"), nullable=False
    )
    credential_hash: Mapped[str | None] = mapped_column(String(512), nullable=True)
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

    site: Mapped["Site"] = relationship()


class Organization(Base):
    """A retailer organization/tenant identified by a unique organization code."""

    __tablename__ = "organizations"

    id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    organization_code: Mapped[str] = mapped_column(
        String(128), unique=True, nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )

    sites: Mapped[list["Site"]] = relationship(back_populates="organization")


class Site(Base):
    """A retailer store/warehouse site, scoped to an organization.

    site_code is only unique within its organization (see
    uq_sites_organization_id_site_code) - the same code may be reused by
    different organizations.
    """

    __tablename__ = "sites"

    id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    site_code: Mapped[str] = mapped_column(String(128), nullable=False)
    organization_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("organizations.id"), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )

    organization: Mapped["Organization"] = relationship(back_populates="sites")

    __table_args__ = (
        UniqueConstraint("organization_id", "site_code", name="uq_sites_organization_id_site_code"),
    )


class EnrollmentAuthorization(Base):
    """A short-lived, single-use, operator-issued permission for one specific
    device_id to enroll at one specific organization/site.

    Only a SHA-256 hex digest of the secret is stored (never the plaintext).
    A fast digest is acceptable here, unlike for device credentials (Argon2id),
    because the secret is 256 bits from secrets.token_urlsafe(32): it cannot be
    brute-forced, and a deterministic digest enables an indexed lookup plus an
    atomic conditional UPDATE when the authorization is consumed.

    At most one *outstanding* (neither consumed nor invalidated) authorization
    may exist per device_id - enforced by a partial unique index, so concurrent
    issuance cannot leave two live authorizations for the same device.
    """

    __tablename__ = "enrollment_authorizations"

    id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    organization_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("organizations.id"), nullable=False
    )
    site_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("sites.id"), nullable=False
    )
    # The intended device. Deliberately not a FK: a first-time device has no
    # enrolled_devices row yet.
    device_id: Mapped[str] = mapped_column(String(128), nullable=False)
    secret_hash: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    invalidated_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    __table_args__ = (
        Index(
            "uq_enrollment_authorizations_outstanding_device_id",
            "device_id",
            unique=True,
            postgresql_where=text("consumed_at IS NULL AND invalidated_at IS NULL"),
        ),
    )
