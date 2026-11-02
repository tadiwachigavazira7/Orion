"""enrollment authorizations, PENDING_ENROLLMENT status, nullable credential_hash

Revision ID: 7c3e9a41b2d6
Revises: d2bdced50673
Create Date: 2026-11-02 12:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '7c3e9a41b2d6'
down_revision: Union[str, Sequence[str], None] = 'd2bdced50673'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # ALTER TYPE ... ADD VALUE cannot run inside a transaction block on
    # PostgreSQL < 12, and even on 12+ the new value is unusable until the
    # adding transaction commits. Run it in its own autocommit block; IF NOT
    # EXISTS keeps a re-run after a later failure in this migration safe.
    with op.get_context().autocommit_block():
        op.execute("ALTER TYPE device_status ADD VALUE IF NOT EXISTS 'PENDING_ENROLLMENT'")

    # NULL exactly when a device is PENDING_ENROLLMENT after an operator reset.
    op.alter_column(
        'enrolled_devices',
        'credential_hash',
        existing_type=sa.String(length=512),
        nullable=True,
    )

    op.create_table(
        'enrollment_authorizations',
        sa.Column('id', sa.UUID(), nullable=False),
        sa.Column('organization_id', sa.UUID(), nullable=False),
        sa.Column('site_id', sa.UUID(), nullable=False),
        sa.Column('device_id', sa.String(length=128), nullable=False),
        sa.Column('secret_hash', sa.String(length=64), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('consumed_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('invalidated_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.ForeignKeyConstraint(['organization_id'], ['organizations.id'], ),
        sa.ForeignKeyConstraint(['site_id'], ['sites.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        op.f('ix_enrollment_authorizations_secret_hash'),
        'enrollment_authorizations',
        ['secret_hash'],
        unique=True,
    )
    # At most one outstanding (unconsumed, not invalidated) authorization per device.
    op.create_index(
        'uq_enrollment_authorizations_outstanding_device_id',
        'enrollment_authorizations',
        ['device_id'],
        unique=True,
        postgresql_where=sa.text('consumed_at IS NULL AND invalidated_at IS NULL'),
    )


def downgrade() -> None:
    """Downgrade schema.

    Refuses (rather than silently deleting or inventing data) if any device
    is PENDING_ENROLLMENT or has no credential hash, since neither can be
    represented by the previous schema. Reset devices must be re-enrolled or
    removed deliberately before downgrading.
    """
    bind = op.get_bind()
    blocking = bind.execute(
        sa.text(
            "SELECT count(*) FROM enrolled_devices "
            "WHERE status = 'PENDING_ENROLLMENT' OR credential_hash IS NULL"
        )
    ).scalar_one()
    if blocking:
        raise RuntimeError(
            f"Cannot downgrade: {blocking} enrolled_devices row(s) are PENDING_ENROLLMENT "
            "or have no credential_hash, which the previous schema cannot represent."
        )

    op.drop_index('uq_enrollment_authorizations_outstanding_device_id', table_name='enrollment_authorizations')
    op.drop_index(op.f('ix_enrollment_authorizations_secret_hash'), table_name='enrollment_authorizations')
    op.drop_table('enrollment_authorizations')

    op.alter_column(
        'enrolled_devices',
        'credential_hash',
        existing_type=sa.String(length=512),
        nullable=False,
    )

    # PostgreSQL cannot drop an enum value; recreate the type without it.
    op.execute("ALTER TYPE device_status RENAME TO device_status_old")
    op.execute("CREATE TYPE device_status AS ENUM ('ACTIVE', 'REVOKED')")
    op.execute(
        "ALTER TABLE enrolled_devices ALTER COLUMN status TYPE device_status "
        "USING status::text::device_status"
    )
    op.execute("DROP TYPE device_status_old")
