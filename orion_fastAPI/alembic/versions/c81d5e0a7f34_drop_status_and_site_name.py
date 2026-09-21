"""drop organizations.status, sites.status, sites.name and their enums

Revision ID: c81d5e0a7f34
Revises: a4f7e2c9b158
Create Date: 2026-09-21 12:00:00.000000

Cleans up databases that applied the earlier (now no-op) a4f7e2c9b158.
Every statement is guarded with IF EXISTS so it is also a no-op on a
database that never had those objects.
"""
from typing import Sequence, Union

from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'c81d5e0a7f34'
down_revision: Union[str, Sequence[str], None] = 'a4f7e2c9b158'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.execute("ALTER TABLE organizations DROP COLUMN IF EXISTS status")
    op.execute("ALTER TABLE sites DROP COLUMN IF EXISTS status")
    op.execute("ALTER TABLE sites DROP COLUMN IF EXISTS name")
    op.execute("DROP TYPE IF EXISTS organization_status")
    op.execute("DROP TYPE IF EXISTS site_status")


def downgrade() -> None:
    """Downgrade schema.

    Intentionally empty: the dropped columns are not part of the intended
    schema, so there is nothing to restore.
    """
