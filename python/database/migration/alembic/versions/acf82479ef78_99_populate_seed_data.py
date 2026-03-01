"""99. Populate seed data

Revision ID: acf82479ef78
Revises: 314b57a8dd0f
Create Date: 2026-03-01 14:53:01.767429

"""
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Sequence, Union
import json
import uuid

import sqlalchemy as sa

from alembic import op

# revision identifiers, used by Alembic.
revision: str = 'acf82479ef78'
down_revision: Union[str, Sequence[str], None] = '314b57a8dd0f'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

SIM_SEED_DAYS = 14
SIM_START_DATE = date(2026, 1, 15)
SIM_POS_UNIT_PRICE = Decimal('19.00')
SIM_ONLINE_UNIT_PRICE = Decimal('18.50')
SIM_B2B_UNIT_PRICE = Decimal('16.75')
SIM_UUID_NAMESPACE = uuid.UUID('06a63ca7-b3dd-4db3-8a8a-4c40a1490ed9')


def sim_uuid(seed: str) -> str:
    return str(uuid.uuid5(SIM_UUID_NAMESPACE, seed))


def json_str(value: object) -> str:
    return json.dumps(value, separators=(',', ':'))

SEED_IDS = {
    'uom_unit': 'e6d51210-c046-4d7b-afce-2f33b5846dd1',
    'uom_kg': '88a08666-07e6-4ddf-babc-d628ef31f77a',
    'uom_liter': '7e74732e-384f-4cb9-8ca8-e6a289497fba',
    'uom_hour': 'fd18d7f6-e1a2-4476-b592-c00a80f4cf43',
    'prod_coffee_latte': 'ec848de5-e34f-4819-a345-0541f47a1230',
    'prod_coffee_bean': 'dd277b84-3590-4079-99a6-daf91e56ec31',
    'prod_milk': 'f9f3f413-f7cb-472c-b271-7d3376059636',
    'prod_cup': '0b2578e9-b0be-42a8-9ff5-a51fce4d9779',
    'prod_filter': 'c6be66cf-37e2-479c-a4a4-8c78cabf458a',
    'prod_latte_art': '84dfcf97-cffb-42f9-9181-b8cab6101b4f',
    'var_latte_hot_m': '8550bd2d-a652-42ce-8e67-6c248671d102',
    'var_latte_hot_l': 'ca9b006e-cf2d-4d54-9e14-2ab4452f3538',
    'var_latte_ice_l': '16677972-62cd-4cd6-ab8a-e51d1eb76f5c',
    'loc_wh': '736c700f-f3f6-4d1c-8c6a-b21266f5ac8f',
    'loc_store': '5e5965f0-3bd2-4da5-8831-b8387d08b64f',
    'loc_prod': '44dd2ebe-253f-4eaf-8f75-c5b457ceeff9',
    'mov_open_bean': 'b66570a9-6471-47f5-8ee2-b0901d58d6ca',
    'mov_open_milk': 'f42a7f37-5f54-4d9f-9895-1f663e97044f',
    'mov_open_cup': '0d3e87f0-a4be-4822-b67f-c12ad861f346',
    'mov_open_filter': 'afeb80db-e7ce-48c3-899f-3fb7bc649682',
    'mov_transfer_bean': 'a5e783f8-f06d-4f93-b724-50c6def5e294',
    'mov_transfer_milk': '58f5f093-83af-4d08-b1de-0f30bbf74de0',
    'mov_transfer_cup': 'f9d7358a-e3fa-42b8-bfd5-edb14d0b95e4',
    'mov_transfer_filter': 'f17c19e5-4537-4d1f-af43-6497fb591fdd',
    'mov_production_out': '45dc96d8-a35d-48b9-9074-b9fcb432f57d',
    'so_pos_001': '56689f0a-346b-4fdd-a3eb-cf7beaf4fe5f',
    'so_online_001': '2cfba591-a5b1-4d56-a8c1-bf00c185b73f',
    'so_b2b_001': '8351a4d1-b386-4745-ac9f-95a7395bc489',
    'sol_pos_001': '8c90212f-58dc-4ecb-8336-4ec24777314e',
    'sol_online_001': '6f3d632e-af2b-41e5-bf2f-34c7f749a9a8',
    'sol_b2b_001': '60e1ed71-2db9-4765-8dc5-020ab5a8f0ca',
    'res_online_001': '15801a57-901d-4f17-8073-3ac7f4b94ec4',
    'pay_pos_001': '20c04f27-c7a9-4f9c-8423-94d9c5d26317',
    'pay_online_001': '970e7388-1e9c-4ee1-8f34-b3f91911d7bc',
    'pay_b2b_001': '291db215-2f98-4117-b608-c538d3318ff2',
    'pos_terminal_1': '2b52f9c5-4deb-4f8e-9038-df49fa7e0900',
    'pos_shift_1': 'b84f39c7-163d-4339-b0cc-8bb39fffd343',
    'receipt_pos_001': '3ba7ca5a-16a0-4e75-b843-bee0b59315fd',
    'bom_latte_v1': '937bc40a-8a8b-48fd-ac1a-27f5afedfb6d',
    'bom_line_bean': '38bc8d44-b8f6-4ca9-8d00-b25ea75ef535',
    'bom_line_milk': '6d2fcf34-9f04-4c4f-a2ca-e9cd49484b77',
    'bom_line_cup': '34f46eca-d7db-40e7-a0ef-24e1ebca8cd2',
    'bom_line_filter': 'd0141ca8-e0d8-4f5f-a463-16ecf90e7f1d',
    'wo_001': '4b5f756f-56cb-49e9-a2db-fc04f0d98b9b',
    'wo_002': '97d4f1a2-c4a9-4497-b94b-f4dd5bf9df8a',
    'run_001': '55dd24e3-7412-47df-9df7-8d519f8766ce',
}


def upgrade() -> None:
    """Populate deterministic seed data for end-to-end simulation."""
    product_type_enum = sa.Enum('STOCK', 'SERVICE', name='product_type', create_type=False)
    location_type_enum = sa.Enum('WAREHOUSE', 'STORE', 'PRODUCTION', name='location_type', create_type=False)
    movement_type_enum = sa.Enum('IN', 'OUT', 'TRANSFER', 'ADJUSTMENT', name='movement_type', create_type=False)
    reference_type_enum = sa.Enum(
        'SALES_ORDER', 'WORK_ORDER', 'PURCHASE_ORDER', 'ADJUSTMENT', 'TRANSFER',
        name='reference_type', create_type=False
    )
    reservation_status_enum = sa.Enum(
        'RESERVED', 'FULFILLED', 'CANCELLED', name='reservation_status', create_type=False
    )
    sales_channel_enum = sa.Enum('POS', 'ONLINE', 'B2B', 'OTHER', name='sales_channel', create_type=False)
    order_status_enum = sa.Enum(
        'DRAFT', 'CONFIRMED', 'FULFILLED', 'CANCELLED', name='order_status', create_type=False
    )
    line_status_enum = sa.Enum(
        'PENDING', 'RESERVED', 'FULFILLED', 'CANCELLED', name='line_status', create_type=False
    )
    payment_method_enum = sa.Enum(
        'CASH', 'CARD', 'DIGITAL', 'GIFT_CARD', 'OTHER', name='payment_method', create_type=False
    )
    payment_status_enum = sa.Enum(
        'PENDING', 'AUTHORIZED', 'CAPTURED', 'REFUNDED', name='payment_status', create_type=False
    )
    shift_status_enum = sa.Enum('OPEN', 'CLOSED', name='shift_status', create_type=False)
    bom_status_enum = sa.Enum('DRAFT', 'ACTIVE', 'DEPRECATED', name='bom_status', create_type=False)
    work_order_status_enum = sa.Enum(
        'PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', name='work_order_status', create_type=False
    )
    run_status_enum = sa.Enum('IN_PROGRESS', 'COMPLETED', name='run_status', create_type=False)

    unit_of_measure = sa.table(
        'unit_of_measure',
        sa.column('uom_id', sa.String),
        sa.column('code', sa.String),
        sa.column('name', sa.String),
        sa.column('base_unit', sa.String),
    )
    product = sa.table(
        'product',
        sa.column('product_id', sa.String),
        sa.column('sku', sa.String),
        sa.column('name', sa.String),
        sa.column('product_type', product_type_enum),
        sa.column('base_uom', sa.String),
        sa.column('active', sa.Boolean),
        sa.column('metadata', sa.JSON),
    )
    product_variant = sa.table(
        'product_variant',
        sa.column('variant_id', sa.String),
        sa.column('product_id', sa.String),
        sa.column('sku', sa.String),
        sa.column('name', sa.String),
        sa.column('attributes', sa.JSON),
        sa.column('active', sa.Boolean),
    )
    location = sa.table(
        'location',
        sa.column('location_id', sa.String),
        sa.column('code', sa.String),
        sa.column('name', sa.String),
        sa.column('location_type', location_type_enum),
        sa.column('is_active', sa.Boolean),
        sa.column('address', sa.JSON),
    )
    inventory_movement = sa.table(
        'inventory_movement',
        sa.column('movement_id', sa.String),
        sa.column('product_id', sa.String),
        sa.column('sku', sa.String),
        sa.column('movement_type', movement_type_enum),
        sa.column('from_location_id', sa.String),
        sa.column('to_location_id', sa.String),
        sa.column('quantity', sa.Numeric),
        sa.column('reference_type', reference_type_enum),
        sa.column('reference_id', sa.String),
        sa.column('notes', sa.Text),
        sa.column('created_by', sa.String),
        sa.column('created_at', sa.DateTime),
    )
    sales_order = sa.table(
        'sales_order',
        sa.column('sales_order_id', sa.String),
        sa.column('order_number', sa.String),
        sa.column('order_date', sa.DateTime),
        sa.column('sales_channel', sales_channel_enum),
        sa.column('customer_id', sa.String),
        sa.column('location_id', sa.String),
        sa.column('status', order_status_enum),
        sa.column('total_amount', sa.Numeric),
        sa.column('currency', sa.String),
        sa.column('notes', sa.Text),
    )
    sales_order_line = sa.table(
        'sales_order_line',
        sa.column('line_id', sa.String),
        sa.column('sales_order_id', sa.String),
        sa.column('product_id', sa.String),
        sa.column('sku', sa.String),
        sa.column('quantity_ordered', sa.Numeric),
        sa.column('quantity_fulfilled', sa.Numeric),
        sa.column('unit_price', sa.Numeric),
        sa.column('line_total', sa.Numeric),
        sa.column('status', line_status_enum),
    )
    inventory_reservation = sa.table(
        'inventory_reservation',
        sa.column('reservation_id', sa.String),
        sa.column('sales_order_id', sa.String),
        sa.column('sales_order_line_id', sa.String),
        sa.column('product_id', sa.String),
        sa.column('sku', sa.String),
        sa.column('location_id', sa.String),
        sa.column('quantity', sa.Numeric),
        sa.column('status', reservation_status_enum),
    )
    payment = sa.table(
        'payment',
        sa.column('payment_id', sa.String),
        sa.column('sales_order_id', sa.String),
        sa.column('payment_method', payment_method_enum),
        sa.column('amount', sa.Numeric),
        sa.column('status', payment_status_enum),
        sa.column('transaction_ref', sa.String),
    )
    pos_terminal = sa.table(
        'pos_terminal',
        sa.column('terminal_id', sa.String),
        sa.column('location_id', sa.String),
        sa.column('terminal_code', sa.String),
        sa.column('device_name', sa.String),
        sa.column('is_active', sa.Boolean),
    )
    pos_shift = sa.table(
        'pos_shift',
        sa.column('shift_id', sa.String),
        sa.column('terminal_id', sa.String),
        sa.column('operator_id', sa.String),
        sa.column('shift_date', sa.Date),
        sa.column('shift_number', sa.Integer),
        sa.column('opened_at', sa.DateTime),
        sa.column('closed_at', sa.DateTime),
        sa.column('opening_balance', sa.Numeric),
        sa.column('closing_balance', sa.Numeric),
        sa.column('status', shift_status_enum),
    )
    receipt = sa.table(
        'receipt',
        sa.column('receipt_id', sa.String),
        sa.column('sales_order_id', sa.String),
        sa.column('shift_id', sa.String),
        sa.column('receipt_number', sa.String),
        sa.column('receipt_date', sa.DateTime),
        sa.column('total_items', sa.Integer),
        sa.column('subtotal', sa.Numeric),
        sa.column('tax_amount', sa.Numeric),
        sa.column('total_amount', sa.Numeric),
        sa.column('receipt_data', sa.JSON),
    )
    bill_of_material = sa.table(
        'bill_of_material',
        sa.column('bom_id', sa.String),
        sa.column('product_id', sa.String),
        sa.column('bom_version', sa.Integer),
        sa.column('status', bom_status_enum),
    )
    bom_line = sa.table(
        'bom_line',
        sa.column('bom_line_id', sa.String),
        sa.column('bom_id', sa.String),
        sa.column('component_product_id', sa.String),
        sa.column('component_sku', sa.String),
        sa.column('quantity_per_unit', sa.Numeric),
        sa.column('scrap_percentage', sa.Numeric),
        sa.column('sequence', sa.Integer),
    )
    work_order = sa.table(
        'work_order',
        sa.column('work_order_id', sa.String),
        sa.column('work_order_number', sa.String),
        sa.column('product_id', sa.String),
        sa.column('bom_id', sa.String),
        sa.column('location_id', sa.String),
        sa.column('planned_quantity', sa.Numeric),
        sa.column('actual_quantity', sa.Numeric),
        sa.column('status', work_order_status_enum),
        sa.column('planned_start', sa.DateTime),
        sa.column('actual_start', sa.DateTime),
        sa.column('planned_end', sa.DateTime),
        sa.column('actual_end', sa.DateTime),
        sa.column('notes', sa.Text),
    )
    production_run = sa.table(
        'production_run',
        sa.column('run_id', sa.String),
        sa.column('work_order_id', sa.String),
        sa.column('run_date', sa.Date),
        sa.column('operator_id', sa.String),
        sa.column('material_consumed', sa.JSON),
        sa.column('output_quantity', sa.Numeric),
        sa.column('scrap_quantity', sa.Numeric),
        sa.column('status', run_status_enum),
    )

    op.bulk_insert(
        unit_of_measure,
        [
            {'uom_id': SEED_IDS['uom_unit'], 'code': 'UNIT', 'name': 'Unit', 'base_unit': None},
            {'uom_id': SEED_IDS['uom_kg'], 'code': 'KG', 'name': 'Kilogram', 'base_unit': 'KG'},
            {'uom_id': SEED_IDS['uom_liter'], 'code': 'L', 'name': 'Liter', 'base_unit': 'L'},
            {'uom_id': SEED_IDS['uom_hour'], 'code': 'HOUR', 'name': 'Hour', 'base_unit': 'HOUR'},
        ],
    )

    op.bulk_insert(
        product,
        [
            {
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE',
                'name': 'Literp Signature Latte',
                'product_type': 'STOCK',
                'base_uom': SEED_IDS['uom_unit'],
                'active': True,
                'metadata': json_str({'category': 'Beverage', 'brand': 'Literp'}),
            },
            {
                'product_id': SEED_IDS['prod_coffee_bean'],
                'sku': 'RAW-BEAN-ARABICA',
                'name': 'Arabica Coffee Bean',
                'product_type': 'STOCK',
                'base_uom': SEED_IDS['uom_kg'],
                'active': True,
                'metadata': json_str({'category': 'Raw Material', 'origin': 'Indonesia'}),
            },
            {
                'product_id': SEED_IDS['prod_milk'],
                'sku': 'RAW-MILK-FRESH',
                'name': 'Fresh Milk',
                'product_type': 'STOCK',
                'base_uom': SEED_IDS['uom_liter'],
                'active': True,
                'metadata': json_str({'category': 'Raw Material', 'cold_chain': True}),
            },
            {
                'product_id': SEED_IDS['prod_cup'],
                'sku': 'PKG-CUP-16OZ',
                'name': 'Paper Cup 16oz',
                'product_type': 'STOCK',
                'base_uom': SEED_IDS['uom_unit'],
                'active': True,
                'metadata': json_str({'category': 'Packaging'}),
            },
            {
                'product_id': SEED_IDS['prod_filter'],
                'sku': 'RAW-FILTER-PAPER',
                'name': 'Coffee Filter Paper',
                'product_type': 'STOCK',
                'base_uom': SEED_IDS['uom_unit'],
                'active': True,
                'metadata': json_str({'category': 'Consumable'}),
            },
            {
                'product_id': SEED_IDS['prod_latte_art'],
                'sku': 'SRV-LATTE-ART',
                'name': 'Latte Art Workshop',
                'product_type': 'SERVICE',
                'base_uom': SEED_IDS['uom_hour'],
                'active': True,
                'metadata': json_str({'category': 'Service'}),
            },
        ],
    )

    op.bulk_insert(
        product_variant,
        [
            {
                'variant_id': SEED_IDS['var_latte_hot_m'],
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE-HOT-M',
                'name': 'Hot - Medium',
                'attributes': json_str({'temperature': 'hot', 'size': 'M'}),
                'active': True,
            },
            {
                'variant_id': SEED_IDS['var_latte_hot_l'],
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE-HOT-L',
                'name': 'Hot - Large',
                'attributes': json_str({'temperature': 'hot', 'size': 'L'}),
                'active': True,
            },
            {
                'variant_id': SEED_IDS['var_latte_ice_l'],
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE-ICE-L',
                'name': 'Iced - Large',
                'attributes': json_str({'temperature': 'iced', 'size': 'L'}),
                'active': True,
            },
        ],
    )

    op.bulk_insert(
        location,
        [
            {
                'location_id': SEED_IDS['loc_wh'],
                'code': 'WH-JKT-01',
                'name': 'Central Warehouse Jakarta',
                'location_type': 'WAREHOUSE',
                'is_active': True,
                'address': json_str({'city': 'Jakarta', 'country': 'ID'}),
            },
            {
                'location_id': SEED_IDS['loc_store'],
                'code': 'STR-JKT-01',
                'name': 'Flagship Store Jakarta',
                'location_type': 'STORE',
                'is_active': True,
                'address': json_str({'city': 'Jakarta', 'country': 'ID'}),
            },
            {
                'location_id': SEED_IDS['loc_prod'],
                'code': 'PRD-JKT-01',
                'name': 'Production Kitchen Jakarta',
                'location_type': 'PRODUCTION',
                'is_active': True,
                'address': json_str({'city': 'Jakarta', 'country': 'ID'}),
            },
        ],
    )

    op.bulk_insert(
        inventory_movement,
        [
            {
                'movement_id': SEED_IDS['mov_open_bean'],
                'product_id': SEED_IDS['prod_coffee_bean'],
                'sku': 'RAW-BEAN-ARABICA',
                'movement_type': 'IN',
                'from_location_id': None,
                'to_location_id': SEED_IDS['loc_wh'],
                'quantity': Decimal('120.000'),
                'reference_type': 'ADJUSTMENT',
                'reference_id': 'OPENING-BALANCE',
                'notes': 'Opening stock',
                'created_by': 'seed.bot',
                'created_at': datetime(2026, 1, 27, 7, 0, 0),
            },
            {
                'movement_id': SEED_IDS['mov_open_milk'],
                'product_id': SEED_IDS['prod_milk'],
                'sku': 'RAW-MILK-FRESH',
                'movement_type': 'IN',
                'from_location_id': None,
                'to_location_id': SEED_IDS['loc_wh'],
                'quantity': Decimal('480.000'),
                'reference_type': 'ADJUSTMENT',
                'reference_id': 'OPENING-BALANCE',
                'notes': 'Opening stock',
                'created_by': 'seed.bot',
                'created_at': datetime(2026, 1, 27, 7, 2, 0),
            },
            {
                'movement_id': SEED_IDS['mov_open_cup'],
                'product_id': SEED_IDS['prod_cup'],
                'sku': 'PKG-CUP-16OZ',
                'movement_type': 'IN',
                'from_location_id': None,
                'to_location_id': SEED_IDS['loc_wh'],
                'quantity': Decimal('2500.000'),
                'reference_type': 'ADJUSTMENT',
                'reference_id': 'OPENING-BALANCE',
                'notes': 'Opening stock',
                'created_by': 'seed.bot',
                'created_at': datetime(2026, 1, 27, 7, 3, 0),
            },
            {
                'movement_id': SEED_IDS['mov_open_filter'],
                'product_id': SEED_IDS['prod_filter'],
                'sku': 'RAW-FILTER-PAPER',
                'movement_type': 'IN',
                'from_location_id': None,
                'to_location_id': SEED_IDS['loc_wh'],
                'quantity': Decimal('1200.000'),
                'reference_type': 'ADJUSTMENT',
                'reference_id': 'OPENING-BALANCE',
                'notes': 'Opening stock',
                'created_by': 'seed.bot',
                'created_at': datetime(2026, 1, 27, 7, 4, 0),
            },
            {
                'movement_id': SEED_IDS['mov_transfer_bean'],
                'product_id': SEED_IDS['prod_coffee_bean'],
                'sku': 'RAW-BEAN-ARABICA',
                'movement_type': 'TRANSFER',
                'from_location_id': SEED_IDS['loc_wh'],
                'to_location_id': SEED_IDS['loc_prod'],
                'quantity': Decimal('20.000'),
                'reference_type': 'TRANSFER',
                'reference_id': 'TRF-0001',
                'notes': 'Transfer to production',
                'created_by': 'seed.bot',
                'created_at': datetime(2026, 1, 27, 8, 0, 0),
            },
            {
                'movement_id': SEED_IDS['mov_transfer_milk'],
                'product_id': SEED_IDS['prod_milk'],
                'sku': 'RAW-MILK-FRESH',
                'movement_type': 'TRANSFER',
                'from_location_id': SEED_IDS['loc_wh'],
                'to_location_id': SEED_IDS['loc_prod'],
                'quantity': Decimal('60.000'),
                'reference_type': 'TRANSFER',
                'reference_id': 'TRF-0002',
                'notes': 'Transfer to production',
                'created_by': 'seed.bot',
                'created_at': datetime(2026, 1, 27, 8, 5, 0),
            },
            {
                'movement_id': SEED_IDS['mov_transfer_cup'],
                'product_id': SEED_IDS['prod_cup'],
                'sku': 'PKG-CUP-16OZ',
                'movement_type': 'TRANSFER',
                'from_location_id': SEED_IDS['loc_wh'],
                'to_location_id': SEED_IDS['loc_store'],
                'quantity': Decimal('500.000'),
                'reference_type': 'TRANSFER',
                'reference_id': 'TRF-0003',
                'notes': 'Transfer to store',
                'created_by': 'seed.bot',
                'created_at': datetime(2026, 1, 27, 8, 10, 0),
            },
            {
                'movement_id': SEED_IDS['mov_transfer_filter'],
                'product_id': SEED_IDS['prod_filter'],
                'sku': 'RAW-FILTER-PAPER',
                'movement_type': 'TRANSFER',
                'from_location_id': SEED_IDS['loc_wh'],
                'to_location_id': SEED_IDS['loc_prod'],
                'quantity': Decimal('200.000'),
                'reference_type': 'TRANSFER',
                'reference_id': 'TRF-0004',
                'notes': 'Transfer to production',
                'created_by': 'seed.bot',
                'created_at': datetime(2026, 1, 27, 8, 12, 0),
            },
            {
                'movement_id': SEED_IDS['mov_production_out'],
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE',
                'movement_type': 'IN',
                'from_location_id': SEED_IDS['loc_prod'],
                'to_location_id': SEED_IDS['loc_store'],
                'quantity': Decimal('180.000'),
                'reference_type': 'WORK_ORDER',
                'reference_id': SEED_IDS['wo_001'],
                'notes': 'Production output moved to store',
                'created_by': 'seed.bot',
                'created_at': datetime(2026, 1, 28, 6, 30, 0),
            },
        ],
    )

    op.bulk_insert(
        sales_order,
        [
            {
                'sales_order_id': SEED_IDS['so_pos_001'],
                'order_number': 'SO-POS-20260128-0001',
                'order_date': datetime(2026, 1, 28, 9, 30, 0),
                'sales_channel': 'POS',
                'customer_id': None,
                'location_id': SEED_IDS['loc_store'],
                'status': 'FULFILLED',
                'total_amount': Decimal('38.00'),
                'currency': 'USD',
                'notes': 'Walk-in customer',
            },
            {
                'sales_order_id': SEED_IDS['so_online_001'],
                'order_number': 'SO-ONL-20260128-0001',
                'order_date': datetime(2026, 1, 28, 10, 15, 0),
                'sales_channel': 'ONLINE',
                'customer_id': 'cust-online-001',
                'location_id': SEED_IDS['loc_store'],
                'status': 'CONFIRMED',
                'total_amount': Decimal('95.00'),
                'currency': 'USD',
                'notes': 'Marketplace order pending handoff',
            },
            {
                'sales_order_id': SEED_IDS['so_b2b_001'],
                'order_number': 'SO-B2B-20260128-0001',
                'order_date': datetime(2026, 1, 28, 14, 5, 0),
                'sales_channel': 'B2B',
                'customer_id': 'cust-b2b-001',
                'location_id': SEED_IDS['loc_store'],
                'status': 'FULFILLED',
                'total_amount': Decimal('420.00'),
                'currency': 'USD',
                'notes': 'Office catering batch order',
            },
        ],
    )

    op.bulk_insert(
        sales_order_line,
        [
            {
                'line_id': SEED_IDS['sol_pos_001'],
                'sales_order_id': SEED_IDS['so_pos_001'],
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE',
                'quantity_ordered': Decimal('2.000'),
                'quantity_fulfilled': Decimal('2.000'),
                'unit_price': Decimal('19.00'),
                'line_total': Decimal('38.00'),
                'status': 'FULFILLED',
            },
            {
                'line_id': SEED_IDS['sol_online_001'],
                'sales_order_id': SEED_IDS['so_online_001'],
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE',
                'quantity_ordered': Decimal('5.000'),
                'quantity_fulfilled': Decimal('0.000'),
                'unit_price': Decimal('19.00'),
                'line_total': Decimal('95.00'),
                'status': 'RESERVED',
            },
            {
                'line_id': SEED_IDS['sol_b2b_001'],
                'sales_order_id': SEED_IDS['so_b2b_001'],
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE',
                'quantity_ordered': Decimal('24.000'),
                'quantity_fulfilled': Decimal('24.000'),
                'unit_price': Decimal('17.50'),
                'line_total': Decimal('420.00'),
                'status': 'FULFILLED',
            },
        ],
    )

    op.bulk_insert(
        inventory_reservation,
        [
            {
                'reservation_id': SEED_IDS['res_online_001'],
                'sales_order_id': SEED_IDS['so_online_001'],
                'sales_order_line_id': SEED_IDS['sol_online_001'],
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE',
                'location_id': SEED_IDS['loc_store'],
                'quantity': Decimal('5.000'),
                'status': 'RESERVED',
            },
        ],
    )

    op.bulk_insert(
        payment,
        [
            {
                'payment_id': SEED_IDS['pay_pos_001'],
                'sales_order_id': SEED_IDS['so_pos_001'],
                'payment_method': 'CARD',
                'amount': Decimal('38.00'),
                'status': 'CAPTURED',
                'transaction_ref': 'TXN-POS-0001',
            },
            {
                'payment_id': SEED_IDS['pay_online_001'],
                'sales_order_id': SEED_IDS['so_online_001'],
                'payment_method': 'DIGITAL',
                'amount': Decimal('95.00'),
                'status': 'AUTHORIZED',
                'transaction_ref': 'TXN-ONL-0001',
            },
            {
                'payment_id': SEED_IDS['pay_b2b_001'],
                'sales_order_id': SEED_IDS['so_b2b_001'],
                'payment_method': 'OTHER',
                'amount': Decimal('420.00'),
                'status': 'PENDING',
                'transaction_ref': 'INV-B2B-0001',
            },
        ],
    )

    op.bulk_insert(
        pos_terminal,
        [
            {
                'terminal_id': SEED_IDS['pos_terminal_1'],
                'location_id': SEED_IDS['loc_store'],
                'terminal_code': 'POS-STR-JKT-01',
                'device_name': 'Store Counter POS #1',
                'is_active': True,
            },
        ],
    )

    op.bulk_insert(
        pos_shift,
        [
            {
                'shift_id': SEED_IDS['pos_shift_1'],
                'terminal_id': SEED_IDS['pos_terminal_1'],
                'operator_id': 'operator-001',
                'shift_date': date(2026, 1, 28),
                'shift_number': 1,
                'opened_at': datetime(2026, 1, 28, 8, 0, 0),
                'closed_at': datetime(2026, 1, 28, 17, 0, 0),
                'opening_balance': Decimal('150.00'),
                'closing_balance': Decimal('188.00'),
                'status': 'CLOSED',
            },
        ],
    )

    op.bulk_insert(
        receipt,
        [
            {
                'receipt_id': SEED_IDS['receipt_pos_001'],
                'sales_order_id': SEED_IDS['so_pos_001'],
                'shift_id': SEED_IDS['pos_shift_1'],
                'receipt_number': 'RCPT-20260128-0001',
                'receipt_date': datetime(2026, 1, 28, 9, 31, 0),
                'total_items': 2,
                'subtotal': Decimal('35.00'),
                'tax_amount': Decimal('3.00'),
                'total_amount': Decimal('38.00'),
                'receipt_data': json_str({
                    'cashier': 'operator-001',
                    'items': [{'sku': 'LIT-COF-LATTE', 'qty': 2, 'price': 19.00}],
                }),
            },
        ],
    )

    op.bulk_insert(
        bill_of_material,
        [
            {
                'bom_id': SEED_IDS['bom_latte_v1'],
                'product_id': SEED_IDS['prod_coffee_latte'],
                'bom_version': 1,
                'status': 'ACTIVE',
            },
        ],
    )

    op.bulk_insert(
        bom_line,
        [
            {
                'bom_line_id': SEED_IDS['bom_line_bean'],
                'bom_id': SEED_IDS['bom_latte_v1'],
                'component_product_id': SEED_IDS['prod_coffee_bean'],
                'component_sku': 'RAW-BEAN-ARABICA',
                'quantity_per_unit': Decimal('0.018'),
                'scrap_percentage': Decimal('2.50'),
                'sequence': 1,
            },
            {
                'bom_line_id': SEED_IDS['bom_line_milk'],
                'bom_id': SEED_IDS['bom_latte_v1'],
                'component_product_id': SEED_IDS['prod_milk'],
                'component_sku': 'RAW-MILK-FRESH',
                'quantity_per_unit': Decimal('0.220'),
                'scrap_percentage': Decimal('1.00'),
                'sequence': 2,
            },
            {
                'bom_line_id': SEED_IDS['bom_line_cup'],
                'bom_id': SEED_IDS['bom_latte_v1'],
                'component_product_id': SEED_IDS['prod_cup'],
                'component_sku': 'PKG-CUP-16OZ',
                'quantity_per_unit': Decimal('1.000'),
                'scrap_percentage': Decimal('0.00'),
                'sequence': 3,
            },
            {
                'bom_line_id': SEED_IDS['bom_line_filter'],
                'bom_id': SEED_IDS['bom_latte_v1'],
                'component_product_id': SEED_IDS['prod_filter'],
                'component_sku': 'RAW-FILTER-PAPER',
                'quantity_per_unit': Decimal('1.000'),
                'scrap_percentage': Decimal('0.50'),
                'sequence': 4,
            },
        ],
    )

    op.bulk_insert(
        work_order,
        [
            {
                'work_order_id': SEED_IDS['wo_001'],
                'work_order_number': 'WO-20260128-0001',
                'product_id': SEED_IDS['prod_coffee_latte'],
                'bom_id': SEED_IDS['bom_latte_v1'],
                'location_id': SEED_IDS['loc_prod'],
                'planned_quantity': Decimal('180.000'),
                'actual_quantity': Decimal('180.000'),
                'status': 'COMPLETED',
                'planned_start': datetime(2026, 1, 28, 4, 0, 0),
                'actual_start': datetime(2026, 1, 28, 4, 5, 0),
                'planned_end': datetime(2026, 1, 28, 6, 0, 0),
                'actual_end': datetime(2026, 1, 28, 6, 10, 0),
                'notes': 'Daily store replenishment run',
            },
            {
                'work_order_id': SEED_IDS['wo_002'],
                'work_order_number': 'WO-20260129-0001',
                'product_id': SEED_IDS['prod_coffee_latte'],
                'bom_id': SEED_IDS['bom_latte_v1'],
                'location_id': SEED_IDS['loc_prod'],
                'planned_quantity': Decimal('220.000'),
                'actual_quantity': None,
                'status': 'PLANNED',
                'planned_start': datetime(2026, 1, 29, 4, 0, 0),
                'actual_start': None,
                'planned_end': datetime(2026, 1, 29, 6, 30, 0),
                'actual_end': None,
                'notes': 'Planned production for morning demand',
            },
        ],
    )

    op.bulk_insert(
        production_run,
        [
            {
                'run_id': SEED_IDS['run_001'],
                'work_order_id': SEED_IDS['wo_001'],
                'run_date': date(2026, 1, 28),
                'operator_id': 'operator-prod-001',
                'material_consumed': json_str({
                    'RAW-BEAN-ARABICA': 3.24,
                    'RAW-MILK-FRESH': 39.6,
                    'PKG-CUP-16OZ': 180,
                    'RAW-FILTER-PAPER': 180,
                }),
                'output_quantity': Decimal('180.000'),
                'scrap_quantity': Decimal('3.000'),
                'status': 'COMPLETED',
            },
        ],
    )

    sim_terminal_id = sim_uuid('sim-terminal-2')
    sim_pos_shifts = []
    sim_work_orders = []
    sim_production_runs = []
    sim_movements = []
    sim_sales_orders = []
    sim_order_lines = []
    sim_reservations = []
    sim_payments = []
    sim_receipts = []

    for day_idx in range(SIM_SEED_DAYS):
        seed_date = SIM_START_DATE + timedelta(days=day_idx)
        day_str = seed_date.isoformat()
        shift_id = sim_uuid(f'sim-shift-{day_str}')
        sim_pos_shifts.append(
            {
                'shift_id': shift_id,
                'terminal_id': sim_terminal_id,
                'operator_id': f'operator-sim-{day_idx % 4 + 1:03d}',
                'shift_date': seed_date,
                'shift_number': 1,
                'opened_at': datetime(seed_date.year, seed_date.month, seed_date.day, 8, 0, 0),
                'closed_at': datetime(seed_date.year, seed_date.month, seed_date.day, 18, 0, 0),
                'opening_balance': Decimal('200.00'),
                'closing_balance': Decimal('250.00') + Decimal(day_idx * 7),
                'status': 'CLOSED',
            }
        )

        wo_id = sim_uuid(f'sim-wo-{day_str}')
        is_completed_wo = day_idx < (SIM_SEED_DAYS - 2)
        output_qty = Decimal(f"{170 + (day_idx % 5) * 18}.000")
        sim_work_orders.append(
            {
                'work_order_id': wo_id,
                'work_order_number': f'WO-SIM-{seed_date.strftime("%Y%m%d")}-001',
                'product_id': SEED_IDS['prod_coffee_latte'],
                'bom_id': SEED_IDS['bom_latte_v1'],
                'location_id': SEED_IDS['loc_prod'],
                'planned_quantity': output_qty,
                'actual_quantity': output_qty if is_completed_wo else None,
                'status': 'COMPLETED' if is_completed_wo else 'PLANNED',
                'planned_start': datetime(seed_date.year, seed_date.month, seed_date.day, 4, 0, 0),
                'actual_start': datetime(seed_date.year, seed_date.month, seed_date.day, 4, 10, 0)
                if is_completed_wo else None,
                'planned_end': datetime(seed_date.year, seed_date.month, seed_date.day, 6, 30, 0),
                'actual_end': datetime(seed_date.year, seed_date.month, seed_date.day, 6, 40, 0)
                if is_completed_wo else None,
                'notes': 'Simulated daily production schedule',
            }
        )

        if is_completed_wo:
            sim_production_runs.append(
                {
                    'run_id': sim_uuid(f'sim-run-{day_str}'),
                    'work_order_id': wo_id,
                    'run_date': seed_date,
                    'operator_id': f'operator-prod-sim-{day_idx % 3 + 1:03d}',
                    'material_consumed': json_str({
                        'RAW-BEAN-ARABICA': float(output_qty * Decimal('0.018')),
                        'RAW-MILK-FRESH': float(output_qty * Decimal('0.220')),
                        'PKG-CUP-16OZ': int(output_qty),
                        'RAW-FILTER-PAPER': int(output_qty),
                    }),
                    'output_quantity': output_qty,
                    'scrap_quantity': Decimal(f"{2 + (day_idx % 3)}.000"),
                    'status': 'COMPLETED',
                }
            )
            sim_movements.append(
                {
                    'movement_id': sim_uuid(f'sim-mov-prod-in-{day_str}'),
                    'product_id': SEED_IDS['prod_coffee_latte'],
                    'sku': 'LIT-COF-LATTE',
                    'movement_type': 'IN',
                    'from_location_id': SEED_IDS['loc_prod'],
                    'to_location_id': SEED_IDS['loc_store'],
                    'quantity': output_qty,
                    'reference_type': 'WORK_ORDER',
                    'reference_id': wo_id,
                    'notes': 'Simulated production output',
                    'created_by': 'seed.sim',
                    'created_at': datetime(seed_date.year, seed_date.month, seed_date.day, 6, 45, 0),
                }
            )

        pos_so_id = sim_uuid(f'sim-so-pos-{day_str}')
        pos_line_id = sim_uuid(f'sim-sol-pos-{day_str}')
        pos_qty_int = 2 + (day_idx % 4)
        pos_qty = Decimal(f'{pos_qty_int}.000')
        pos_total = (Decimal(pos_qty_int) * SIM_POS_UNIT_PRICE).quantize(Decimal('0.01'))
        sim_sales_orders.append(
            {
                'sales_order_id': pos_so_id,
                'order_number': f'SO-SIM-POS-{seed_date.strftime("%Y%m%d")}-001',
                'order_date': datetime(seed_date.year, seed_date.month, seed_date.day, 9, 15, 0),
                'sales_channel': 'POS',
                'customer_id': None,
                'location_id': SEED_IDS['loc_store'],
                'status': 'FULFILLED',
                'total_amount': pos_total,
                'currency': 'USD',
                'notes': 'Simulated walk-in traffic',
            }
        )
        sim_order_lines.append(
            {
                'line_id': pos_line_id,
                'sales_order_id': pos_so_id,
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE',
                'quantity_ordered': pos_qty,
                'quantity_fulfilled': pos_qty,
                'unit_price': SIM_POS_UNIT_PRICE,
                'line_total': pos_total,
                'status': 'FULFILLED',
            }
        )
        sim_payments.append(
            {
                'payment_id': sim_uuid(f'sim-pay-pos-{day_str}'),
                'sales_order_id': pos_so_id,
                'payment_method': 'CARD',
                'amount': pos_total,
                'status': 'CAPTURED',
                'transaction_ref': f'SIM-TXN-POS-{seed_date.strftime("%Y%m%d")}-001',
            }
        )
        sim_receipts.append(
            {
                'receipt_id': sim_uuid(f'sim-rcpt-pos-{day_str}'),
                'sales_order_id': pos_so_id,
                'shift_id': shift_id,
                'receipt_number': f'RCPT-SIM-{seed_date.strftime("%Y%m%d")}-001',
                'receipt_date': datetime(seed_date.year, seed_date.month, seed_date.day, 9, 18, 0),
                'total_items': pos_qty_int,
                'subtotal': (pos_total * Decimal('0.9')).quantize(Decimal('0.01')),
                'tax_amount': (pos_total * Decimal('0.1')).quantize(Decimal('0.01')),
                'total_amount': pos_total,
                'receipt_data': json_str({
                    'cashier': f'operator-sim-{day_idx % 4 + 1:03d}',
                    'items': [{'sku': 'LIT-COF-LATTE', 'qty': pos_qty_int, 'price': float(SIM_POS_UNIT_PRICE)}],
                }),
            }
        )
        sim_movements.append(
            {
                'movement_id': sim_uuid(f'sim-mov-pos-out-{day_str}'),
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE',
                'movement_type': 'OUT',
                'from_location_id': SEED_IDS['loc_store'],
                'to_location_id': SEED_IDS['loc_store'],
                'quantity': pos_qty,
                'reference_type': 'SALES_ORDER',
                'reference_id': pos_so_id,
                'notes': 'Simulated POS sale consumption',
                'created_by': 'seed.sim',
                'created_at': datetime(seed_date.year, seed_date.month, seed_date.day, 9, 20, 0),
            }
        )

        online_so_id = sim_uuid(f'sim-so-online-{day_str}')
        online_line_id = sim_uuid(f'sim-sol-online-{day_str}')
        online_qty_int = 3 + (day_idx % 5)
        online_qty = Decimal(f'{online_qty_int}.000')
        online_total = (Decimal(online_qty_int) * SIM_ONLINE_UNIT_PRICE).quantize(Decimal('0.01'))
        online_fulfilled = day_idx % 3 == 0
        sim_sales_orders.append(
            {
                'sales_order_id': online_so_id,
                'order_number': f'SO-SIM-ONL-{seed_date.strftime("%Y%m%d")}-001',
                'order_date': datetime(seed_date.year, seed_date.month, seed_date.day, 11, 10, 0),
                'sales_channel': 'ONLINE',
                'customer_id': f'cust-sim-online-{day_idx + 1:03d}',
                'location_id': SEED_IDS['loc_store'],
                'status': 'FULFILLED' if online_fulfilled else 'CONFIRMED',
                'total_amount': online_total,
                'currency': 'USD',
                'notes': 'Simulated online order flow',
            }
        )
        sim_order_lines.append(
            {
                'line_id': online_line_id,
                'sales_order_id': online_so_id,
                'product_id': SEED_IDS['prod_coffee_latte'],
                'sku': 'LIT-COF-LATTE',
                'quantity_ordered': online_qty,
                'quantity_fulfilled': online_qty if online_fulfilled else Decimal('0.000'),
                'unit_price': SIM_ONLINE_UNIT_PRICE,
                'line_total': online_total,
                'status': 'FULFILLED' if online_fulfilled else 'RESERVED',
            }
        )
        sim_payments.append(
            {
                'payment_id': sim_uuid(f'sim-pay-online-{day_str}'),
                'sales_order_id': online_so_id,
                'payment_method': 'DIGITAL',
                'amount': online_total,
                'status': 'CAPTURED' if online_fulfilled else 'AUTHORIZED',
                'transaction_ref': f'SIM-TXN-ONL-{seed_date.strftime("%Y%m%d")}-001',
            }
        )
        if online_fulfilled:
            sim_movements.append(
                {
                    'movement_id': sim_uuid(f'sim-mov-online-out-{day_str}'),
                    'product_id': SEED_IDS['prod_coffee_latte'],
                    'sku': 'LIT-COF-LATTE',
                    'movement_type': 'OUT',
                    'from_location_id': SEED_IDS['loc_store'],
                    'to_location_id': SEED_IDS['loc_store'],
                    'quantity': online_qty,
                    'reference_type': 'SALES_ORDER',
                    'reference_id': online_so_id,
                    'notes': 'Simulated online fulfillment',
                    'created_by': 'seed.sim',
                    'created_at': datetime(seed_date.year, seed_date.month, seed_date.day, 12, 30, 0),
                }
            )
        else:
            sim_reservations.append(
                {
                    'reservation_id': sim_uuid(f'sim-res-online-{day_str}'),
                    'sales_order_id': online_so_id,
                    'sales_order_line_id': online_line_id,
                    'product_id': SEED_IDS['prod_coffee_latte'],
                    'sku': 'LIT-COF-LATTE',
                    'location_id': SEED_IDS['loc_store'],
                    'quantity': online_qty,
                    'status': 'RESERVED',
                }
            )

        if day_idx % 2 == 0:
            b2b_so_id = sim_uuid(f'sim-so-b2b-{day_str}')
            b2b_line_id = sim_uuid(f'sim-sol-b2b-{day_str}')
            b2b_qty_int = 18 + (day_idx % 4) * 6
            b2b_qty = Decimal(f'{b2b_qty_int}.000')
            b2b_total = (Decimal(b2b_qty_int) * SIM_B2B_UNIT_PRICE).quantize(Decimal('0.01'))
            sim_sales_orders.append(
                {
                    'sales_order_id': b2b_so_id,
                    'order_number': f'SO-SIM-B2B-{seed_date.strftime("%Y%m%d")}-001',
                    'order_date': datetime(seed_date.year, seed_date.month, seed_date.day, 14, 45, 0),
                    'sales_channel': 'B2B',
                    'customer_id': f'cust-sim-b2b-{day_idx // 2 + 1:03d}',
                    'location_id': SEED_IDS['loc_store'],
                    'status': 'FULFILLED',
                    'total_amount': b2b_total,
                    'currency': 'USD',
                    'notes': 'Simulated office/catering volume order',
                }
            )
            sim_order_lines.append(
                {
                    'line_id': b2b_line_id,
                    'sales_order_id': b2b_so_id,
                    'product_id': SEED_IDS['prod_coffee_latte'],
                    'sku': 'LIT-COF-LATTE',
                    'quantity_ordered': b2b_qty,
                    'quantity_fulfilled': b2b_qty,
                    'unit_price': SIM_B2B_UNIT_PRICE,
                    'line_total': b2b_total,
                    'status': 'FULFILLED',
                }
            )
            sim_payments.append(
                {
                    'payment_id': sim_uuid(f'sim-pay-b2b-{day_str}'),
                    'sales_order_id': b2b_so_id,
                    'payment_method': 'OTHER',
                    'amount': b2b_total,
                    'status': 'PENDING',
                    'transaction_ref': f'SIM-INV-B2B-{seed_date.strftime("%Y%m%d")}-001',
                }
            )
            sim_movements.append(
                {
                    'movement_id': sim_uuid(f'sim-mov-b2b-out-{day_str}'),
                    'product_id': SEED_IDS['prod_coffee_latte'],
                    'sku': 'LIT-COF-LATTE',
                    'movement_type': 'OUT',
                    'from_location_id': SEED_IDS['loc_store'],
                    'to_location_id': SEED_IDS['loc_store'],
                    'quantity': b2b_qty,
                    'reference_type': 'SALES_ORDER',
                    'reference_id': b2b_so_id,
                    'notes': 'Simulated B2B fulfillment',
                    'created_by': 'seed.sim',
                    'created_at': datetime(seed_date.year, seed_date.month, seed_date.day, 15, 0, 0),
                }
            )

    op.bulk_insert(
        pos_terminal,
        [
            {
                'terminal_id': sim_terminal_id,
                'location_id': SEED_IDS['loc_store'],
                'terminal_code': 'POS-STR-JKT-02',
                'device_name': 'Store Counter POS #2',
                'is_active': True,
            }
        ],
    )
    op.bulk_insert(pos_shift, sim_pos_shifts)
    op.bulk_insert(work_order, sim_work_orders)
    op.bulk_insert(production_run, sim_production_runs)
    op.bulk_insert(inventory_movement, sim_movements)
    op.bulk_insert(sales_order, sim_sales_orders)
    op.bulk_insert(sales_order_line, sim_order_lines)
    if sim_reservations:
        op.bulk_insert(inventory_reservation, sim_reservations)
    op.bulk_insert(payment, sim_payments)
    op.bulk_insert(receipt, sim_receipts)


def downgrade() -> None:
    """Remove seed data inserted by this revision."""
    bind = op.get_bind()

    bind.execute(
        sa.text("DELETE FROM production_run WHERE operator_id LIKE 'operator-prod-sim-%'")
    )
    bind.execute(
        sa.text("DELETE FROM work_order WHERE work_order_number LIKE 'WO-SIM-%'")
    )
    bind.execute(
        sa.text("DELETE FROM receipt WHERE receipt_number LIKE 'RCPT-SIM-%'")
    )
    bind.execute(
        sa.text("DELETE FROM payment WHERE transaction_ref LIKE 'SIM-%'")
    )
    bind.execute(
        sa.text(
            "DELETE FROM inventory_reservation WHERE sales_order_id IN ("
            "SELECT sales_order_id FROM sales_order WHERE order_number LIKE 'SO-SIM-%'"
            ")"
        )
    )
    bind.execute(
        sa.text(
            "DELETE FROM sales_order_line WHERE sales_order_id IN ("
            "SELECT sales_order_id FROM sales_order WHERE order_number LIKE 'SO-SIM-%'"
            ")"
        )
    )
    bind.execute(
        sa.text("DELETE FROM sales_order WHERE order_number LIKE 'SO-SIM-%'")
    )
    bind.execute(
        sa.text("DELETE FROM inventory_movement WHERE created_by = 'seed.sim'")
    )
    bind.execute(
        sa.text("DELETE FROM pos_shift WHERE operator_id LIKE 'operator-sim-%'")
    )
    bind.execute(
        sa.text("DELETE FROM pos_terminal WHERE terminal_code = 'POS-STR-JKT-02'")
    )

    bind.execute(sa.text("DELETE FROM production_run WHERE run_id = :id"), {'id': SEED_IDS['run_001']})
    bind.execute(
        sa.text("DELETE FROM work_order WHERE work_order_id IN (:id1, :id2)"),
        {'id1': SEED_IDS['wo_001'], 'id2': SEED_IDS['wo_002']},
    )
    bind.execute(
        sa.text(
            "DELETE FROM bom_line WHERE bom_line_id IN (:id1, :id2, :id3, :id4)"
        ),
        {
            'id1': SEED_IDS['bom_line_bean'],
            'id2': SEED_IDS['bom_line_milk'],
            'id3': SEED_IDS['bom_line_cup'],
            'id4': SEED_IDS['bom_line_filter'],
        },
    )
    bind.execute(sa.text("DELETE FROM bill_of_material WHERE bom_id = :id"), {'id': SEED_IDS['bom_latte_v1']})
    bind.execute(sa.text("DELETE FROM receipt WHERE receipt_id = :id"), {'id': SEED_IDS['receipt_pos_001']})
    bind.execute(sa.text("DELETE FROM pos_shift WHERE shift_id = :id"), {'id': SEED_IDS['pos_shift_1']})
    bind.execute(sa.text("DELETE FROM pos_terminal WHERE terminal_id = :id"), {'id': SEED_IDS['pos_terminal_1']})
    bind.execute(
        sa.text("DELETE FROM payment WHERE payment_id IN (:id1, :id2, :id3)"),
        {
            'id1': SEED_IDS['pay_pos_001'],
            'id2': SEED_IDS['pay_online_001'],
            'id3': SEED_IDS['pay_b2b_001'],
        },
    )
    bind.execute(
        sa.text("DELETE FROM inventory_reservation WHERE reservation_id = :id"),
        {'id': SEED_IDS['res_online_001']},
    )
    bind.execute(
        sa.text("DELETE FROM sales_order_line WHERE line_id IN (:id1, :id2, :id3)"),
        {
            'id1': SEED_IDS['sol_pos_001'],
            'id2': SEED_IDS['sol_online_001'],
            'id3': SEED_IDS['sol_b2b_001'],
        },
    )
    bind.execute(
        sa.text("DELETE FROM sales_order WHERE sales_order_id IN (:id1, :id2, :id3)"),
        {
            'id1': SEED_IDS['so_pos_001'],
            'id2': SEED_IDS['so_online_001'],
            'id3': SEED_IDS['so_b2b_001'],
        },
    )
    bind.execute(
        sa.text(
            "DELETE FROM inventory_movement WHERE movement_id IN "
            "(:id1, :id2, :id3, :id4, :id5, :id6, :id7, :id8, :id9)"
        ),
        {
            'id1': SEED_IDS['mov_open_bean'],
            'id2': SEED_IDS['mov_open_milk'],
            'id3': SEED_IDS['mov_open_cup'],
            'id4': SEED_IDS['mov_open_filter'],
            'id5': SEED_IDS['mov_transfer_bean'],
            'id6': SEED_IDS['mov_transfer_milk'],
            'id7': SEED_IDS['mov_transfer_cup'],
            'id8': SEED_IDS['mov_transfer_filter'],
            'id9': SEED_IDS['mov_production_out'],
        },
    )
    bind.execute(
        sa.text("DELETE FROM location WHERE location_id IN (:id1, :id2, :id3)"),
        {
            'id1': SEED_IDS['loc_wh'],
            'id2': SEED_IDS['loc_store'],
            'id3': SEED_IDS['loc_prod'],
        },
    )
    bind.execute(
        sa.text("DELETE FROM product_variant WHERE variant_id IN (:id1, :id2, :id3)"),
        {
            'id1': SEED_IDS['var_latte_hot_m'],
            'id2': SEED_IDS['var_latte_hot_l'],
            'id3': SEED_IDS['var_latte_ice_l'],
        },
    )
    bind.execute(
        sa.text("DELETE FROM product WHERE product_id IN (:id1, :id2, :id3, :id4, :id5, :id6)"),
        {
            'id1': SEED_IDS['prod_coffee_latte'],
            'id2': SEED_IDS['prod_coffee_bean'],
            'id3': SEED_IDS['prod_milk'],
            'id4': SEED_IDS['prod_cup'],
            'id5': SEED_IDS['prod_filter'],
            'id6': SEED_IDS['prod_latte_art'],
        },
    )
    bind.execute(
        sa.text("DELETE FROM unit_of_measure WHERE uom_id IN (:id1, :id2, :id3, :id4)"),
        {
            'id1': SEED_IDS['uom_unit'],
            'id2': SEED_IDS['uom_kg'],
            'id3': SEED_IDS['uom_liter'],
            'id4': SEED_IDS['uom_hour'],
        },
    )
