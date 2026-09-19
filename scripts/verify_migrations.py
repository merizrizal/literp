#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Final

try:
    import psycopg
    from alembic import command
    from alembic.config import Config
    from alembic.script import ScriptDirectory
    from psycopg import sql
except ModuleNotFoundError as error:
    missing_module = error.name or "unknown"
    print(
        f"Missing Python dependency '{missing_module}'. "
        "Install migration dependencies with: python3 -m pip install -r python/requirements.txt",
        file=sys.stderr,
    )
    sys.exit(1)


SEED_MINIMUMS: Final[dict[str, int]] = {
    "unit_of_measure": 4,
    "product": 6,
    "location": 3,
    "inventory_movement": 1,
    "sales_order": 3,
}

POS_COMMAND_LEDGER_COLUMNS: Final[frozenset[str]] = frozenset(
    {
        "command_id",
        "organization_id",
        "actor_subject",
        "operation_id",
        "target_id",
        "idempotency_key",
        "request_fingerprint",
        "response_status",
        "response_payload",
        "created_at",
        "updated_at",
    }
)
POS_COMMAND_LEDGER_CONSTRAINT: Final[str] = "uq_pos_command_ledger_scope_key"
POS_COMMAND_LEDGER_INDEX: Final[str] = "idx_pos_command_ledger_operation_target_created"

POS_SHIFT_INVARIANT_COLUMNS: Final[frozenset[str]] = frozenset(
    {
        "operator_id",
        "currency",
        "expected_cash",
        "cash_variance",
        "closed_by",
        "is_reconcilable",
    }
)
POS_SHIFT_INVARIANT_INDEXES: Final[frozenset[str]] = frozenset(
    {
        "uq_pos_shift_open_terminal",
        "uq_pos_shift_terminal_date_number",
    }
)
POS_SHIFT_INVARIANT_CONSTRAINTS: Final[frozenset[str]] = frozenset(
    {
        "ck_pos_shift_opening_balance_bounds",
        "ck_pos_shift_closing_balance_bounds",
        "ck_pos_shift_currency_format",
        "ck_pos_shift_reconcilable_requires_currency",
    }
)

POS_ORDER_CONTEXT_COLUMNS: Final[frozenset[str]] = frozenset(
    {
        "sales_order_id",
        "shift_id",
        "draft_operator_id",
        "created_at",
    }
)
POS_ORDER_CONTEXT_INDEX: Final[str] = "idx_pos_order_context_shift"
POS_ORDER_CONTEXT_PRIMARY_KEY: Final[str] = "pk_pos_order_context"
POS_ORDER_CONTEXT_FOREIGN_KEYS: Final[frozenset[str]] = frozenset(
    {
        "fk_pos_order_context_order",
        "fk_pos_order_context_shift",
    }
)


class MigrationVerificationError(Exception):
    pass


def resolve_root_dir() -> Path:
    if root_dir := os.environ.get("ROOT_DIR"):
        return Path(root_dir).resolve()

    return Path(__file__).resolve().parents[1]


def require_db_url() -> str:
    if db_url := os.environ.get("DB_URL"):
        return db_url

    raise MigrationVerificationError(
        "DB_URL is required. Source python/database/envrc, "
        "source python/database/envrc.test, or set DB_URL directly."
    )


def load_alembic_config(root_dir: Path) -> Config:
    config_path = root_dir / "python" / "database" / "migration" / "alembic.ini"
    if not config_path.exists():
        raise MigrationVerificationError(f"Alembic config not found: {config_path}")

    return Config(str(config_path))


def get_single_head(config: Config) -> str:
    heads = ScriptDirectory.from_config(config).get_heads()
    if len(heads) != 1:
        raise MigrationVerificationError(
            f"Expected exactly one Alembic head, found: {', '.join(heads)}"
        )

    return heads[0]


def run_migration(config: Config, root_dir: Path, db_url: str) -> None:
    os.environ["ROOT_DIR"] = str(root_dir)
    os.environ["DB_URL"] = db_url

    print("Running Alembic migrations to head...")
    command.upgrade(config, "head")


def verify_pos_command_ledger(cursor) -> None:
    cursor.execute(
        """
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'pos_command_ledger'
        """
    )
    actual_columns = {row[0] for row in cursor.fetchall()}
    missing_columns = POS_COMMAND_LEDGER_COLUMNS - actual_columns
    if missing_columns:
        raise MigrationVerificationError(
            "pos_command_ledger is missing columns: "
            + ", ".join(sorted(missing_columns))
        )

    cursor.execute(
        """
        SELECT 1
        FROM pg_constraint constraint_entry
        JOIN pg_class table_entry ON table_entry.oid = constraint_entry.conrelid
        JOIN pg_namespace schema_entry ON schema_entry.oid = table_entry.relnamespace
        WHERE schema_entry.nspname = 'public'
          AND table_entry.relname = 'pos_command_ledger'
          AND constraint_entry.conname = %s
          AND constraint_entry.contype = 'u'
        """,
        (POS_COMMAND_LEDGER_CONSTRAINT,),
    )
    if cursor.fetchone() is None:
        raise MigrationVerificationError(
            f"Missing POS command-ledger uniqueness constraint: {POS_COMMAND_LEDGER_CONSTRAINT}"
        )

    cursor.execute(
        """
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'pos_command_ledger'
          AND indexname = %s
        """,
        (POS_COMMAND_LEDGER_INDEX,),
    )
    if cursor.fetchone() is None:
        raise MigrationVerificationError(
            f"Missing POS command-ledger index: {POS_COMMAND_LEDGER_INDEX}"
        )


def verify_pos_shift_invariants(cursor) -> None:
    cursor.execute(
        """
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'pos_shift'
        """
    )
    actual_columns = {row[0] for row in cursor.fetchall()}
    missing_columns = POS_SHIFT_INVARIANT_COLUMNS - actual_columns
    if missing_columns:
        raise MigrationVerificationError(
            "pos_shift is missing invariant columns: "
            + ", ".join(sorted(missing_columns))
        )

    cursor.execute(
        """
        SELECT character_maximum_length
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'pos_shift'
          AND column_name = 'operator_id'
        """
    )
    operator_length = cursor.fetchone()
    if operator_length is None or operator_length[0] != 255:
        raise MigrationVerificationError(
            f"Expected pos_shift.operator_id length 255, found {operator_length[0] if operator_length else 'missing'}"
        )

    for index_name in POS_SHIFT_INVARIANT_INDEXES:
        cursor.execute(
            """
            SELECT indexdef
            FROM pg_indexes
            WHERE schemaname = 'public'
              AND tablename = 'pos_shift'
              AND indexname = %s
            """,
            (index_name,),
        )
        index_entry = cursor.fetchone()
        if index_entry is None or "UNIQUE INDEX" not in index_entry[0].upper():
            raise MigrationVerificationError(
                f"Missing unique POS shift index: {index_name}"
            )

    for constraint_name in POS_SHIFT_INVARIANT_CONSTRAINTS:
        cursor.execute(
            """
            SELECT 1
            FROM pg_constraint constraint_entry
            JOIN pg_class table_entry ON table_entry.oid = constraint_entry.conrelid
            JOIN pg_namespace schema_entry ON schema_entry.oid = table_entry.relnamespace
            WHERE schema_entry.nspname = 'public'
              AND table_entry.relname = 'pos_shift'
              AND constraint_entry.conname = %s
              AND constraint_entry.contype = 'c'
            """,
            (constraint_name,),
        )
        if cursor.fetchone() is None:
            raise MigrationVerificationError(
                f"Missing POS shift check constraint: {constraint_name}"
            )


def verify_pos_order_context(cursor) -> None:
    cursor.execute(
        """
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'pos_order_context'
        """
    )
    actual_columns = {row[0] for row in cursor.fetchall()}
    missing_columns = POS_ORDER_CONTEXT_COLUMNS - actual_columns
    if missing_columns:
        raise MigrationVerificationError(
            "pos_order_context is missing columns: "
            + ", ".join(sorted(missing_columns))
        )

    cursor.execute(
        """
        SELECT 1
        FROM pg_constraint constraint_entry
        JOIN pg_class table_entry ON table_entry.oid = constraint_entry.conrelid
        JOIN pg_namespace schema_entry ON schema_entry.oid = table_entry.relnamespace
        WHERE schema_entry.nspname = 'public'
          AND table_entry.relname = 'pos_order_context'
          AND constraint_entry.conname = %s
          AND constraint_entry.contype = 'p'
        """,
        (POS_ORDER_CONTEXT_PRIMARY_KEY,),
    )
    if cursor.fetchone() is None:
        raise MigrationVerificationError(
            f"Missing POS order-context primary key: {POS_ORDER_CONTEXT_PRIMARY_KEY}"
        )

    cursor.execute(
        """
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'pos_order_context'
          AND indexname = %s
        """,
        (POS_ORDER_CONTEXT_INDEX,),
    )
    if cursor.fetchone() is None:
        raise MigrationVerificationError(
            f"Missing POS order-context index: {POS_ORDER_CONTEXT_INDEX}"
        )

    for foreign_key_name in POS_ORDER_CONTEXT_FOREIGN_KEYS:
        cursor.execute(
            """
            SELECT 1
            FROM pg_constraint constraint_entry
            JOIN pg_class table_entry ON table_entry.oid = constraint_entry.conrelid
            JOIN pg_namespace schema_entry ON schema_entry.oid = table_entry.relnamespace
            WHERE schema_entry.nspname = 'public'
              AND table_entry.relname = 'pos_order_context'
              AND constraint_entry.conname = %s
              AND constraint_entry.contype = 'f'
            """,
            (foreign_key_name,),
        )
        if cursor.fetchone() is None:
            raise MigrationVerificationError(
                f"Missing POS order-context foreign key: {foreign_key_name}"
            )


def verify_database(db_url: str, expected_head: str) -> None:
    print(
        "Verifying Alembic head, deterministic seed data, POS ledger schema, shift invariants, and order context..."
    )
    with psycopg.connect(db_url) as connection, connection.cursor() as cursor:
        cursor.execute("SELECT version_num FROM alembic_version")
        row = cursor.fetchone()
        if row is None:
            raise MigrationVerificationError("alembic_version is empty after migration")

        actual_head = row[0]
        if actual_head != expected_head:
            raise MigrationVerificationError(
                f"Expected Alembic head {expected_head}, found {actual_head}"
            )

        for table_name, minimum_count in SEED_MINIMUMS.items():
            query = sql.SQL("SELECT COUNT(*) FROM {}").format(
                sql.Identifier(table_name)
            )
            cursor.execute(query)
            actual_count = cursor.fetchone()[0]
            if actual_count < minimum_count:
                raise MigrationVerificationError(
                    f"Expected at least {minimum_count} rows in {table_name}, "
                    f"found {actual_count}"
                )

        verify_pos_command_ledger(cursor)
        verify_pos_shift_invariants(cursor)
        verify_pos_order_context(cursor)

    print(f"Alembic head verified: {expected_head}")
    for table_name, minimum_count in SEED_MINIMUMS.items():
        print(f"Seed minimum verified: {table_name} >= {minimum_count}")


def main() -> int:
    try:
        root_dir = resolve_root_dir()
        db_url = require_db_url()
        config = load_alembic_config(root_dir)
        expected_head = get_single_head(config)
        run_migration(config, root_dir, db_url)
        verify_database(db_url, expected_head)
    except MigrationVerificationError as error:
        print(f"Migration verification failed: {error}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
