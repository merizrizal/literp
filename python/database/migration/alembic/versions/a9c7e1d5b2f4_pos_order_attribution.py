"""Add immutable POS order-to-shift attribution."""

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
revision: str = "a9c7e1d5b2f4"
down_revision: str | Sequence[str] | None = "f4b8d2e6c1a9"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create the immutable order-to-shift attribution table."""
    create_table_if_not_exists(
        "pos_order_context",
        sa.Column("sales_order_id", sa.String(36), nullable=False),
        sa.Column("shift_id", sa.String(36), nullable=False),
        sa.Column("draft_operator_id", sa.String(255), nullable=False),
        sa.Column("created_at", sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint("sales_order_id", name="pk_pos_order_context"),
        sa.ForeignKeyConstraint(
            ["sales_order_id"],
            ["sales_order.sales_order_id"],
            name="fk_pos_order_context_order",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["shift_id"],
            ["pos_shift.shift_id"],
            name="fk_pos_order_context_shift",
            ondelete="RESTRICT",
        ),
    )
    create_index_if_not_exists(
        "idx_pos_order_context_shift",
        "pos_order_context",
        ["shift_id"],
    )


def downgrade() -> None:
    """Remove immutable POS order-to-shift attribution."""
    delete_index_if_exists("idx_pos_order_context_shift", "pos_order_context")
    op.drop_table("pos_order_context")
