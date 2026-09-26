"""Add the durable command ledger used by terminal administration.

The ledger scopes idempotency to the verified organization, actor, operation,
and target. A terminal-create target is its authorized location; later POS
commands may use their own target identity without changing this contract.
"""
import os
import sys
from typing import Sequence, Union

import sqlalchemy as sa

from alembic import op

sys.path.insert(0, os.getenv('ROOT_DIR'))
from python.database.migration.alembic.resources import (  # noqa: E402
    create_table_if_not_exists,
)

# revision identifiers, used by Alembic.
revision: str = 'f3a7c9e1d5b2'
down_revision: Union[str, Sequence[str], None] = 'e6d9a7c1b2f4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Create the POS command ledger without altering existing POS history."""
    create_table_if_not_exists(
        'pos_command_ledger',
        sa.Column('command_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('organization_id', sa.String(255), nullable=False),
        sa.Column('actor_subject', sa.String(255), nullable=False),
        sa.Column('operation_id', sa.String(100), nullable=False),
        sa.Column('target_id', sa.String(255), nullable=False),
        sa.Column('idempotency_key', sa.String(255), nullable=False),
        sa.Column('request_fingerprint', sa.String(128), nullable=False),
        sa.Column('response_status', sa.Integer, nullable=True),
        sa.Column('response_payload', sa.JSON, nullable=True),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint(
            'organization_id',
            'actor_subject',
            'operation_id',
            'target_id',
            'idempotency_key',
            name='uq_pos_command_ledger_scope_key',
        ),
        sa.Index(
            'idx_pos_command_ledger_operation_target_created',
            'operation_id',
            'target_id',
            'created_at',
        ),
    )


def downgrade() -> None:
    """Remove only the command ledger introduced by this revision."""
    op.drop_table('pos_command_ledger')
