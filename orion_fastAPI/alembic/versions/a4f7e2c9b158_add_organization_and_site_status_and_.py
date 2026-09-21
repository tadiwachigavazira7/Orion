"""add organization and site status, and site name

Revision ID: a4f7e2c9b158
Revises: d2bdced50673
Create Date: 2026-09-21 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


# revision identifiers, used by Alembic.
revision: str = 'a4f7e2c9b158'
down_revision: Union[str, Sequence[str], None] = 'd2bdced50673'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # ### commands adjusted by hand - op.add_column does not auto-create the
    # backing Postgres enum type the way op.create_table does, so each enum
    # type is created explicitly (checkfirst=True keeps this idempotent)
    # before the column that uses it is added. create_type=False on the
    # column's own Enum then prevents a redundant CREATE TYPE attempt. ###
    organization_status = postgresql.ENUM(
        'ACTIVE', 'SUSPENDED', name='organization_status'
    )
    organization_status.create(op.get_bind(), checkfirst=True)
    op.add_column(
        'organizations',
        sa.Column(
            'status',
            sa.Enum('ACTIVE', 'SUSPENDED', name='organization_status', create_type=False),
            nullable=False,
            server_default='ACTIVE',
        ),
    )

    site_status = postgresql.ENUM('ACTIVE', 'SUSPENDED', name='site_status')
    site_status.create(op.get_bind(), checkfirst=True)
    op.add_column(
        'sites',
        sa.Column(
            'status',
            sa.Enum('ACTIVE', 'SUSPENDED', name='site_status', create_type=False),
            nullable=False,
            server_default='ACTIVE',
        ),
    )

    op.add_column('sites', sa.Column('name', sa.String(length=255), nullable=True))
    # ### end commands ###


def downgrade() -> None:
    """Downgrade schema."""
    # ### commands adjusted by hand ###
    op.drop_column('sites', 'name')
    op.drop_column('sites', 'status')
    op.drop_column('organizations', 'status')

    postgresql.ENUM(name='site_status').drop(op.get_bind(), checkfirst=True)
    postgresql.ENUM(name='organization_status').drop(op.get_bind(), checkfirst=True)
    # ### end commands ###
