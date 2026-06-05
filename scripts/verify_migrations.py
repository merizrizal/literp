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


def verify_database(db_url: str, expected_head: str) -> None:
    print("Verifying Alembic head and deterministic seed data...")
    with psycopg.connect(db_url) as connection:
        with connection.cursor() as cursor:
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
                query = sql.SQL("SELECT COUNT(*) FROM {}").format(sql.Identifier(table_name))
                cursor.execute(query)
                actual_count = cursor.fetchone()[0]
                if actual_count < minimum_count:
                    raise MigrationVerificationError(
                        f"Expected at least {minimum_count} rows in {table_name}, "
                        f"found {actual_count}"
                    )

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
