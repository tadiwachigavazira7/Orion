"""add sites.extra_device_slots

Revision ID: a1c3e5f7b9d2
Revises: d2bdced50673
Create Date: 2026-11-03 12:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'a1c3e5f7b9d2'
down_revision: Union[str, Sequence[str], None] = 'd2bdced50673'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'sites', sa.Column('extra_device_slots', sa.Integer(), server_default='0', nullable=False)
    )
    op.create_check_constraint(
        'ck_sites_extra_device_slots_nonneg', 'sites', 'extra_device_slots >= 0'
    )


def downgrade() -> None:
    op.drop_constraint('ck_sites_extra_device_slots_nonneg', 'sites', type_='check')
    op.drop_column('sites', 'extra_device_slots')
