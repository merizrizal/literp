# Literp

Lightweight ERP core built with Vert.x, Kotlin, and PostgreSQL.

The current branch implements a POS-first sales and inventory backend with:
- 43 implemented business API operations across 6 domains
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
- POS: 12 implemented operations (terminal/shift workflows, scoped receipt reads, immutable issuance, and audited local refunds with cash reconciliation)

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

- Kotlin `2.4.21`
- Vert.x `5.2.1`
- Java `25`
- RxJava3
- PostgreSQL with SCRAM client `3.4` for SASL/SCRAM authentication
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

### 2. Configure authentication before starting the server

Follow the [authentication runtime contract](docs/knowledge/AUTHENTICATION_BASELINE.md#runtime-configuration)
and provision the provider's public JWKS file. Set `LITERP_AUTH_ISSUER`,
`LITERP_AUTH_AUDIENCE`, `LITERP_AUTH_ORGANIZATION_ID`, and `LITERP_AUTH_JWKS_PATH`
in your local environment. Startup fails closed if these are absent or invalid.
For isolated local provider setup, follow [docker/keycloak/README.md](docker/keycloak/README.md).
Never commit tokens, private keys, or deployment configuration. Local setup does
not authorize public exposure or close external provider/deployment acceptance.

### 3. Build and run the server

```bash
./gradlew build
./gradlew run
```

Base URL:

```text
http://localhost:8010/api/v1
```

### 4. Verify the service

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
│   ├── order/
│   └── pos/
└── src/main/kotlin/com/literp/
    ├── db/
    ├── repository/
    ├── service/
    └── verticle/
```

The accepted [project structure decision](docs/knowledge/PROJECT_STRUCTURE_DECISION.md) retains this layer-based layout and the current API asset roots through Phase 05. Catalog, location, and order continue in their current layers; inventory remains with order-process behavior. POS uses those same handler/repository/service layers and the concrete `service/pos` proxy group. Manufacturing remains design-only; no future proxy groups are scaffolded.

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
- Fulfillment writes `OUT` movements from the order location with `to_location_id = NULL`; a sale has no internal destination.
- Confirm, payment capture, fulfill, and cancel mutations run in explicit database transactions. Same-order fulfillment is serialized; this does not establish cross-order stock serialization.
- Payment captures must be positive, fit `numeric(14,2)`, and have at most two fractional digits; invalid amounts are rejected rather than rounded.
- Receipt issuance is implemented for human issuers on exactly settled, fulfilled attributed POS orders, including after shift close. Refunds are append-only local records with payment/receipt caps, owner-controlled refund shifts and drawer-cash reconciliation; no external settlement or inventory return is claimed. See the [review repair status](docs/implementation-plan/REVIEW_REPAIR_STATUS.md) for outstanding findings.
