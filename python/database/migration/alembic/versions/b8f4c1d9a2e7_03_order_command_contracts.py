"""03. Order command contracts for idempotency, audit, and order numbers

Revision ID: b8f4c1d9a2e7
Revises: acf82479ef78
Create Date: 2026-06-24 00:00:00.000000

Schema-only migration for Phase 03 hardening.
Adds persistence contracts for idempotent order commands, order audit events,
and database-backed order number generation.
"""
import os
import sys
from typing import Sequence, Union

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

sys.path.insert(0, os.getenv('ROOT_DIR'))
from python.database.migration.alembic.resources import (  # noqa: E402
    create_sequence_if_not_exists,
    create_table_if_not_exists,
    delete_sequence_if_exists,
)

# revision identifiers, used by Alembic.
revision: str = 'b8f4c1d9a2e7'
down_revision: Union[str, Sequence[str], None] = 'acf82479ef78'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


ORDER_STATUS = postgresql.ENUM(
    'DRAFT', 'CONFIRMED', 'FULFILLED', 'CANCELLED',
    name='order_status',
    create_type=False,
)


def upgrade() -> None:
    """Upgrade schema - add contracts for command idempotency and auditability."""

    create_sequence_if_not_exists('sales_order_number_seq')

    create_table_if_not_exists(
        'order_command_idempotency',
        sa.Column('idempotency_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('sales_order_id', sa.String(36), sa.ForeignKey('sales_order.sales_order_id', ondelete='CASCADE'), nullable=False),
        sa.Column('command_name', sa.String(50), nullable=False),
        sa.Column('idempotency_key', sa.String(255), nullable=False),
        sa.Column('request_fingerprint', sa.String(128), nullable=True),
        sa.Column('response_status', sa.Integer, nullable=True),
        sa.Column('response_payload', sa.JSON, nullable=True),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint(
            'sales_order_id', 'command_name', 'idempotency_key',
            name='uq_order_command_idempotency_order_command_key',
        ),
        sa.Index(
            'idx_order_command_idempotency_order_command_created',
            'sales_order_id', 'command_name', 'created_at',
        ),
    )

    create_table_if_not_exists(
        'sales_order_event',
        sa.Column('event_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('sales_order_id', sa.String(36), sa.ForeignKey('sales_order.sales_order_id', ondelete='CASCADE'), nullable=False),
        sa.Column('event_type', sa.String(50), nullable=False),
        sa.Column('previous_status', ORDER_STATUS, nullable=True),
        sa.Column('new_status', ORDER_STATUS, nullable=True),
        sa.Column('command_name', sa.String(50), nullable=True),
        sa.Column('idempotency_key', sa.String(255), nullable=True),
        sa.Column('notes', sa.Text, nullable=True),
        sa.Column('created_by', sa.String(255), nullable=True),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Index('idx_sales_order_event_order_created', 'sales_order_id', 'created_at'),
    )


def downgrade() -> None:
    """Downgrade schema - remove order command support contracts."""

    op.drop_table('sales_order_event')
    op.drop_table('order_command_idempotency')
    delete_sequence_if_exists('sales_order_number_seq')
