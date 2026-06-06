## Project Summary

Literp is currently a Kotlin and Vert.x backend that implements a lightweight ERP core focused on catalog, location, and order-to-fulfillment flows.

### Current runtime characteristics

- Kotlin `2.4.0` on Java `25`
- Vert.x `5.1.1`
- PostgreSQL via Vert.x PG client
- RxJava3 for asynchronous repository flows
- OpenAPI-based routing

### What is implemented

- utility endpoints for index and database health
- 29 API endpoints across:
  - Unit of Measure
  - Product
  - Product Variant
  - Location
  - Order Process

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
- automated foundation and master-data tests

### Current implementation caveats

- order-process list response envelopes still need normalization
- automated tests are not yet broad across the full order-to-inventory flow
- receipt, refund, and partial-fulfillment APIs are not yet exposed
