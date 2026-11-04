"""Append-only audit trail. record() only adds the row; the caller commits it
in the same transaction as the change it describes.

Never pass credentials, enrollment codes or hashes in `details`.
"""

import logging
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import AuditEvent

# Rejected (not persisted) attempts are logged here too - site_id/device_id/reason only.
logger = logging.getLogger("orion_backend.audit")


def record(
    db: AsyncSession, actor: str, action: str, site_id: uuid.UUID | None, details: dict | None = None
) -> None:
    db.add(AuditEvent(actor=actor, action=action, site_id=site_id, details=details or {}))
