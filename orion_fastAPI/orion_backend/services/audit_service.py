"""Admin audit trail. Every operator change writes one admin_audit_events
row in the same transaction as the change (callers commit).

before/after hold only non-secret descriptive fields; never a store code,
a device credential, or any hash.
"""

import uuid
from dataclasses import dataclass
from typing import Any, Literal

from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import AdminAuditEvent
from orion_backend.services.validation import require_name

ActorSource = Literal["flag", "os_user"]


@dataclass(frozen=True)
class Actor:
    name: str
    # "flag": given explicitly with --actor; "os_user": defaulted from the OS.
    source: ActorSource

    def __post_init__(self) -> None:
        require_name("actor", self.name)
        if self.source not in ("flag", "os_user"):
            raise ValueError(f"unknown actor source {self.source!r}")


def record(
    db: AsyncSession,
    actor: Actor,
    action: str,
    *,
    organization_id: uuid.UUID | None = None,
    site_id: uuid.UUID | None = None,
    device_id: str | None = None,
    before: dict[str, Any] | None = None,
    after: dict[str, Any] | None = None,
) -> None:
    db.add(
        AdminAuditEvent(
            actor=actor.name,
            actor_source=actor.source,
            action=action,
            organization_id=organization_id,
            site_id=site_id,
            device_id=device_id,
            before=before,
            after=after,
        )
    )
