"""Add legacy-safe receipt issuance metadata and an append-only refund ledger."""
from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "c7a8e2f4d6b1"
down_revision: str | Sequence[str] | None = "b2d6f8a1c4e3"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

RECEIPT_TOTAL_ITEMS_MAX = "999999999.999"
REFUND_AMOUNT_MAX = "999999999999.99"


def _assert_legacy_receipt_totals_are_safe() -> None:
    invalid_receipt = op.get_context().bind.execute(
        sa.text(
            """
            SELECT receipt_id
            FROM receipt
            WHERE total_items < 0 OR total_items > 999999999
            LIMIT 1
            """
        )
    ).first()
    if invalid_receipt is not None:
        raise RuntimeError(
            "Cannot convert legacy receipt totals to numeric(12,3): "
            "a total_items value is outside the supported range"
        )


def _assert_downgrade_preserves_financial_history() -> None:
    bind = op.get_context().bind
    if bind.execute(
        sa.text("SELECT EXISTS (SELECT 1 FROM pos_receipt_refund)")
    ).scalar():
        raise RuntimeError(
            "Cannot downgrade receipt/refund storage while refund history exists"
        )

    if bind.execute(
        sa.text(
            """
            SELECT EXISTS (
                SELECT 1
                FROM receipt
                WHERE is_api_issued OR currency IS NOT NULL OR issued_by IS NOT NULL
            )
            """
        )
    ).scalar():
        raise RuntimeError(
            "Cannot downgrade receipt/refund storage while issuance metadata exists"
        )

    if bind.execute(
        sa.text(
            """
            SELECT EXISTS (
                SELECT 1
                FROM receipt
                WHERE total_items <> trunc(total_items)
            )
            """
        )
    ).scalar():
        raise RuntimeError(
            "Cannot downgrade receipt totals while fractional item quantities exist"
        )

    sequence_state = bind.execute(
        sa.text("SELECT last_value, is_called FROM pos_receipt_number_seq")
    ).one()
    if sequence_state.is_called:
        raise RuntimeError(
            "Cannot downgrade receipt numbering after the receipt sequence "
            "has been used"
        )


def _initialize_receipt_number_sequence() -> None:
    maximum_existing_suffix = op.get_context().bind.execute(
        sa.text(
            """
            SELECT MAX(
                substring(receipt_number FROM '^RCPT-[0-9]{8}-([0-9]+)$')::numeric
            )
            FROM receipt
            WHERE receipt_number ~ '^RCPT-[0-9]{8}-[0-9]+$'
            """
        )
    ).scalar()
    if maximum_existing_suffix is None:
        return
    if maximum_existing_suffix >= 9223372036854775807:
        raise RuntimeError(
            "Cannot initialize receipt numbering beyond the PostgreSQL sequence range"
        )

    op.get_context().bind.execute(
        sa.text("SELECT setval('pos_receipt_number_seq', :next_value, false)"),
        {"next_value": int(maximum_existing_suffix) + 1},
    )


def upgrade() -> None:
    """Add receipt issuance metadata and durable refund storage."""
    _assert_legacy_receipt_totals_are_safe()

    op.alter_column(
        "receipt",
        "total_items",
        existing_type=sa.Integer(),
        type_=sa.Numeric(12, 3),
        existing_nullable=False,
        postgresql_using="total_items::numeric(12, 3)",
    )
    op.add_column("receipt", sa.Column("currency", sa.String(3), nullable=True))
    op.add_column("receipt", sa.Column("issued_by", sa.String(255), nullable=True))
    op.add_column(
        "receipt",
        sa.Column(
            "is_api_issued",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("false"),
        ),
    )

    op.create_check_constraint(
        "ck_receipt_total_items_bounds",
        "receipt",
        f"total_items >= 0 AND total_items <= {RECEIPT_TOTAL_ITEMS_MAX}",
    )
    op.create_check_constraint(
        "ck_receipt_currency_format",
        "receipt",
        "currency IS NULL OR currency ~ '^[A-Z]{3}$'",
    )
    op.create_check_constraint(
        "ck_receipt_api_issued_metadata",
        "receipt",
        (
            "NOT is_api_issued OR (currency IS NOT NULL AND "
            "issued_by IS NOT NULL AND length(btrim(issued_by)) > 0)"
        ),
    )
    op.create_index(
        "uq_receipt_api_issued_order",
        "receipt",
        ["sales_order_id"],
        unique=True,
        postgresql_where=sa.text("is_api_issued IS TRUE"),
    )

    op.execute(
        "CREATE SEQUENCE pos_receipt_number_seq AS BIGINT "
        "START WITH 1 INCREMENT BY 1 NO CYCLE"
    )
    _initialize_receipt_number_sequence()

    op.create_table(
        "pos_receipt_refund",
        sa.Column("refund_id", sa.String(36), nullable=False),
        sa.Column("receipt_id", sa.String(36), nullable=False),
        sa.Column("payment_id", sa.String(36), nullable=False),
        sa.Column("refund_shift_id", sa.String(36), nullable=False),
        sa.Column("actor_subject", sa.String(255), nullable=False),
        sa.Column("reason", sa.String(500), nullable=False),
        sa.Column("amount", sa.Numeric(14, 2), nullable=False),
        sa.Column("currency", sa.String(3), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("refund_id", name="pk_pos_receipt_refund"),
        sa.ForeignKeyConstraint(
            ["receipt_id"],
            ["receipt.receipt_id"],
            name="fk_pos_receipt_refund_receipt",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["payment_id"],
            ["payment.payment_id"],
            name="fk_pos_receipt_refund_payment",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["refund_shift_id"],
            ["pos_shift.shift_id"],
            name="fk_pos_receipt_refund_shift",
            ondelete="RESTRICT",
        ),
        sa.CheckConstraint(
            f"amount > 0 AND amount <= {REFUND_AMOUNT_MAX}",
            name="ck_pos_receipt_refund_amount_bounds",
        ),
        sa.CheckConstraint(
            "currency ~ '^[A-Z]{3}$'",
            name="ck_pos_receipt_refund_currency_format",
        ),
        sa.CheckConstraint(
            "length(btrim(reason)) > 0",
            name="ck_pos_receipt_refund_reason_nonblank",
        ),
    )
    op.create_index(
        "idx_pos_receipt_refund_receipt_created",
        "pos_receipt_refund",
        ["receipt_id", "created_at"],
    )
    op.create_index(
        "idx_pos_receipt_refund_payment_created",
        "pos_receipt_refund",
        ["payment_id", "created_at"],
    )
    op.create_index(
        "idx_pos_receipt_refund_shift_created",
        "pos_receipt_refund",
        ["refund_shift_id", "created_at"],
    )
    op.execute(
        """
        CREATE FUNCTION reject_pos_receipt_refund_mutation()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            RAISE EXCEPTION 'POS receipt refund rows are append-only'
                USING ERRCODE = '55000';
            RETURN NULL;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_pos_receipt_refund_immutable
        BEFORE UPDATE OR DELETE ON pos_receipt_refund
        FOR EACH ROW EXECUTE FUNCTION reject_pos_receipt_refund_mutation()
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_pos_receipt_refund_no_truncate
        BEFORE TRUNCATE ON pos_receipt_refund
        FOR EACH STATEMENT EXECUTE FUNCTION reject_pos_receipt_refund_mutation()
        """
    )


def downgrade() -> None:
    """Remove only unused storage; preserve issued receipts and refund history."""
    _assert_downgrade_preserves_financial_history()

    op.drop_table("pos_receipt_refund")
    op.execute("DROP FUNCTION reject_pos_receipt_refund_mutation()")
    op.execute("DROP SEQUENCE pos_receipt_number_seq")
    op.drop_index("uq_receipt_api_issued_order", table_name="receipt")
    op.drop_constraint("ck_receipt_api_issued_metadata", "receipt", type_="check")
    op.drop_constraint("ck_receipt_currency_format", "receipt", type_="check")
    op.drop_constraint("ck_receipt_total_items_bounds", "receipt", type_="check")
    op.alter_column(
        "receipt",
        "total_items",
        existing_type=sa.Numeric(12, 3),
        type_=sa.Integer(),
        existing_nullable=False,
        postgresql_using="total_items::integer",
    )
    op.drop_column("receipt", "is_api_issued")
    op.drop_column("receipt", "issued_by")
    op.drop_column("receipt", "currency")
