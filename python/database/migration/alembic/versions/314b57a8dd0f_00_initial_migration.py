"""00. Initial migration

Revision ID: 314b57a8dd0f
Revises:
Create Date: 2026-01-26 22:00:58.474180

"""
import os
import sys
from typing import Sequence, Union

import sqlalchemy as sa

from alembic import op


sys.path.insert(0, os.getenv('ROOT_DIR'))
from python.database.migration.alembic.resources import (
    create_sequence_if_not_exists, create_table_if_not_exists,
    delete_sequence_if_exists)

# revision identifiers, used by Alembic.
revision: str = '314b57a8dd0f'
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    pass


def downgrade() -> None:
    """Downgrade schema."""
    pass
