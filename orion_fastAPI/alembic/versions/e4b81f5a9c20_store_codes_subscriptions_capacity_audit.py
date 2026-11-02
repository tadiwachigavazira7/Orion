"""store enrollment codes, site subscriptions/capacity, rate limiting, audit; drop enrollment_authorizations

Revision ID: e4b81f5a9c20
Revises: 7c3e9a41b2d6
Create Date: 2026-11-02 15:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


# revision identifiers, used by Alembic.
revision: str = 'e4b81f5a9c20'
down_revision: Union[str, Sequence[str], None] = '7c3e9a41b2d6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

subscription_status = postgresql.ENUM(
    'INACTIVE', 'PILOT', 'ACTIVE', 'SUSPENDED', name='subscription_status', create_type=False
)


def upgrade() -> None:
    """Upgrade schema."""
    subscription_status.create(op.get_bind(), checkfirst=True)

    op.create_table(
        'site_subscriptions',
        sa.Column('id', sa.UUID(), nullable=False),
        sa.Column('site_id', sa.UUID(), nullable=False),
        sa.Column('status', subscription_status, nullable=False),
        sa.Column('included_device_slots', sa.Integer(), server_default='6', nullable=False),
        sa.Column('additional_device_slots', sa.Integer(), server_default='0', nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('status_changed_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.CheckConstraint('included_device_slots >= 0', name='ck_site_subscriptions_included_nonnegative'),
        sa.CheckConstraint('additional_device_slots >= 0', name='ck_site_subscriptions_additional_nonnegative'),
        sa.ForeignKeyConstraint(['site_id'], ['sites.id'], ),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('site_id'),
    )
    # User decision: every existing site becomes a PILOT with 6 included and
    # 0 additional slots.
    op.execute(
        "INSERT INTO site_subscriptions "
        "(id, site_id, status, included_device_slots, additional_device_slots, "
        " created_at, updated_at, status_changed_at) "
        "SELECT gen_random_uuid(), id, 'PILOT', 6, 0, now(), now(), now() FROM sites"
    )

    # Store enrollment code (Argon2id hash only). NULL until first rotation.
    op.add_column('sites', sa.Column('enrollment_code_hash', sa.String(length=512), nullable=True))
    op.add_column('sites', sa.Column('enrollment_code_rotated_at', sa.DateTime(timezone=True), nullable=True))

    op.create_table(
        'enrollment_attempt_failures',
        sa.Column('id', sa.UUID(), nullable=False),
        sa.Column('site_id', sa.UUID(), nullable=False),
        sa.Column('client_ip', sa.String(length=64), nullable=False),
        sa.Column('attempted_at', sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(['site_id'], ['sites.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        'ix_enrollment_attempt_failures_site_attempted',
        'enrollment_attempt_failures',
        ['site_id', 'attempted_at'],
    )
    op.create_index(
        'ix_enrollment_attempt_failures_site_client_attempted',
        'enrollment_attempt_failures',
        ['site_id', 'client_ip', 'attempted_at'],
    )

    op.create_table(
        'admin_audit_events',
        sa.Column('id', sa.UUID(), nullable=False),
        sa.Column('occurred_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('actor', sa.String(length=255), nullable=False),
        sa.Column('actor_source', sa.String(length=16), nullable=False),
        sa.Column('action', sa.String(length=64), nullable=False),
        sa.Column('organization_id', sa.UUID(), nullable=True),
        sa.Column('site_id', sa.UUID(), nullable=True),
        sa.Column('device_id', sa.String(length=128), nullable=True),
        sa.Column('before', postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column('after', postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.ForeignKeyConstraint(['organization_id'], ['organizations.id'], ),
        sa.ForeignKeyConstraint(['site_id'], ['sites.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        op.f('ix_admin_audit_events_occurred_at'), 'admin_audit_events', ['occurred_at'], unique=False
    )

    # v2 per-device single-use authorizations are replaced by store codes.
    # Outstanding authorizations are discarded: they cannot be converted.
    op.drop_index('uq_enrollment_authorizations_outstanding_device_id', table_name='enrollment_authorizations')
    op.drop_index(op.f('ix_enrollment_authorizations_secret_hash'), table_name='enrollment_authorizations')
    op.drop_table('enrollment_authorizations')


def downgrade() -> None:
    """Downgrade schema.

    Recreates an empty enrollment_authorizations table (v2 schema). Store
    codes, subscriptions/capacity, rate-limit history and the audit trail
    are dropped and cannot be recovered.
    """
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
    op.create_index(
        'uq_enrollment_authorizations_outstanding_device_id',
        'enrollment_authorizations',
        ['device_id'],
        unique=True,
        postgresql_where=sa.text('consumed_at IS NULL AND invalidated_at IS NULL'),
    )

    op.drop_index(op.f('ix_admin_audit_events_occurred_at'), table_name='admin_audit_events')
    op.drop_table('admin_audit_events')
    op.drop_index('ix_enrollment_attempt_failures_site_client_attempted', table_name='enrollment_attempt_failures')
    op.drop_index('ix_enrollment_attempt_failures_site_attempted', table_name='enrollment_attempt_failures')
    op.drop_table('enrollment_attempt_failures')
    op.drop_column('sites', 'enrollment_code_rotated_at')
    op.drop_column('sites', 'enrollment_code_hash')
    op.drop_table('site_subscriptions')
    subscription_status.drop(op.get_bind(), checkfirst=True)
