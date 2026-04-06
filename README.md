# Literp

Lightweight ERP core built with Vert.x, Kotlin, and PostgreSQL.

The current branch implements a POS-first sales and inventory backend with:
- 29 REST API endpoints across 5 domains
- movement-based inventory and reservation-backed order fulfillment
- deterministic Alembic seed data for local testing
- OpenAPI contracts and a synchronized Bruno collection

## Current Scope

Implemented API domains:
- Unit of Measure: 5 endpoints
- Product: 5 endpoints
- Product Variant: 5 endpoints
- Location: 6 endpoints
- Order Process: 8 endpoints

Utility endpoints:
- `GET /`
- `GET /health/db`

The order process API supports:
- draft order creation
- line insertion
- confirmation and reservation creation
- payment capture
- fulfillment with inventory movement writes
- cancellation with payment guardrails

## Stack

- Kotlin `2.3.20`
- Vert.x `5.0.10`
- Java `25`
- RxJava3
- PostgreSQL
- OpenAPI 3.0
- Alembic migrations

## Quick Start

### 1. Start PostgreSQL and run migrations

The repository already includes a root-level [`cfg.properties`](cfg.properties) that points the app at the local Docker database.

```bash
cd docker
source envrc
make network
DIR=pgsql make env-up
```

`docker/pgsql/docker-compose.yml` starts:
- `literp_pgsql`
- `literp_migration`

The migration container applies both schema and seed data automatically.

### 2. Build and run the server

```bash
./gradlew build
./gradlew run
```

Base URL:

```text
http://localhost:8010/api/v1
```

### 3. Verify the service

```bash
curl http://localhost:8010 | jq
curl http://localhost:8010/health/db | jq
curl "http://localhost:8010/api/v1/uom?page=0&size=20&sort=code,asc" | jq
curl "http://localhost:8010/api/v1/orders?page=0&size=20&sort=orderDate,desc" | jq
```

## API Assets

- Bruno collection: [`api_collections/Literp`](api_collections/Literp)
- OpenAPI specs: [`api_collections/open_api_spec`](api_collections/open_api_spec)

The Bruno collection is aligned to the implemented handlers and uses collection variables for common IDs and codes.

## Documentation

- [docs/QUICK_START.md](docs/QUICK_START.md): setup, first run, Bruno usage
- [docs/API_IMPLEMENTATION.md](docs/API_IMPLEMENTATION.md): architecture and implementation details
- [docs/API_TESTING_GUIDE.md](docs/API_TESTING_GUIDE.md): curl workflows for the implemented endpoints
- [docs/ENDPOINTS_OVERVIEW.md](docs/ENDPOINTS_OVERVIEW.md): endpoint inventory and lifecycle map
- [docs/README_API.md](docs/README_API.md): documentation index
- [docs/IMPLEMENTATION_SUMMARY.md](docs/IMPLEMENTATION_SUMMARY.md): branch-level implementation summary
- [docs/VERIFICATION_CHECKLIST.md](docs/VERIFICATION_CHECKLIST.md): validation checklist

Domain notes:
- [docs/knowledge/PROJECT_OVERVIEW.md](docs/knowledge/PROJECT_OVERVIEW.md)
- [docs/knowledge/MODEL_DESIGN.md](docs/knowledge/MODEL_DESIGN.md)
- [docs/knowledge/PROCESS_ORDER_PAYMENT_FULFILLMENT.md](docs/knowledge/PROCESS_ORDER_PAYMENT_FULFILLMENT.md)

## Project Structure

```text
literp/
├── api_collections/
│   ├── Literp/                 # Bruno collection
│   └── open_api_spec/          # OpenAPI contracts
├── docker/
│   ├── Makefile
│   ├── envrc
│   ├── jvm/
│   └── pgsql/
├── docs/
├── python/database/migration/  # Alembic schema + seed data
├── src/main/java/com/literp/service/
│   ├── master/
│   └── order/
└── src/main/kotlin/com/literp/
    ├── db/
    ├── repository/
    ├── service/
    └── verticle/
```

## Implementation Notes

- Products and variants use soft delete.
- Locations and UOMs use hard delete.
- Sales orders use status transitions instead of deletion.
- Seed data includes catalog, locations, inventory movements, orders, payments, POS records, and manufacturing records.

Current implementation caveats:
- List endpoints return an outer `data` envelope around the repository payload.
- Master-data single-resource endpoints currently return `data.data`.
- `GET /products` only honors `page`, `size`, and `sort` even though the OpenAPI spec documents additional filters.
- Location `isActive`, product `active`, and product `baseUom` update fields are documented in OpenAPI but not currently applied by the handlers.
- Order fulfillment writes `inventory_movement.to_location_id` with the same location as `from_location_id` because the schema requires a non-null destination.
- Confirm, fulfill, and cancel flows are multi-step operations and are not yet wrapped in explicit database transactions.
