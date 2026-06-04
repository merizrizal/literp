import sqlalchemy as sa
from sqlalchemy.schema import CreateSequence, DropSequence
from sqlalchemy.schema import Sequence as SqlSequence

from alembic import op


def enum_type_exists(enum_name):
    """Check if a PostgreSQL enum type exists."""
    bind = op.get_context().bind
    result = bind.execute(
        sa.text("SELECT 1 FROM pg_type WHERE typname = :enum_name"),
        {"enum_name": enum_name},
    )
    return result.scalar() is not None


def create_enum_if_not_exists(enum_name, *values):
    """Create a PostgreSQL enum type if it does not exist."""
    if enum_type_exists(enum_name):
        print(f'Enum {enum_name} already exists')
        return

    print(f'Creating enum: {enum_name}')
    quoted_values = ', '.join([f"'{value}'" for value in values])
    op.execute(sa.text(f"CREATE TYPE {enum_name} AS ENUM ({quoted_values})"))


def create_table_if_not_exists(table_name, *columns):
    """Create a table if it does not exist."""
    bind = op.get_context().bind
    tables = sa.inspect(bind).get_table_names()
    if table_name not in tables:
        print(f'Creating table: {table_name}')
        op.create_table(table_name, *columns)
    else:
        print(f'Table {table_name} already exists')


def create_index_if_not_exists(index_name, table_name, columns, unique=False):
    """Create an index if it does not exist."""
    bind = op.get_context().bind
    indexes = sa.inspect(bind).get_indexes(table_name)
    if index_name not in [index['name'] for index in indexes]:
        print(f'Creating index: {index_name} for table {table_name}')
        op.create_index(index_name, table_name, columns, unique=unique)
    else:
        print(f'Index {index_name} already exists for table {table_name}')


def delete_index_if_exists(index_name, table_name):
    """Delete an index if it exists."""
    bind = op.get_context().bind
    indexes = sa.inspect(bind).get_indexes(table_name)
    if index_name in [index['name'] for index in indexes]:
        print(f'Deleting index: {index_name} for table {table_name}')
        op.drop_index(index_name, table_name)
    else:
        print(f'Index {index_name} does not exist for table {table_name}')


def create_sequence_if_not_exists(sequence_name):
    """Create a sequence if it does not exist."""
    bind = op.get_context().bind
    sequences = sa.inspect(bind).get_sequence_names()
    if sequence_name not in sequences:
        print(f'Creating sequence: {sequence_name}')
        op.execute(CreateSequence(SqlSequence(sequence_name, start=1)))
    else:
        print(f'Sequence {sequence_name} already exists')

def delete_sequence_if_exists(sequence_name):
    """Delete a sequence if it exists."""
    bind = op.get_context().bind
    sequences = sa.inspect(bind).get_sequence_names()
    if sequence_name in sequences:
        print(f'Deleting sequence: {sequence_name}')
        op.execute(DropSequence(SqlSequence(sequence_name)))
    else:
        print(f'Sequence {sequence_name} does not exist')

def column_exists(table_name, column_name):
    """Check if a column exists in a table."""
    bind = op.get_context().bind
    columns = sa.inspect(bind).get_columns(table_name)
    return any(c['name'] == column_name for c in columns)
