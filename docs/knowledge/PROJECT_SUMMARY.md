## Project Summary

Literp is currently a Kotlin and Vert.x backend that implements a lightweight ERP core focused on catalog, location, and order-to-fulfillment flows.

### Current runtime characteristics

- Kotlin `2.3.20` on Java `25`
- Vert.x `5.0.10`
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

### Current implementation caveats

- some endpoint response envelopes are still inconsistent
- some OpenAPI fields are ahead of the current handler behavior
- receipt, refund, and partial-fulfillment APIs are not yet exposed
