# Database Model Design

## Fundamental Architecture

Based on the project's core principles:

1. **POS is a channel**, not the system center
2. **Inventory is movement-based** – immutable ledger, not mutable counters
3. **Sales create intent**, inventory executes state changes
4. **Manufacturing is an extension**, not a fork
5. **Multi-location awareness** from inception

---

## Model and Schema Boundary

This is a domain model, not a complete migration catalog. Initial entities remain below; later migrations add order command/event, POS attribution/reconciliation, and receipt/refund foundations. In particular, `c7a8e2f4d6b1_pos_receipt_refund_foundation.py` adds decimal receipt item totals and legacy-compatible issuance/refund metadata without enabling receipt/refund writes. Consult migrations for physical constraints and the [ADS coverage review](../implementation-plan/ADS_COVERAGE_REVIEW.md) for proposed versus implemented behavior.

Manufacturing APIs remain proposed. A work-order/run COMPLETED state or historical `material_consumed` JSON is not proof of a managed stock posting. The aligned 05.5/05.6 proposal separates immutable execution results from explicit run-level posting, applies POSTED-before-close only to new stock-policy orders, and excludes legacy records from automatic adoption/replay.

## Core Entities

### 1. Product Catalog

#### Product
The base product entity has SKU variants and one referenced base UOM. Alternate-unit conversion is not implemented by this model.

**Fields:**
- `product_id` (UUID, PK)
- `sku` (unique, indexed) – primary SKU for the product
- `name` (string)
- `product_type` (ENUM: STOCK | SERVICE) – determines if inventory applies
- `base_uom` (FK → `unit_of_measure.uom_id`) – default unit for quantities
- `active` (boolean, default=true)
- `metadata` (JSON, optional) – extensible attributes
- `created_at`, `updated_at`

#### ProductVariant
Represents product variants (sizes, colors, etc.). Each variant has its own SKU.

**Fields:**
- `variant_id` (UUID, PK)
- `product_id` (FK → `product.product_id`, cascade delete)
- `sku` (unique, indexed)
- `name` (string)
- `attributes` (JSON) – variant-specific data (e.g., size, color)
- `active` (boolean)
- `created_at`, `updated_at`

#### UnitOfMeasure
Standard units (EA, KG, LTR, etc.).

**Fields:**
- `uom_id` (UUID, PK)
- `code` (string, unique) – EA, KG, etc.
- `name` (string)
- `base_unit` (string, optional) – for conversions
- `created_at`, `updated_at`

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
- `address` (JSON, optional)
- `created_at`, `updated_at`

#### InventoryMovement (Immutable Ledger)
Immutable log of all stock movements. Stock levels are derived by summing movements.

**Fields:**
- `movement_id` (UUID, PK)
- `product_id` (FK → `product.product_id`) – relates to product or variant SKU
- `sku` (string, indexed) – denormalized for performance
- `movement_type` (ENUM: IN | OUT | TRANSFER | ADJUSTMENT)
  - IN: goods arrival, manufacturing output posting (not execution completion by itself)
  - OUT: sales fulfillment, consumption
  - TRANSFER: between locations
  - ADJUSTMENT: inventory correction
- `from_location_id` (FK → `location.location_id`, nullable) – source location
- `to_location_id` (FK → `location.location_id`, nullable for OUT rows) – destination location
- `quantity` (decimal)
- `reference_type` (ENUM: SALES_ORDER | WORK_ORDER | PURCHASE_ORDER | ADJUSTMENT | TRANSFER)
- `reference_id` (string) – reference to order/work-order/etc. (not FK due to polymorphic nature)
- `notes` (text, optional)
- `created_by` (string, optional) – user/system identifier
- `created_at` (timestamp, immutable)

**Index:** (product_id, to_location_id, created_at)

#### InventoryReservation
Tracks stock reserved for sales orders. Decouples sales intent from fulfillment.

**Fields:**
- `reservation_id` (UUID, PK)
- `sales_order_id` (FK → `sales_order.sales_order_id`, cascade delete)
- `sales_order_line_id` (FK → `sales_order_line.line_id`, cascade delete)
- `product_id` (FK → `product.product_id`)
- `sku` (string, indexed)
- `location_id` (FK → `location.location_id`) – where stock is reserved from
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
- `customer_id` (string, nullable) – external customer identifier (no FK intentional for flexibility)
- `location_id` (FK → `location.location_id`) – which location this order is for
- `status` (ENUM: DRAFT | CONFIRMED | FULFILLED | CANCELLED)
- `total_amount` (decimal)
- `currency` (string, default USD)
- `notes` (text, optional)
- `created_at`, `updated_at`

#### SalesOrderLine
Individual line items in a sales order.

**Fields:**
- `line_id` (UUID, PK)
- `sales_order_id` (FK → `sales_order.sales_order_id`, cascade delete)
- `product_id` (FK → `product.product_id`)
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
- `sales_order_id` (FK → `sales_order.sales_order_id`)
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
- `location_id` (FK → `location.location_id`)
- `terminal_code` (string, unique)
- `device_name` (string)
- `is_active` (boolean)
- `created_at`, `updated_at`

#### POSShift
Shift management for cashiers/operators.

**Fields:**
- `shift_id` (UUID, PK)
- `terminal_id` (FK → `pos_terminal.terminal_id`)
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
- `sales_order_id` (FK → `sales_order.sales_order_id`)
- `shift_id` (FK → `pos_shift.shift_id`, nullable)
- `receipt_number` (string, unique) – sequential
- `receipt_date` (timestamp)
- `total_items` (numeric(12,3)) – summed item quantity; widened from integer by receipt/refund foundation migration
- `subtotal` (decimal)
- `tax_amount` (decimal)
- `total_amount` (decimal)
- `receipt_data` (JSON) – full JSON copy for offline use
- `created_at`

---

### 5. Manufacturing Extension (Future)

#### BillOfMaterial
Recipe for assembled or manufactured products.

**Fields:**
- `bom_id` (UUID, PK)
- `product_id` (FK → `product.product_id`) – finished good
- `bom_version` (int)
- `status` (ENUM: DRAFT | ACTIVE | DEPRECATED)
- `created_at`, `updated_at`

#### BOMLine
Components in a BOM.

**Fields:**
- `bom_line_id` (UUID, PK)
- `bom_id` (FK → `bill_of_material.bom_id`, cascade delete)
- `component_product_id` (FK → `product.product_id`)
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
- `product_id` (FK → `product.product_id`) – what to produce
- `bom_id` (FK → `bill_of_material.bom_id`)
- `location_id` (FK → `location.location_id`) – where to produce
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
- `work_order_id` (FK → `work_order.work_order_id`)
- `run_date` (date)
- `operator_id` (string, optional)
- `material_consumed` (JSON) – legacy usage data; proposed managed posting projection is calculated BOM backflush, not measured usage or standalone posting proof
- `output_quantity` (decimal)
- `scrap_quantity` (decimal)
- `status` (ENUM: IN_PROGRESS | COMPLETED)
- `created_at`, `updated_at`

---

## Foreign Key Relationships Summary

This section summarizes the initial domain relationships, not all constraints added by later migrations. POS attribution, command/event, issuance and refund tables must be inspected in their owning migrations.

### Product Catalog
| From | To | Cascade Delete |
|------|-----|---|---|
| `product.base_uom` | `unit_of_measure.uom_id` | No |
| `product_variant.product_id` | `product.product_id` | Yes |

### Inventory System
| From | To | Cascade Delete |
|------|-----|---|
| `inventory_movement.product_id` | `product.product_id` | No |
| `inventory_movement.from_location_id` | `location.location_id` | No |
| `inventory_movement.to_location_id` | `location.location_id` | No |
| `inventory_reservation.sales_order_id` | `sales_order.sales_order_id` | Yes |
| `inventory_reservation.sales_order_line_id` | `sales_order_line.line_id` | Yes |
| `inventory_reservation.product_id` | `product.product_id` | No |
| `inventory_reservation.location_id` | `location.location_id` | No |

### Sales & Orders
| From | To | Cascade Delete |
|------|-----|---|
| `sales_order.location_id` | `location.location_id` | No |
| `sales_order_line.sales_order_id` | `sales_order.sales_order_id` | Yes |
| `sales_order_line.product_id` | `product.product_id` | No |
| `payment.sales_order_id` | `sales_order.sales_order_id` | No |

### POS Operations
| From | To | Cascade Delete |
|------|-----|---|
| `pos_terminal.location_id` | `location.location_id` | No |
| `pos_shift.terminal_id` | `pos_terminal.terminal_id` | No |
| `receipt.sales_order_id` | `sales_order.sales_order_id` | No |
| `receipt.shift_id` | `pos_shift.shift_id` | No |

### Manufacturing
| From | To | Cascade Delete |
|------|-----|---|
| `bill_of_material.product_id` | `product.product_id` | No |
| `bom_line.bom_id` | `bill_of_material.bom_id` | Yes |
| `bom_line.component_product_id` | `product.product_id` | No |
| `work_order.product_id` | `product.product_id` | No |
| `work_order.bom_id` | `bill_of_material.bom_id` | No |
| `work_order.location_id` | `location.location_id` | No |
| `production_run.work_order_id` | `work_order.work_order_id` | No |

---

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

### JSON for Extensibility
- **Where:** Product metadata, ProductVariant attributes, location address, receipt data, and production material consumed use SQL JSON in the initial migration, not JSONB.
- **Why:** Extensible payload shape; changing required semantics still requires versioning and validation.
- **Trade-off:** Flexibility does not replace typed financial, identity or posting invariants. JSONB-specific queries/indexes require an explicitly approved storage change.

---

## Queries & Views (Derived)

### Current Stock Level (by product, location)

The following reflects `OrderProcessRepository.getCurrentStock` at the review baseline; `$1` is product ID and `$2` location ID. Movements have source/destination columns, not a `location_id` column. Quantities follow the existing signed ADJUSTMENT convention; do not change adjustment or transfer semantics implicitly in manufacturing.

```sql
SELECT COALESCE(SUM(
  CASE
    WHEN movement_type IN ('IN', 'ADJUSTMENT') AND to_location_id = $2 THEN quantity
    WHEN movement_type = 'TRANSFER' AND to_location_id = $2 THEN quantity
    WHEN movement_type = 'TRANSFER' AND from_location_id = $2 THEN -quantity
    WHEN movement_type = 'OUT' AND from_location_id = $2 THEN -quantity
    ELSE 0
  END
), 0) AS current_qty
FROM inventory_movement
WHERE product_id = $1 AND (to_location_id = $2 OR from_location_id = $2);
```

### Available Stock (reserved vs. unreserved)

`available_qty = current_qty - reserved_qty`, where `reserved_qty` is the coalesced sum of `inventory_reservation.quantity` for the same product/location with `status = 'RESERVED'`. `OrderProcessRepository.getAvailableStock` computes both aggregates in one statement. This formula is not a lock: concurrent check-and-write paths need a common serialization protocol, proposed in [05.6](../implementation-plan/ads/phase-05-task-6.md#shared-inventory-serialization-prerequisite).

Product base-UOM/type updates are currently allowed by the catalog API. The proposed manufacturing invariants require approved unit snapshots/reference guards and compatible reference-writer locking; this model does not claim those guards already exist.

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
