"""add organization and site status, and site name (superseded no-op)

Revision ID: a4f7e2c9b158
Revises: d2bdced50673
Create Date: 2026-09-21 00:00:00.000000

This revision originally added organizations.status, sites.status,
sites.name and the organization_status / site_status enum types. The
intended schema has none of those, so it is now a no-op. Databases that
already applied the old version are cleaned up by the next revision
(c81d5e0a7f34), which drops those objects if they exist.
"""
from typing import Sequence, Union


# revision identifiers, used by Alembic.
revision: str = 'a4f7e2c9b158'
down_revision: Union[str, Sequence[str], None] = 'd2bdced50673'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Intentionally empty - see module docstring."""


def downgrade() -> None:
    """Intentionally empty - see module docstring."""
