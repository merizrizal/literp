# Literp

Lightweight ERP core built with Vert.x, Kotlin, and PostgreSQL.

The current branch implements a POS-first sales and inventory backend with:
- 31 REST API endpoints across 5 domains
- movement-based inventory and reservation-backed order fulfillment
- deterministic Alembic seed data for local testing
- OpenAPI contracts and a synchronized Bruno collection

## Current Scope

Implemented API domains:
- Unit of Measure: 5 endpoints
- Product: 5 endpoints
- Product Variant: 5 endpoints
- Location: 6 endpoints
- Order Process: 10 endpoints

Utility endpoints:
- `GET /`
- `GET /metrics`
- `GET /health/live`
- `GET /health/ready`
- `GET /health/db`

The order process API supports:
- draft order creation
- line insertion
- confirmation and reservation creation
- payment capture
- fulfillment with inventory movement writes
- cancellation with payment guardrails
- current and available stock queries

## Stack

- Kotlin `2.4.20`
- Vert.x `5.1.8`
- Java `25`
- RxJava3
- PostgreSQL
- OpenAPI 3.0
- Alembic migrations

## Quick Start

### 1. Start PostgreSQL and run migrations

Create a local config file from the committed template:

```bash
cp cfg.properties.template cfg.properties
```

Then start the local development database:

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
# Public probes
curl http://localhost:8010 | jq
curl http://localhost:8010/health/live | jq

# Protected routes require an approved non-production access token.
: "${LITERP_ACCESS_TOKEN:?Set LITERP_ACCESS_TOKEN before calling protected routes}"
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" http://localhost:8010/health/db | jq
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "http://localhost:8010/api/v1/uom?page=0&size=20&sort=code,asc" | jq
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "http://localhost:8010/api/v1/orders?page=0&size=20&sort=orderDate,desc" | jq
```

## API Assets

- Bruno collection: [`api_collections/Literp`](api_collections/Literp)
- OpenAPI specs: [`api_collections/open_api_spec`](api_collections/open_api_spec)

The Bruno collection is aligned to the implemented handlers and uses collection variables for common IDs and codes. Protected requests inherit a bearer token from the empty `accessToken` variable; the root and liveness probes explicitly use no authentication. Never save real tokens in the repository.

## Documentation

- [docs/QUICK_START.md](docs/QUICK_START.md): setup, first run, Bruno usage
- [docs/API_IMPLEMENTATION.md](docs/API_IMPLEMENTATION.md): architecture and implementation details
- [docs/API_TESTING_GUIDE.md](docs/API_TESTING_GUIDE.md): curl workflows for the implemented endpoints
- [docs/ENDPOINTS_OVERVIEW.md](docs/ENDPOINTS_OVERVIEW.md): endpoint inventory and lifecycle map
- [docs/CI_VERIFICATION.md](docs/CI_VERIFICATION.md): required CI checks and local reproduction
- [docs/LOCAL_RESET.md](docs/LOCAL_RESET.md): non-destructive and destructive local database reset paths
- [docs/README_API.md](docs/README_API.md): documentation index
- [docs/knowledge/AUTHENTICATION_BASELINE.md](docs/knowledge/AUTHENTICATION_BASELINE.md): authentication runtime contract, operations runbook, and acceptance ledger
- [docker/keycloak/README.md](docker/keycloak/README.md): disposable real-Keycloak integration setup for local provider smoke
- [docs/IMPLEMENTATION_SUMMARY.md](docs/IMPLEMENTATION_SUMMARY.md): branch-level implementation summary
- [docs/VERIFICATION_CHECKLIST.md](docs/VERIFICATION_CHECKLIST.md): validation checklist
- [docs/implementation-plan/00-implementation-overview.md](docs/implementation-plan/00-implementation-overview.md): phased implementation plan

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
│   ├── keycloak/
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

The accepted [project structure decision](docs/knowledge/PROJECT_STRUCTURE_DECISION.md) retains this layer-based layout and the current API asset roots through Phase 05. Catalog, location, and order continue in their current layers; inventory remains with order-process behavior. When implemented, POS and manufacturing follow the same handler/repository/service layers, with their proposed Java proxy service groups created only for concrete services.

The application-side 05.0 authentication and authorization baseline is implemented and locally regression-tested. Provider-token, key-rotation, deployment/network, and maintainer acceptance evidence remains pending; keep the service isolated from untrusted networks until those gates are complete. See [AUTHENTICATION_BASELINE.md](docs/knowledge/AUTHENTICATION_BASELINE.md) for the runtime contract and acceptance ledger.

## Implementation Notes

- Products and variants use soft delete; deleting a missing or already inactive row returns `404`.
- Locations and UOMs use hard delete; missing rows return `404`, and foreign-key references return `409`.
- Sales orders use status transitions instead of deletion.
- Seed data includes catalog, locations, inventory movements, orders, payments, POS records, and manufacturing records.

Current implementation caveats:
- Master-data responses use top-level `data` and `pagination`.
- Order-process list responses still use the older outer `data` envelope.
- Master-data OpenAPI contracts are aligned with the implemented handler fields for catalog and location APIs.
- Order fulfillment writes `inventory_movement.to_location_id` with the same location as `from_location_id` because the schema requires a non-null destination.
- Confirm, fulfill, and cancel flows are multi-step operations and are not yet wrapped in explicit database transactions.
