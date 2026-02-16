# Database Model Design

## Fundamental Architecture

Based on the project's core principles:

1. **POS is a channel**, not the system center
2. **Inventory is movement-based** – immutable ledger, not mutable counters
3. **Sales create intent**, inventory executes state changes
4. **Manufacturing is an extension**, not a fork
5. **Multi-location awareness** from inception

---

## Core Entities

### 1. Product Catalog

#### Product
The base product entity. Supports SKU variants and multiple UOM.

**Fields:**
- `product_id` (UUID, PK)
- `sku` (unique, indexed) – primary SKU for the product
- `name` (string)
- `product_type` (ENUM: STOCK | SERVICE) – determines if inventory applies
- `base_uom` (FK to unit_of_measure) – default unit for quantities
- `active` (boolean, default=true)
- `metadata` (JSONB, optional) – extensible attributes
- `created_at`, `updated_at`

#### ProductVariant
Represents product variants (sizes, colors, etc.). Each variant has its own SKU.

**Fields:**
- `variant_id` (UUID, PK)
- `product_id` (FK, cascade delete)
- `sku` (unique, indexed)
- `name` (string)
- `attributes` (JSONB) – variant-specific data (e.g., size, color)
- `active` (boolean)
- `created_at`, `updated_at`

#### UnitOfMeasure
Standard units (EA, KG, LTR, etc.).

**Fields:**
- `uom_id` (UUID, PK)
- `code` (string, unique) – EA, KG, etc.
- `name` (string)
- `base_unit` (string, optional) – for conversions

---

### 2. Inventory System (Movement-Based)

#### Location
Multi-location warehouses, stores, or sites.

**Fields:**
- `location_id` (UUID, PK)
- `code` (string, unique)
- `name` (string)
- `location_type` (ENUM: WAREHOUSE | STORE | PRODUCTION)
- `is_active` (boolean)
- `address` (JSONB, optional)
- `created_at`, `updated_at`

#### InventoryMovement (Immutable Ledger)
Immutable log of all stock movements. Stock levels are derived by summing movements.

**Fields:**
- `movement_id` (UUID, PK)
- `product_id` (FK) – relates to product or variant SKU
- `sku` (string, indexed) – denormalized for performance
- `movement_type` (ENUM: IN | OUT | TRANSFER | ADJUSTMENT)
  - IN: goods arrival, work order completion
  - OUT: sales fulfillment, consumption
  - TRANSFER: between locations
  - ADJUSTMENT: inventory correction
- `from_location_id` (FK, nullable) – source location
- `to_location_id` (FK) – destination location
- `quantity` (decimal)
- `reference_type` (ENUM: SALES_ORDER | WORK_ORDER | PURCHASE_ORDER | ADJUSTMENT | TRANSFER)
- `reference_id` (string) – FK to order/work-order/etc.
- `notes` (text, optional)
- `created_by` (string, optional) – user/system identifier
- `created_at` (timestamp, immutable)

**Index:** (product_id, to_location_id, created_at)

#### InventoryReservation
Tracks stock reserved for sales orders. Decouples sales intent from fulfillment.

**Fields:**
- `reservation_id` (UUID, PK)
- `sales_order_id` (FK)
- `sales_order_line_id` (FK)
- `product_id` (FK)
- `sku` (string, indexed)
- `location_id` (FK) – where stock is reserved from
- `quantity` (decimal)
- `status` (ENUM: RESERVED | FULFILLED | CANCELLED)
- `created_at`, `updated_at`

---

### 3. Sales & Order Management

#### SalesOrder
Represents customer orders (POS, online, B2B). Captures sales intent.

**Fields:**
- `sales_order_id` (UUID, PK)
- `order_number` (string, unique, indexed) – human-readable ID
- `order_date` (timestamp)
- `sales_channel` (ENUM: POS | ONLINE | B2B | OTHER) – which channel created this
- `customer_id` (string, nullable) – FK to customer (external CRM integration)
- `location_id` (FK) – which location this order is for
- `status` (ENUM: DRAFT | CONFIRMED | FULFILLED | CANCELLED)
- `total_amount` (decimal)
- `currency` (string, default USD)
- `notes` (text, optional)
- `created_at`, `updated_at`

#### SalesOrderLine
Individual line items in a sales order.

**Fields:**
- `line_id` (UUID, PK)
- `sales_order_id` (FK, cascade delete)
- `product_id` (FK)
- `sku` (string, indexed)
- `quantity_ordered` (decimal)
- `quantity_fulfilled` (decimal, default=0)
- `unit_price` (decimal)
- `line_total` (decimal)
- `status` (ENUM: PENDING | RESERVED | FULFILLED | CANCELLED)
- `created_at`, `updated_at`

#### Payment
Payment records for orders.

**Fields:**
- `payment_id` (UUID, PK)
- `sales_order_id` (FK)
- `payment_method` (ENUM: CASH | CARD | DIGITAL | GIFT_CARD | OTHER)
- `amount` (decimal)
- `status` (ENUM: PENDING | AUTHORIZED | CAPTURED | REFUNDED)
- `transaction_ref` (string, nullable) – payment gateway reference
- `created_at`, `updated_at`

---

### 4. POS Operations

#### POSTerminal
Tracks POS registers/devices.

**Fields:**
- `terminal_id` (UUID, PK)
- `location_id` (FK)
- `terminal_code` (string, unique)
- `device_name` (string)
- `is_active` (boolean)
- `created_at`, `updated_at`

#### POSShift
Shift management for cashiers/operators.

**Fields:**
- `shift_id` (UUID, PK)
- `terminal_id` (FK)
- `operator_id` (string) – user identifier
- `shift_date` (date)
- `shift_number` (int) – 1st, 2nd, 3rd shift
- `opened_at` (timestamp)
- `closed_at` (timestamp, nullable)
- `opening_balance` (decimal)
- `closing_balance` (decimal, nullable)
- `status` (ENUM: OPEN | CLOSED)
- `created_at`

#### Receipt
Receipt/Invoice records. Denormalized copy of order for offline storage.

**Fields:**
- `receipt_id` (UUID, PK)
- `sales_order_id` (FK)
- `shift_id` (FK)
- `receipt_number` (string, unique) – sequential
- `receipt_date` (timestamp)
- `total_items` (int)
- `subtotal` (decimal)
- `tax_amount` (decimal)
- `total_amount` (decimal)
- `receipt_data` (JSONB) – full JSON copy for offline use
- `created_at`

---

### 5. Manufacturing Extension (Future)

#### BillOfMaterial
Recipe for assembled or manufactured products.

**Fields:**
- `bom_id` (UUID, PK)
- `product_id` (FK) – finished good
- `bom_version` (int)
- `status` (ENUM: DRAFT | ACTIVE | DEPRECATED)
- `created_at`, `updated_at`

#### BOMLine
Components in a BOM.

**Fields:**
- `bom_line_id` (UUID, PK)
- `bom_id` (FK, cascade delete)
- `component_product_id` (FK)
- `component_sku` (string)
- `quantity_per_unit` (decimal) – how many of this component per finished good
- `scrap_percentage` (decimal, default=0)
- `sequence` (int) – order in BOM
- `created_at`, `updated_at`

#### WorkOrder
Production order to manufacture goods.

**Fields:**
- `work_order_id` (UUID, PK)
- `work_order_number` (string, unique, indexed)
- `product_id` (FK) – what to produce
- `bom_id` (FK)
- `location_id` (FK) – where to produce
- `planned_quantity` (decimal)
- `actual_quantity` (decimal, nullable)
- `status` (ENUM: PLANNED | IN_PROGRESS | COMPLETED | CANCELLED)
- `planned_start` (timestamp)
- `actual_start` (timestamp, nullable)
- `planned_end` (timestamp)
- `actual_end` (timestamp, nullable)
- `notes` (text)
- `created_at`, `updated_at`

#### ProductionRun
Execution details of a work order (batch/lot tracking).

**Fields:**
- `run_id` (UUID, PK)
- `work_order_id` (FK)
- `run_date` (date)
- `operator_id` (string, optional)
- `material_consumed` (JSONB) – detailed component usage
- `output_quantity` (decimal)
- `scrap_quantity` (decimal)
- `status` (ENUM: IN_PROGRESS | COMPLETED)
- `created_at`, `updated_at`

---

## Key Design Decisions

### Inventory as Immutable Ledger
- **Why:** Full auditability, supports offline POS, manufacturing-friendly
- **How:** InventoryMovement table records every state change; stock levels are computed via SUM queries
- **Performance:** Indexed on (product_id, location_id, created_at) for efficient stock rollups

### Sales Deferred from Inventory
- **Why:** Allows reservations, offline order capture, manufacturing-driven availability
- **How:** SalesOrder + InventoryReservation + InventoryMovement are separate
  - Sales intent → SalesOrder + InventoryReservation
  - Fulfillment → InventoryMovement (OUT from location)
  - Reserved stock is not available for other orders

### SKU Denormalization
- **Why:** Performance; quick lookups without joins
- **How:** SKU stored in both Product, ProductVariant, InventoryMovement, SalesOrderLine
- **Maintenance:** Unique constraint on product.sku; variants get their own sku

### Location-Aware from Day One
- **Why:** Multi-branch, manufacturing, and transfers are core use cases
- **How:** Every inventory movement has from_location and to_location
  - POS sales pull from a location
  - Work orders produce into a location
  - Transfers move between locations

### JSONB for Extensibility
- **Where:** Product/ProductVariant attributes, Receipt data, Production material consumed
- **Why:** Allows schema evolution without table migrations
- **Trade-off:** Loses strong typing, but gains flexibility for future channels

---

## Queries & Views (Derived)

### Current Stock Level (by product, location)
```sql
SELECT
  product_id,
  sku,
  location_id,
  SUM(CASE WHEN movement_type IN ('IN', 'TRANSFER') AND to_location_id = location_id
           THEN quantity ELSE 0 END) -
  SUM(CASE WHEN movement_type IN ('OUT', 'TRANSFER') AND from_location_id = location_id
           THEN quantity ELSE 0 END) as available_qty
FROM inventory_movement
GROUP BY product_id, sku, location_id;
```

### Available Stock (reserved vs. unreserved)
```sql
SELECT
  m.product_id,
  m.sku,
  m.location_id,
  COALESCE(current_qty, 0) as current_qty,
  COALESCE(reserved_qty, 0) as reserved_qty,
  COALESCE(current_qty, 0) - COALESCE(reserved_qty, 0) as available_qty
FROM (... current stock ...) m
LEFT JOIN (
  SELECT product_id, location_id, SUM(quantity) as reserved_qty
  FROM inventory_reservation
  WHERE status = 'RESERVED'
  GROUP BY product_id, location_id
) r ON m.product_id = r.product_id AND m.location_id = r.location_id;
```

---

## Scalability & Future Considerations

- **Event Sourcing:** InventoryMovement table is already an event log; can emit events downstream
- **Partitioning:** InventoryMovement by date range (monthly/yearly) as it grows
- **Denormalization:** Add materialized views for stock levels if query latency becomes an issue
- **Soft Deletes:** Consider for audit (is_deleted flag) rather than hard deletes
- **Tenancy:** Add tenant_id to all tables if multi-tenant support is needed

---

## Summary

This schema embodies the project's philosophy:
- **Channels are adapters** (POS is one of many)
- **Inventory is authoritative** (movement-based ledger)
- **Sales and fulfillment are decoupled** (reservations bridge them)
- **Manufacturing is a first-class capability** (BOM, work orders, production runs)
- **Everything is auditable** (created_at, created_by, movement history)
