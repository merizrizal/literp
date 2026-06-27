"""04. Inventory OUT movement semantics for sales fulfillment

Revision ID: e6d9a7c1b2f4
Revises: b8f4c1d9a2e7
Create Date: 2026-06-27 00:00:00.000000

Schema-only migration for Phase 03 fulfillment semantics.
Allows sales OUT movements to omit a fake destination location.
"""
from typing import Sequence, Union

import sqlalchemy as sa

from alembic import op

# revision identifiers, used by Alembic.
revision: str = 'e6d9a7c1b2f4'
down_revision: Union[str, Sequence[str], None] = 'b8f4c1d9a2e7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema - allow OUT rows to have no destination location."""

    op.alter_column(
        'inventory_movement',
        'to_location_id',
        existing_type=sa.String(36),
        existing_nullable=False,
        nullable=True,
    )

    op.execute(sa.text(
        """
        UPDATE inventory_movement
        SET to_location_id = NULL
        WHERE movement_type = 'OUT'
          AND to_location_id = from_location_id
        """
    ))


def downgrade() -> None:
    """Downgrade schema - restore the legacy sales OUT destination workaround."""

    op.execute(sa.text(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                FROM inventory_movement
                WHERE to_location_id IS NULL
                  AND movement_type <> 'OUT'
            ) THEN
                RAISE EXCEPTION 'Cannot restore NOT NULL on inventory_movement.to_location_id while non-OUT rows still have NULL destinations';
            END IF;
        END;
        $$;
        """
    ))

    op.execute(sa.text(
        """
        UPDATE inventory_movement
        SET to_location_id = from_location_id
        WHERE movement_type = 'OUT'
          AND to_location_id IS NULL
        """
    ))

    op.alter_column(
        'inventory_movement',
        'to_location_id',
        existing_type=sa.String(36),
        existing_nullable=True,
        nullable=False,
    )
