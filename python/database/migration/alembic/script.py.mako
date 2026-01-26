"""${message}

Revision ID: ${up_revision}
Revises: ${down_revision | comma,n}
Create Date: ${create_date}

"""
import os
import sys
from typing import Sequence, Union

import sqlalchemy as sa

from alembic import op
${imports if imports else ""}

sys.path.insert(0, os.getenv('ROOT_DIR'))
from python.database.migration.alembic.resources import (
    create_sequence_if_not_exists, create_table_if_not_exists,
    delete_sequence_if_exists)

# revision identifiers, used by Alembic.
revision: str = ${repr(up_revision)}
down_revision: Union[str, Sequence[str], None] = ${repr(down_revision)}
branch_labels: Union[str, Sequence[str], None] = ${repr(branch_labels)}
depends_on: Union[str, Sequence[str], None] = ${repr(depends_on)}


def upgrade() -> None:
    """Upgrade schema."""
    ${upgrades if upgrades else "pass"}


def downgrade() -> None:
    """Downgrade schema."""
    ${downgrades if downgrades else "pass"}
