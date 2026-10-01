## Project Summary

Literp is currently a Kotlin and Vert.x backend that implements a lightweight ERP core focused on catalog, location, and order-to-fulfillment flows.

### Current runtime characteristics

- Kotlin `2.4.20` on Java `25`
- Vert.x `5.2.0`
- PostgreSQL via Vert.x PG client
- RxJava3 for asynchronous repository flows
- OpenAPI-based routing

### What is implemented

- utility endpoints for index, metrics, liveness, readiness, and database health
- 43 registered API operations: 41 implemented and two authenticated receipt/refund write placeholders, across:
  - Unit of Measure
  - Product
  - Product Variant
  - Location
  - Order Process
  - POS Operations (terminals, shifts and scoped receipt reads)
- application-side authentication and authorization; provider/deployment acceptance remains pending

### Data model currently present

- product catalog and UOM
- multi-location inventory
- immutable inventory movement ledger
- sales orders, lines, reservations, and payments
- POS terminal, shift, and receipt tables
- manufacturing tables for BOM, work orders, and production runs

### Supporting assets

- Alembic schema migration
- deterministic Alembic seed data migration
- OpenAPI specs in `api_collections/open_api_spec`
- Bruno collection in `api_collections/Literp`
- automated foundation, master-data, order-flow, POS, security and contract tests; their presence is not a new acceptance run

### Current implementation caveats

- receipt generation and refund writes remain authenticated 501 placeholders
- manufacturing APIs are design only; partial fulfillment and offline synchronization remain deferred
- shared stock-writer serialization and stable catalog unit/type guards are proposed prerequisites, not implemented guarantees
- Phase 04 repository request correlation and external CI required-check enforcement remain open
- see the [ADS coverage review](../implementation-plan/ADS_COVERAGE_REVIEW.md) for requirement gaps and current evidence boundaries
