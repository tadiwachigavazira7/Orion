"""create sites and add site fk to enrolled_devices

Revision ID: d2bdced50673
Revises: 010422d38325
Create Date: 2026-09-18 16:20:04.825400

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'd2bdced50673'
down_revision: Union[str, Sequence[str], None] = '010422d38325'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # ### commands adjusted by hand - autogenerate cannot express this change ###
    op.create_table('sites',
    sa.Column('id', sa.UUID(), nullable=False),
    sa.Column('site_code', sa.String(length=128), nullable=False),
    sa.Column('organization_id', sa.UUID(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
    sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
    sa.ForeignKeyConstraint(['organization_id'], ['organizations.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('organization_id', 'site_code', name='uq_sites_organization_id_site_code')
    )

    # enrolled_devices.site_id moves from a free-form VARCHAR(128) to a real
    # FK to sites.id. This is pre-launch, dev-only data - there is no
    # production device data to preserve, and the old free-form site_id
    # strings cannot be mapped to a real site row, so this is a breaking
    # change rather than a data migration: any existing enrolled_devices
    # rows are dropped along with the old column. A straight
    # alter_column(VARCHAR -> UUID) is not valid here regardless, since
    # existing site_id values are not UUIDs.
    op.execute(sa.text("DELETE FROM enrolled_devices"))
    op.drop_column('enrolled_devices', 'site_id')
    op.add_column('enrolled_devices', sa.Column('site_id', sa.UUID(), nullable=False))
    op.create_foreign_key(
        'fk_enrolled_devices_site_id_sites', 'enrolled_devices', 'sites', ['site_id'], ['id']
    )
    # ### end commands ###


def downgrade() -> None:
    """Downgrade schema."""
    # ### commands adjusted by hand - autogenerate cannot express this change ###
    op.drop_constraint('fk_enrolled_devices_site_id_sites', 'enrolled_devices', type_='foreignkey')
    op.drop_column('enrolled_devices', 'site_id')
    op.add_column(
        'enrolled_devices', sa.Column('site_id', sa.VARCHAR(length=128), nullable=False)
    )
    op.drop_table('sites')
    # ### end commands ###
