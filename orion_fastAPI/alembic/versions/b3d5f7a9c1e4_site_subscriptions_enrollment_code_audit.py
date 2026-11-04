"""per-site subscriptions, enrollment code, generated max_devices, audit_events

Revision ID: b3d5f7a9c1e4
Revises: a1c3e5f7b9d2
Create Date: 2026-11-04 12:00:00.000000

Inserts NO subscription rows and touches NO device rows: existing sites end up
without a subscription (and without an enrollment code) and are therefore not
authorized until an operator provisions them.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = 'b3d5f7a9c1e4'
down_revision: Union[str, Sequence[str], None] = 'a1c3e5f7b9d2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'sites', sa.Column('included_device_slots', sa.Integer(), server_default='6', nullable=False)
    )
    op.create_check_constraint(
        'ck_sites_included_device_slots_nonneg', 'sites', 'included_device_slots >= 0'
    )
    # GENERATED ALWAYS AS (...) STORED
    op.add_column(
        'sites',
        sa.Column(
            'max_devices',
            sa.Integer(),
            sa.Computed('included_device_slots + extra_device_slots', persisted=True),
            nullable=False,
        ),
    )
    op.add_column('sites', sa.Column('enrollment_code_hash', sa.String(length=512), nullable=True))

    op.create_table(
        'subscriptions',
        sa.Column('id', sa.UUID(), nullable=False),
        sa.Column('site_id', sa.UUID(), nullable=False),
        sa.Column('plan_code', sa.Enum('STANDARD', 'PILOT', name='subscription_plan'), nullable=False),
        sa.Column(
            'status',
            sa.Enum('PENDING', 'ACTIVE', 'SUSPENDED', 'EXPIRED', 'CANCELED', name='subscription_status'),
            nullable=False,
        ),
        sa.Column('monthly_price_cents', sa.Integer(), server_default='40000', nullable=False),
        sa.Column('current_period_start', sa.DateTime(timezone=True), nullable=True),
        sa.Column('current_period_end', sa.DateTime(timezone=True), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.CheckConstraint('monthly_price_cents >= 0', name='ck_subscriptions_monthly_price_nonneg'),
        sa.CheckConstraint(
            'current_period_end IS NULL OR current_period_start IS NULL '
            'OR current_period_end > current_period_start',
            name='ck_subscriptions_period_order',
        ),
        sa.ForeignKeyConstraint(['site_id'], ['sites.id']),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('site_id'),
    )
    op.create_index('ix_subscriptions_status', 'subscriptions', ['status'])

    op.create_table(
        'audit_events',
        sa.Column('id', sa.UUID(), nullable=False),
        sa.Column('occurred_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('actor', sa.String(length=128), nullable=False),
        sa.Column('action', sa.String(length=64), nullable=False),
        sa.Column('site_id', sa.UUID(), nullable=True),
        sa.Column(
            'details', postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"), nullable=False,
        ),
        sa.ForeignKeyConstraint(['site_id'], ['sites.id']),
        sa.PrimaryKeyConstraint('id'),
    )


def downgrade() -> None:
    op.drop_table('audit_events')
    op.drop_index('ix_subscriptions_status', table_name='subscriptions')
    op.drop_table('subscriptions')
    sa.Enum(name='subscription_status').drop(op.get_bind(), checkfirst=True)
    sa.Enum(name='subscription_plan').drop(op.get_bind(), checkfirst=True)
    op.drop_column('sites', 'enrollment_code_hash')
    op.drop_column('sites', 'max_devices')
    op.drop_constraint('ck_sites_included_device_slots_nonneg', 'sites', type_='check')
    op.drop_column('sites', 'included_device_slots')
