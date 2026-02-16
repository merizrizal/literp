"""00. Initial migration - Core Sales, Inventory, and Manufacturing System

Revision ID: 314b57a8dd0f
Revises:
Create Date: 2026-01-26 22:00:58.474180

This migration creates the foundational schema for Literp:
- Movement-based inventory system
- Sales order management with reservations
- POS operations
- Manufacturing (BOM, work orders)
- Multi-location support

"""
import os
import sys
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

sys.path.insert(0, os.getenv('ROOT_DIR'))
from python.database.migration.alembic.resources import \
    create_table_if_not_exists

# revision identifiers, used by Alembic.
revision: str = '314b57a8dd0f'
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema - Create all core tables."""

    # ==================== ENUMS ====================
    product_type_enum = sa.Enum('STOCK', 'SERVICE', name='product_type', create_type=True)
    location_type_enum = sa.Enum('WAREHOUSE', 'STORE', 'PRODUCTION', name='location_type', create_type=True)
    movement_type_enum = sa.Enum('IN', 'OUT', 'TRANSFER', 'ADJUSTMENT', name='movement_type', create_type=True)
    reference_type_enum = sa.Enum('SALES_ORDER', 'WORK_ORDER', 'PURCHASE_ORDER', 'ADJUSTMENT', 'TRANSFER',
                                    name='reference_type', create_type=True)
    reservation_status_enum = sa.Enum('RESERVED', 'FULFILLED', 'CANCELLED', name='reservation_status', create_type=True)
    sales_channel_enum = sa.Enum('POS', 'ONLINE', 'B2B', 'OTHER', name='sales_channel', create_type=True)
    order_status_enum = sa.Enum('DRAFT', 'CONFIRMED', 'FULFILLED', 'CANCELLED', name='order_status', create_type=True)
    line_status_enum = sa.Enum('PENDING', 'RESERVED', 'FULFILLED', 'CANCELLED', name='line_status', create_type=True)
    payment_method_enum = sa.Enum('CASH', 'CARD', 'DIGITAL', 'GIFT_CARD', 'OTHER', name='payment_method', create_type=True)
    payment_status_enum = sa.Enum('PENDING', 'AUTHORIZED', 'CAPTURED', 'REFUNDED', name='payment_status', create_type=True)
    shift_status_enum = sa.Enum('OPEN', 'CLOSED', name='shift_status', create_type=True)
    bom_status_enum = sa.Enum('DRAFT', 'ACTIVE', 'DEPRECATED', name='bom_status', create_type=True)
    work_order_status_enum = sa.Enum('PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', name='work_order_status', create_type=True)
    run_status_enum = sa.Enum('IN_PROGRESS', 'COMPLETED', name='run_status', create_type=True)

    # ==================== 1. UNIT OF MEASURE ====================
    create_table_if_not_exists(
        'unit_of_measure',
        sa.Column('uom_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('code', sa.String(50), unique=True, nullable=False, index=True),
        sa.Column('name', sa.String(255), nullable=False),
        sa.Column('base_unit', sa.String(50), nullable=True),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    # ==================== 2. PRODUCT CATALOG ====================
    create_table_if_not_exists(
        'product',
        sa.Column('product_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('sku', sa.String(255), unique=True, nullable=False, index=True),
        sa.Column('name', sa.String(255), nullable=False),
        sa.Column('product_type', product_type_enum, nullable=False),
        sa.Column('base_uom', sa.String(36), sa.ForeignKey('unit_of_measure.uom_id'), nullable=False),
        sa.Column('active', sa.Boolean, default=True, nullable=False),
        sa.Column('metadata', sa.JSON, nullable=True),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    create_table_if_not_exists(
        'product_variant',
        sa.Column('variant_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('product_id', sa.String(36), sa.ForeignKey('product.product_id', ondelete='CASCADE'), nullable=False),
        sa.Column('sku', sa.String(255), unique=True, nullable=False, index=True),
        sa.Column('name', sa.String(255), nullable=False),
        sa.Column('attributes', sa.JSON, nullable=True),
        sa.Column('active', sa.Boolean, default=True, nullable=False),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    # ==================== 3. LOCATIONS ====================
    create_table_if_not_exists(
        'location',
        sa.Column('location_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('code', sa.String(50), unique=True, nullable=False, index=True),
        sa.Column('name', sa.String(255), nullable=False),
        sa.Column('location_type', location_type_enum, nullable=False),
        sa.Column('is_active', sa.Boolean, default=True, nullable=False),
        sa.Column('address', sa.JSON, nullable=True),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    # ==================== 4. INVENTORY (MOVEMENT-BASED LEDGER) ====================
    create_table_if_not_exists(
        'inventory_movement',
        sa.Column('movement_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('product_id', sa.String(36), sa.ForeignKey('product.product_id'), nullable=False),
        sa.Column('sku', sa.String(255), nullable=False, index=True),
        sa.Column('movement_type', movement_type_enum, nullable=False),
        sa.Column('from_location_id', sa.String(36), sa.ForeignKey('location.location_id'), nullable=True),
        sa.Column('to_location_id', sa.String(36), sa.ForeignKey('location.location_id'), nullable=False),
        sa.Column('quantity', sa.Numeric(12, 3), nullable=False),
        sa.Column('reference_type', reference_type_enum, nullable=False),
        sa.Column('reference_id', sa.String(36), nullable=True),
        sa.Column('notes', sa.Text, nullable=True),
        sa.Column('created_by', sa.String(255), nullable=True),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Index('idx_inventory_movement_product_location_date', 'product_id', 'to_location_id', 'created_at'),
    )

    create_table_if_not_exists(
        'inventory_reservation',
        sa.Column('reservation_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('sales_order_id', sa.String(36), sa.ForeignKey('sales_order.sales_order_id', ondelete='CASCADE'), nullable=False),
        sa.Column('sales_order_line_id', sa.String(36), sa.ForeignKey('sales_order_line.line_id', ondelete='CASCADE'), nullable=False),
        sa.Column('product_id', sa.String(36), sa.ForeignKey('product.product_id'), nullable=False),
        sa.Column('sku', sa.String(255), nullable=False, index=True),
        sa.Column('location_id', sa.String(36), sa.ForeignKey('location.location_id'), nullable=False),
        sa.Column('quantity', sa.Numeric(12, 3), nullable=False),
        sa.Column('status', reservation_status_enum, nullable=False),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    # ==================== 5. SALES ORDERS ====================
    create_table_if_not_exists(
        'sales_order',
        sa.Column('sales_order_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('order_number', sa.String(50), unique=True, nullable=False, index=True),
        sa.Column('order_date', sa.DateTime, nullable=False),
        sa.Column('sales_channel', sales_channel_enum, nullable=False),
        sa.Column('customer_id', sa.String(36), nullable=True),
        sa.Column('location_id', sa.String(36), sa.ForeignKey('location.location_id'), nullable=False),
        sa.Column('status', order_status_enum, nullable=False),
        sa.Column('total_amount', sa.Numeric(14, 2), nullable=False),
        sa.Column('currency', sa.String(3), default='USD', nullable=False),
        sa.Column('notes', sa.Text, nullable=True),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    create_table_if_not_exists(
        'sales_order_line',
        sa.Column('line_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('sales_order_id', sa.String(36), sa.ForeignKey('sales_order.sales_order_id', ondelete='CASCADE'), nullable=False),
        sa.Column('product_id', sa.String(36), sa.ForeignKey('product.product_id'), nullable=False),
        sa.Column('sku', sa.String(255), nullable=False, index=True),
        sa.Column('quantity_ordered', sa.Numeric(12, 3), nullable=False),
        sa.Column('quantity_fulfilled', sa.Numeric(12, 3), default=0, nullable=False),
        sa.Column('unit_price', sa.Numeric(12, 2), nullable=False),
        sa.Column('line_total', sa.Numeric(14, 2), nullable=False),
        sa.Column('status', line_status_enum, nullable=False),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    # ==================== 6. PAYMENTS ====================
    create_table_if_not_exists(
        'payment',
        sa.Column('payment_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('sales_order_id', sa.String(36), sa.ForeignKey('sales_order.sales_order_id'), nullable=False),
        sa.Column('payment_method', payment_method_enum, nullable=False),
        sa.Column('amount', sa.Numeric(14, 2), nullable=False),
        sa.Column('status', payment_status_enum, nullable=False),
        sa.Column('transaction_ref', sa.String(255), nullable=True),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    # ==================== 7. POS OPERATIONS ====================
    create_table_if_not_exists(
        'pos_terminal',
        sa.Column('terminal_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('location_id', sa.String(36), sa.ForeignKey('location.location_id'), nullable=False),
        sa.Column('terminal_code', sa.String(50), unique=True, nullable=False, index=True),
        sa.Column('device_name', sa.String(255), nullable=False),
        sa.Column('is_active', sa.Boolean, default=True, nullable=False),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    create_table_if_not_exists(
        'pos_shift',
        sa.Column('shift_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('terminal_id', sa.String(36), sa.ForeignKey('pos_terminal.terminal_id'), nullable=False),
        sa.Column('operator_id', sa.String(36), nullable=False),
        sa.Column('shift_date', sa.Date, nullable=False),
        sa.Column('shift_number', sa.Integer, nullable=False),
        sa.Column('opened_at', sa.DateTime, nullable=False),
        sa.Column('closed_at', sa.DateTime, nullable=True),
        sa.Column('opening_balance', sa.Numeric(14, 2), nullable=False),
        sa.Column('closing_balance', sa.Numeric(14, 2), nullable=True),
        sa.Column('status', shift_status_enum, nullable=False),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    create_table_if_not_exists(
        'receipt',
        sa.Column('receipt_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('sales_order_id', sa.String(36), sa.ForeignKey('sales_order.sales_order_id'), nullable=False),
        sa.Column('shift_id', sa.String(36), sa.ForeignKey('pos_shift.shift_id'), nullable=True),
        sa.Column('receipt_number', sa.String(50), unique=True, nullable=False, index=True),
        sa.Column('receipt_date', sa.DateTime, nullable=False),
        sa.Column('total_items', sa.Integer, nullable=False),
        sa.Column('subtotal', sa.Numeric(14, 2), nullable=False),
        sa.Column('tax_amount', sa.Numeric(14, 2), nullable=False),
        sa.Column('total_amount', sa.Numeric(14, 2), nullable=False),
        sa.Column('receipt_data', sa.JSON, nullable=True),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    # ==================== 8. MANUFACTURING ====================
    create_table_if_not_exists(
        'bill_of_material',
        sa.Column('bom_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('product_id', sa.String(36), sa.ForeignKey('product.product_id'), nullable=False),
        sa.Column('bom_version', sa.Integer, nullable=False),
        sa.Column('status', bom_status_enum, nullable=False),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    create_table_if_not_exists(
        'bom_line',
        sa.Column('bom_line_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('bom_id', sa.String(36), sa.ForeignKey('bill_of_material.bom_id', ondelete='CASCADE'), nullable=False),
        sa.Column('component_product_id', sa.String(36), sa.ForeignKey('product.product_id'), nullable=False),
        sa.Column('component_sku', sa.String(255), nullable=False),
        sa.Column('quantity_per_unit', sa.Numeric(12, 3), nullable=False),
        sa.Column('scrap_percentage', sa.Numeric(5, 2), default=0, nullable=False),
        sa.Column('sequence', sa.Integer, nullable=False),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    create_table_if_not_exists(
        'work_order',
        sa.Column('work_order_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('work_order_number', sa.String(50), unique=True, nullable=False, index=True),
        sa.Column('product_id', sa.String(36), sa.ForeignKey('product.product_id'), nullable=False),
        sa.Column('bom_id', sa.String(36), sa.ForeignKey('bill_of_material.bom_id'), nullable=False),
        sa.Column('location_id', sa.String(36), sa.ForeignKey('location.location_id'), nullable=False),
        sa.Column('planned_quantity', sa.Numeric(12, 3), nullable=False),
        sa.Column('actual_quantity', sa.Numeric(12, 3), nullable=True),
        sa.Column('status', work_order_status_enum, nullable=False),
        sa.Column('planned_start', sa.DateTime, nullable=False),
        sa.Column('actual_start', sa.DateTime, nullable=True),
        sa.Column('planned_end', sa.DateTime, nullable=False),
        sa.Column('actual_end', sa.DateTime, nullable=True),
        sa.Column('notes', sa.Text, nullable=True),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )

    create_table_if_not_exists(
        'production_run',
        sa.Column('run_id', sa.String(36), primary_key=True, nullable=False),
        sa.Column('work_order_id', sa.String(36), sa.ForeignKey('work_order.work_order_id'), nullable=False),
        sa.Column('run_date', sa.Date, nullable=False),
        sa.Column('operator_id', sa.String(36), nullable=True),
        sa.Column('material_consumed', sa.JSON, nullable=True),
        sa.Column('output_quantity', sa.Numeric(12, 3), nullable=False),
        sa.Column('scrap_quantity', sa.Numeric(12, 3), nullable=False),
        sa.Column('status', run_status_enum, nullable=False),
        sa.Column('created_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime, server_default=sa.func.now(), nullable=False),
    )


def downgrade() -> None:
    """Downgrade schema - Drop all tables and enums."""
    # Drop tables in reverse order (respecting foreign keys)
    op.drop_table('production_run')
    op.drop_table('work_order')
    op.drop_table('bom_line')
    op.drop_table('bill_of_material')
    op.drop_table('receipt')
    op.drop_table('pos_shift')
    op.drop_table('pos_terminal')
    op.drop_table('payment')
    op.drop_table('sales_order_line')
    op.drop_table('sales_order')
    op.drop_table('inventory_reservation')
    op.drop_table('inventory_movement')
    op.drop_table('location')
    op.drop_table('product_variant')
    op.drop_table('product')
    op.drop_table('unit_of_measure')

    # Drop ENUM types
    op.execute('DROP TYPE IF EXISTS run_status')
    op.execute('DROP TYPE IF EXISTS work_order_status')
    op.execute('DROP TYPE IF EXISTS bom_status')
    op.execute('DROP TYPE IF EXISTS shift_status')
    op.execute('DROP TYPE IF EXISTS payment_status')
    op.execute('DROP TYPE IF EXISTS payment_method')
    op.execute('DROP TYPE IF EXISTS line_status')
    op.execute('DROP TYPE IF EXISTS order_status')
    op.execute('DROP TYPE IF EXISTS sales_channel')
    op.execute('DROP TYPE IF EXISTS reservation_status')
    op.execute('DROP TYPE IF EXISTS reference_type')
    op.execute('DROP TYPE IF EXISTS movement_type')
    op.execute('DROP TYPE IF EXISTS location_type')
    op.execute('DROP TYPE IF EXISTS product_type')
