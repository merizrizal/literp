"""Add trusted POS payment-to-shift attribution storage."""

import os
import sys
from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

sys.path.insert(0, os.getenv("ROOT_DIR"))
from python.database.migration.alembic.resources import (
    create_index_if_not_exists,
    create_table_if_not_exists,
    delete_index_if_exists,
)

# revision identifiers, used by Alembic.
revision: str = "b2d6f8a1c4e3"
down_revision: str | Sequence[str] | None = "a9c7e1d5b2f4"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create immutable payment-to-shift attribution storage."""
    create_table_if_not_exists(
        "pos_payment_context",
        sa.Column("payment_id", sa.String(36), nullable=False),
        sa.Column("shift_id", sa.String(36), nullable=False),
        sa.Column("capture_operator_id", sa.String(255), nullable=False),
        sa.Column("created_at", sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint("payment_id", name="pk_pos_payment_context"),
        sa.ForeignKeyConstraint(
            ["payment_id"],
            ["payment.payment_id"],
            name="fk_pos_payment_context_payment",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["shift_id"],
            ["pos_shift.shift_id"],
            name="fk_pos_payment_context_shift",
            ondelete="RESTRICT",
        ),
    )
    create_index_if_not_exists(
        "idx_pos_payment_context_shift",
        "pos_payment_context",
        ["shift_id"],
    )


def downgrade() -> None:
    """Remove immutable payment-to-shift attribution storage."""
    delete_index_if_exists("idx_pos_payment_context_shift", "pos_payment_context")
    op.drop_table("pos_payment_context")
