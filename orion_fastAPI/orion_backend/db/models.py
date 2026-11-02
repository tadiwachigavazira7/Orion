import enum
import uuid
from datetime import datetime

from typing import Any

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    Enum as SAEnum,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID as PGUUID
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

    # The store enrollment code: one reusable, rotatable secret per site,
    # stored only as an Argon2id hash (services/store_code_service.py).
    # NULL until an operator runs rotate-enrollment-code; enrollment at a
    # site without a code is rejected.
    enrollment_code_hash: Mapped[str | None] = mapped_column(String(512), nullable=True)
    enrollment_code_rotated_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    organization: Mapped["Organization"] = relationship(back_populates="sites")

    __table_args__ = (
        UniqueConstraint("organization_id", "site_code", name="uq_sites_organization_id_site_code"),
    )


class SubscriptionStatus(str, enum.Enum):
    """Commercial status of a site. Only PILOT and ACTIVE allow new
    enrollments; no status affects verification of already-enrolled devices."""

    INACTIVE = "INACTIVE"
    PILOT = "PILOT"
    ACTIVE = "ACTIVE"
    SUSPENDED = "SUSPENDED"


ENROLLABLE_SUBSCRIPTION_STATUSES = frozenset({SubscriptionStatus.PILOT, SubscriptionStatus.ACTIVE})
DEFAULT_INCLUDED_DEVICE_SLOTS = 6


class SiteSubscription(Base):
    """Device capacity for one site (1:1 with sites).

    total_authorized_slots = included_device_slots + additional_device_slots
    is derived, never stored or edited separately. Only ACTIVE devices count
    against it. Capacity only ever changes through an explicit operator
    command; nothing increases it automatically.
    """

    __tablename__ = "site_subscriptions"

    id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    site_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("sites.id"), unique=True, nullable=False
    )
    status: Mapped[SubscriptionStatus] = mapped_column(
        SAEnum(SubscriptionStatus, name="subscription_status", native_enum=True),
        nullable=False,
        default=SubscriptionStatus.INACTIVE,
    )
    included_device_slots: Mapped[int] = mapped_column(
        Integer, nullable=False, default=DEFAULT_INCLUDED_DEVICE_SLOTS, server_default="6"
    )
    additional_device_slots: Mapped[int] = mapped_column(
        Integer, nullable=False, default=0, server_default="0"
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )
    status_changed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    __table_args__ = (
        CheckConstraint("included_device_slots >= 0", name="ck_site_subscriptions_included_nonnegative"),
        CheckConstraint(
            "additional_device_slots >= 0", name="ck_site_subscriptions_additional_nonnegative"
        ),
    )

    @property
    def total_authorized_slots(self) -> int:
        return self.included_device_slots + self.additional_device_slots


class EnrollmentAttemptFailure(Base):
    """One failed store-code attempt, used for DB-backed rate limiting
    (works across workers). Never stores the submitted code."""

    __tablename__ = "enrollment_attempt_failures"

    id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    site_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("sites.id"), nullable=False
    )
    client_ip: Mapped[str] = mapped_column(String(64), nullable=False)
    attempted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    __table_args__ = (
        Index("ix_enrollment_attempt_failures_site_attempted", "site_id", "attempted_at"),
        Index(
            "ix_enrollment_attempt_failures_site_client_attempted",
            "site_id",
            "client_ip",
            "attempted_at",
        ),
    )


class AdminAuditEvent(Base):
    """Append-only record of an operator change, written in the same
    transaction as the change. before/after never contain secrets or hashes."""

    __tablename__ = "admin_audit_events"

    id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    occurred_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False, index=True
    )
    actor: Mapped[str] = mapped_column(String(255), nullable=False)
    # "flag" (--actor given) or "os_user" (defaulted via getpass.getuser()).
    actor_source: Mapped[str] = mapped_column(String(16), nullable=False)
    action: Mapped[str] = mapped_column(String(64), nullable=False)
    organization_id: Mapped[uuid.UUID | None] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("organizations.id"), nullable=True
    )
    site_id: Mapped[uuid.UUID | None] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("sites.id"), nullable=True
    )
    device_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    before: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)
    after: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)
