#!/usr/bin/env python3
"""Exercise POS downgrades in databases created solely by this test.

Set LITERP_MIGRATION_TEST_ADMIN_URL to a disposable loopback PostgreSQL cluster's
postgres database. No existing application database is migrated or dropped.
"""

from __future__ import annotations

import os
import threading
import time
from pathlib import Path
import unittest
from urllib.parse import urlsplit, urlunsplit
from uuid import uuid4

import psycopg
from psycopg import sql
from alembic import command
from alembic.config import Config


class PosDowngradeSafetyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.admin_url = os.environ["LITERP_MIGRATION_TEST_ADMIN_URL"]
        parsed = urlsplit(self.admin_url)
        if (
            parsed.hostname not in {"127.0.0.1", "localhost"}
            or parsed.path != "/postgres"
        ):
            raise ValueError(
                "Use a disposable loopback PostgreSQL cluster's postgres database"
            )
        self.database = "literp_downgrade_test_" + uuid4().hex
        self.db_url = urlunsplit(parsed._replace(path="/" + self.database))
        with psycopg.connect(self.admin_url, autocommit=True) as connection:
            connection.execute(
                sql.SQL("CREATE DATABASE {}").format(sql.Identifier(self.database))
            )
        self.addCleanup(self.drop_database)
        root = Path(__file__).resolve().parents[1]
        self.config = Config(str(root / "python/database/migration/alembic.ini"))
        previous = {key: os.environ.get(key) for key in ("DB_URL", "ROOT_DIR")}
        self.addCleanup(self.restore_environment, previous)
        os.environ.update(DB_URL=self.db_url, ROOT_DIR=str(root))

    @staticmethod
    def restore_environment(previous: dict[str, str | None]) -> None:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    def drop_database(self) -> None:
        with psycopg.connect(self.admin_url, autocommit=True) as connection:
            connection.execute(
                sql.SQL("DROP DATABASE {} WITH (FORCE)").format(
                    sql.Identifier(self.database)
                )
            )

    def execute(self, statement: str) -> None:
        with psycopg.connect(self.db_url) as connection:
            connection.execute(statement)

    def query(self, statement: str) -> list:
        with psycopg.connect(self.db_url) as connection:
            return connection.execute(statement).fetchall()

    def assert_refused(
        self, revision: str, history_query: str, target: str = "-1"
    ) -> None:
        before = self.query(history_query)
        self.assertTrue(before, "The financial-history fixture must not be empty")
        with self.assertRaisesRegex(RuntimeError, "Cannot downgrade"):
            command.downgrade(self.config, target)
        self.assertEqual(
            [(revision,)], self.query("SELECT version_num FROM alembic_version")
        )
        self.assertEqual(
            before, self.query(history_query), "Refusal must preserve history"
        )

    def test_payment_attribution_refuses_downgrade(self) -> None:
        command.upgrade(self.config, "b2d6f8a1c4e3")
        self.execute("""
            INSERT INTO pos_payment_context (payment_id, shift_id, capture_operator_id)
            SELECT p.payment_id, s.shift_id, 'capture-owner'
            FROM payment p CROSS JOIN pos_shift s LIMIT 1
        """)
        self.assert_refused("b2d6f8a1c4e3", "SELECT * FROM pos_payment_context")

    def test_order_attribution_refuses_downgrade(self) -> None:
        command.upgrade(self.config, "a9c7e1d5b2f4")
        self.execute("""
            INSERT INTO pos_order_context (sales_order_id, shift_id, draft_operator_id)
            SELECT o.sales_order_id, s.shift_id, 'draft-owner'
            FROM sales_order o CROSS JOIN pos_shift s LIMIT 1
        """)
        self.assert_refused("a9c7e1d5b2f4", "SELECT * FROM pos_order_context")

    def test_reconciliation_history_refuses_downgrade(self) -> None:
        command.upgrade(self.config, "f4b8d2e6c1a9")
        self.execute("""
            UPDATE pos_shift SET currency = 'USD', is_reconcilable = true,
                expected_cash = 12.34, cash_variance = -1.00, closed_by = 'verified-closer'
            WHERE shift_id = (SELECT shift_id FROM pos_shift LIMIT 1)
        """)
        self.assert_refused("f4b8d2e6c1a9", "SELECT * FROM pos_shift ORDER BY shift_id")

    def test_each_trusted_shift_field_refuses_downgrade(self) -> None:
        command.upgrade(self.config, "f4b8d2e6c1a9")
        for assignment in (
            "currency = 'USD'",
            "expected_cash = 0",
            "cash_variance = 0",
            "closed_by = 'closer'",
        ):
            with self.subTest(assignment=assignment):
                self.execute("UPDATE pos_shift SET " + assignment)
                self.assert_refused(
                    "f4b8d2e6c1a9", "SELECT * FROM pos_shift ORDER BY shift_id"
                )
                self.execute(
                    "UPDATE pos_shift SET currency = NULL, expected_cash = NULL, cash_variance = NULL, closed_by = NULL"
                )

    def test_full_chain_refusal_keeps_head_schema(self) -> None:
        command.upgrade(self.config, "head")
        self.execute("""
            INSERT INTO pos_payment_context (payment_id, shift_id, capture_operator_id)
            SELECT p.payment_id, s.shift_id, 'capture-owner'
            FROM payment p CROSS JOIN pos_shift s LIMIT 1
        """)
        self.assert_refused(
            "c7a8e2f4d6b1", "SELECT * FROM pos_payment_context", "f3a7c9e1d5b2"
        )
        self.assertEqual([(0,)], self.query("SELECT count(*) FROM pos_receipt_refund"))
        self.assertTrue(
            self.query("SELECT currency, issued_by, is_api_issued FROM receipt")
        )

    def test_long_actor_identity_refuses_downgrade(self) -> None:
        command.upgrade(self.config, "f4b8d2e6c1a9")
        self.execute("UPDATE pos_shift SET operator_id = repeat('a', 100)")
        self.assert_refused("f4b8d2e6c1a9", "SELECT * FROM pos_shift ORDER BY shift_id")

    def test_concurrent_refund_write_is_not_erased_by_downgrade(self) -> None:
        command.upgrade(self.config, "head")
        fixture = self.query(
            """
            SELECT r.receipt_id, p.payment_id, s.shift_id
            FROM receipt r
            JOIN payment p ON p.sales_order_id = r.sales_order_id
            CROSS JOIN pos_shift s
            LIMIT 1
            """
        )
        self.assertTrue(fixture, "A refund race requires seeded receipt/payment/shift")
        receipt_id, payment_id, shift_id = fixture[0]
        refund_id = str(uuid4())
        writer = psycopg.connect(self.db_url)
        writer.execute(
            """
            INSERT INTO pos_receipt_refund (
                refund_id, receipt_id, payment_id, refund_shift_id,
                actor_subject, reason, amount, currency
            ) VALUES (%s, %s, %s, %s, 'race-test', 'downgrade race', 0.01, 'USD')
            """,
            (refund_id, receipt_id, payment_id, shift_id),
        )
        downgrade_errors: list[Exception] = []

        def downgrade() -> None:
            try:
                command.downgrade(self.config, "b2d6f8a1c4e3")
            except Exception as error:
                downgrade_errors.append(error)

        downgrade_thread = threading.Thread(target=downgrade)
        try:
            downgrade_thread.start()
            deadline = time.monotonic() + 10
            waiting = []
            while time.monotonic() < deadline:
                waiting = self.query(
                    """
                    SELECT query FROM pg_stat_activity
                    WHERE datname = current_database()
                        AND pid <> pg_backend_pid()
                        AND wait_event_type = 'Lock'
                    """
                )
                if waiting or not downgrade_thread.is_alive():
                    break
                time.sleep(0.02)
            self.assertTrue(
                waiting, "Downgrade must wait for the in-flight refund transaction"
            )
            writer.commit()
            downgrade_thread.join(timeout=10)
            self.assertFalse(downgrade_thread.is_alive(), "Downgrade did not finish")
            self.assertEqual(1, len(downgrade_errors))
            self.assertIsInstance(downgrade_errors[0], RuntimeError)
            self.assertIn(
                "Cannot downgrade receipt/refund storage",
                str(downgrade_errors[0]),
            )
            self.assertEqual(
                [("c7a8e2f4d6b1",)],
                self.query("SELECT version_num FROM alembic_version"),
            )
            self.assertEqual(
                [(refund_id,)],
                self.query("SELECT refund_id FROM pos_receipt_refund"),
            )
        finally:
            if downgrade_thread.is_alive():
                writer.rollback()
                downgrade_thread.join(timeout=10)
            writer.close()

    def test_legacy_only_database_can_downgrade_and_upgrade(self) -> None:
        command.upgrade(self.config, "b2d6f8a1c4e3")
        before = self.query(
            "SELECT payment_id, amount FROM payment ORDER BY payment_id"
        )
        command.downgrade(self.config, "f3a7c9e1d5b2")
        self.assertEqual(
            [("f3a7c9e1d5b2",)], self.query("SELECT version_num FROM alembic_version")
        )
        command.upgrade(self.config, "head")
        self.assertEqual(
            before,
            self.query("SELECT payment_id, amount FROM payment ORDER BY payment_id"),
        )


if __name__ == "__main__":
    unittest.main()
