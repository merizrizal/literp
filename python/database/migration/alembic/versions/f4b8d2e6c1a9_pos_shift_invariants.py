"""Add trusted POS shift fields and database invariants.

Existing shifts remain legacy/non-reconcilable until an explicit operator
procedure supplies trusted currency and attribution. New API-opened shifts set
``is_reconcilable`` explicitly and are protected by the same database rules.
"""

import os
import sys
from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

sys.path.insert(0, os.getenv("ROOT_DIR"))
from python.database.migration.alembic.resources import (
    column_exists,
)

# revision identifiers, used by Alembic.
revision: str = "f4b8d2e6c1a9"
down_revision: str | Sequence[str] | None = "f3a7c9e1d5b2"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

SHIFT_MONEY_MAX = "999999999999.99"


def _assert_legacy_rows_are_safe_for_constraints() -> None:
    bind = op.get_context().bind

    duplicate_open = bind.execute(
        sa.text(
            """
            SELECT terminal_id
            FROM pos_shift
            WHERE status = 'OPEN'
            GROUP BY terminal_id
            HAVING COUNT(*) > 1
            LIMIT 1
            """
        )
    ).first()
    if duplicate_open is not None:
        raise RuntimeError(
            "Cannot add the unique open-shift invariant: a terminal has multiple OPEN shifts"
        )

    duplicate_number = bind.execute(
        sa.text(
            """
            SELECT terminal_id, shift_date, shift_number
            FROM pos_shift
            GROUP BY terminal_id, shift_date, shift_number
            HAVING COUNT(*) > 1
            LIMIT 1
            """
        )
    ).first()
    if duplicate_number is not None:
        raise RuntimeError(
            "Cannot add the unique terminal/date/number invariant: duplicate legacy shift numbers exist"
        )

    invalid_legacy_value = bind.execute(
        sa.text(
            f"""
            SELECT shift_id
            FROM pos_shift
            WHERE shift_number < 1
               OR opening_balance < 0
               OR opening_balance > {SHIFT_MONEY_MAX}
               OR (closing_balance IS NOT NULL AND (
                    closing_balance < 0 OR closing_balance > {SHIFT_MONEY_MAX}
               ))
            LIMIT 1
            """
        )
    ).first()
    if invalid_legacy_value is not None:
        raise RuntimeError(
            "Cannot add POS shift amount/number invariants: invalid legacy shift data exists"
        )


def upgrade() -> None:
    """Add trusted shift metadata without rewriting historical shift state."""
    _assert_legacy_rows_are_safe_for_constraints()

    op.alter_column(
        "pos_shift",
        "operator_id",
        existing_type=sa.String(36),
        type_=sa.String(255),
        existing_nullable=False,
    )

    if not column_exists("pos_shift", "currency"):
        op.add_column("pos_shift", sa.Column("currency", sa.String(3), nullable=True))
    if not column_exists("pos_shift", "expected_cash"):
        op.add_column(
            "pos_shift", sa.Column("expected_cash", sa.Numeric(14, 2), nullable=True)
        )
    if not column_exists("pos_shift", "cash_variance"):
        op.add_column(
            "pos_shift", sa.Column("cash_variance", sa.Numeric(14, 2), nullable=True)
        )
    if not column_exists("pos_shift", "closed_by"):
        op.add_column(
            "pos_shift", sa.Column("closed_by", sa.String(255), nullable=True)
        )
    if not column_exists("pos_shift", "is_reconcilable"):
        op.add_column(
            "pos_shift",
            sa.Column(
                "is_reconcilable",
                sa.Boolean,
                nullable=False,
                server_default=sa.text("false"),
            ),
        )

    op.create_check_constraint(
        "ck_pos_shift_opening_balance_bounds",
        "pos_shift",
        f"opening_balance >= 0 AND opening_balance <= {SHIFT_MONEY_MAX}",
    )
    op.create_check_constraint(
        "ck_pos_shift_closing_balance_bounds",
        "pos_shift",
        f"closing_balance IS NULL OR (closing_balance >= 0 AND closing_balance <= {SHIFT_MONEY_MAX})",
    )
    op.create_check_constraint(
        "ck_pos_shift_currency_format",
        "pos_shift",
        "currency IS NULL OR currency ~ '^[A-Z]{3}$'",
    )
    op.create_check_constraint(
        "ck_pos_shift_reconcilable_requires_currency",
        "pos_shift",
        "NOT is_reconcilable OR currency IS NOT NULL",
    )
    op.create_index(
        "uq_pos_shift_open_terminal",
        "pos_shift",
        ["terminal_id"],
        unique=True,
        postgresql_where=sa.text("status = 'OPEN'"),
    )
    op.create_index(
        "uq_pos_shift_terminal_date_number",
        "pos_shift",
        ["terminal_id", "shift_date", "shift_number"],
        unique=True,
    )


def downgrade() -> None:
    """Remove only the shift invariants introduced by this revision."""
    op.drop_index("uq_pos_shift_terminal_date_number", table_name="pos_shift")
    op.drop_index("uq_pos_shift_open_terminal", table_name="pos_shift")
    op.drop_constraint(
        "ck_pos_shift_reconcilable_requires_currency", "pos_shift", type_="check"
    )
    op.drop_constraint("ck_pos_shift_currency_format", "pos_shift", type_="check")
    op.drop_constraint(
        "ck_pos_shift_closing_balance_bounds", "pos_shift", type_="check"
    )
    op.drop_constraint(
        "ck_pos_shift_opening_balance_bounds", "pos_shift", type_="check"
    )
    op.drop_column("pos_shift", "is_reconcilable")
    op.drop_column("pos_shift", "closed_by")
    op.drop_column("pos_shift", "cash_variance")
    op.drop_column("pos_shift", "expected_cash")
    op.drop_column("pos_shift", "currency")
    op.alter_column(
        "pos_shift",
        "operator_id",
        existing_type=sa.String(255),
        type_=sa.String(36),
        existing_nullable=False,
    )
