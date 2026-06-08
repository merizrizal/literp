# API Implementation

This document describes the implementation that exists on the current branch, not an aspirational target state.

## Stack

- Kotlin `2.4.0`
- Vert.x `5.1.1`
- Java `25`
- RxJava3
- PostgreSQL
- Vert.x OpenAPI RouterBuilder
- Vert.x service proxies
- Alembic for schema and seed migrations

## Modules

### Entry points

- `src/main/kotlin/com/literp/App.kt`
- `src/main/kotlin/com/literp/verticle/MainVerticle.kt`
- `src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt`

### Shared infrastructure

- `src/main/kotlin/com/literp/config/Config.kt`
- `src/main/kotlin/com/literp/db/DatabaseConnection.kt`
- `src/main/kotlin/com/literp/common/ErrorCodes.kt`

### Repositories

- `BaseRepository`
- `UnitOfMeasureRepository`
- `ProductRepository`
- `ProductVariantRepository`
- `LocationRepository`
- `OrderProcessRepository`

### Handlers

- `BaseHandler`
- `UnitOfMeasureHandler`
- `ProductHandler`
- `LocationHandler`
- `OrderProcessHandler`

### Service proxies

Java interfaces:
- `src/main/java/com/literp/service/master/*`
- `src/main/java/com/literp/service/order/*`

Kotlin implementations:
- `src/main/kotlin/com/literp/service/master/impl/*`
- `src/main/kotlin/com/literp/service/order/impl/OrderProcessServiceImpl.kt`

## Routing

The HTTP server loads 3 OpenAPI contracts:
- `api_collections/open_api_spec/product-catalog.yaml`
- `api_collections/open_api_spec/locations.yaml`
- `api_collections/open_api_spec/order-process.yaml`

It also exposes two utility routes outside `/api/v1`:
- `GET /`
- `GET /health/db`

## Implemented API Surface

### Unit of Measure

- `GET /uom`
- `POST /uom`
- `GET /uom/{uomId}`
- `PUT /uom/{uomId}`
- `DELETE /uom/{uomId}`

### Product

- `GET /products`
- `POST /products`
- `GET /products/{productId}`
- `PUT /products/{productId}`
- `DELETE /products/{productId}`

### Product Variant

- `GET /products/{productId}/variants`
- `POST /products/{productId}/variants`
- `GET /products/{productId}/variants/{variantId}`
- `PUT /products/{productId}/variants/{variantId}`
- `DELETE /products/{productId}/variants/{variantId}`

### Location

- `GET /locations`
- `POST /locations`
- `GET /locations/{locationId}`
- `GET /locations/by-code/{code}`
- `PUT /locations/{locationId}`
- `DELETE /locations/{locationId}`

### Order Process

- `GET /orders`
- `POST /orders`
- `GET /orders/{salesOrderId}`
- `POST /orders/{salesOrderId}/lines`
- `POST /orders/{salesOrderId}/confirm`
- `POST /orders/{salesOrderId}/payments`
- `POST /orders/{salesOrderId}/fulfill`
- `POST /orders/{salesOrderId}/cancel`

Total API endpoints: `29`

## Database and Seed Data

Schema and seed data are managed through:
- `python/database/migration/alembic/versions/314b57a8dd0f_00_initial_migration.py`
- `python/database/migration/alembic/versions/acf82479ef78_99_populate_seed_data.py`

The seed migration populates deterministic data for:
- UOM
- products and variants
- locations
- opening and transfer inventory movements
- sales orders, lines, reservations, and payments
- POS terminal, shift, and receipt
- BOM, BOM lines, work orders, and production run

The Docker PostgreSQL stacks run migrations automatically on startup:

| Workflow | Compose directory | Host port | Database | Use for |
|---|---|---:|---|---|
| Development | `docker/pgsql` | `5432` | `literp` | local app work and manual API checks |
| Test | `docker/pgsql-test` | `55432` | `literp_test` | automated tests and destructive checks |

Both workflows run Alembic to `head`, so the schema and deterministic seed data
come from the same migration path. The PostgreSQL services expose a healthcheck
and migration containers wait for the database before running Alembic.

Manual Alembic execution requires `DB_URL`. Use `python/database/envrc` for the
development database, `python/database/envrc.test` for the isolated test
database, or set `DB_URL` directly.

## Foundation CI Verification

The repository includes the `Foundation Verification` workflow at
`.github/workflows/foundation-verification.yml`.

Required checks:

- Build: Java 25 with the Gradle wrapper, running `./gradlew build`
- Migration Verification: Python 3.13 with PostgreSQL 18, running `python scripts/verify_migrations.py`

The migration verification script runs Alembic to `head`, verifies the database
revision matches the repository's Alembic head, and checks that deterministic
seed data exists in core tables.

## Runtime Configuration

The server reads `cfg.properties` from the repository root. Non-blank
environment variables override file values.

Current defaults:

```properties
http.port=8010
pg.host=localhost
pg.port=5432
pg.user=root
pg.password=pgdevpassword
pg.database=literp
```

Config precedence:

```text
environment override -> cfg.properties -> startup failure
```

Supported environment overrides:

| Property | Environment variables, in precedence order |
|---|---|
| `http.port` | `LITERP_HTTP_PORT`, `HTTP_PORT` |
| `pg.host` | `LITERP_PG_HOST`, `PG_HOST`, `DB_HOST` |
| `pg.port` | `LITERP_PG_PORT`, `PG_PORT`, `DB_PORT` |
| `pg.user` | `LITERP_PG_USER`, `PG_USER`, `DB_USER` |
| `pg.password` | `LITERP_PG_PASSWORD`, `PG_PASSWORD`, `DB_PASSWORD` |
| `pg.database` | `LITERP_PG_DATABASE`, `PG_DATABASE`, `DB_NAME` |

Startup fails with an actionable error when a required value is missing, blank,
or when numeric values such as `http.port` or `pg.port` are invalid.

## Handler and Repository Behavior

### UOM

Supported:
- list with `page`, `size`, `sort`
- create with duplicate code check
- get, update, delete by ID

Validation:
- `code` and `name` required on create
- `name` required on update

### Products

Supported:
- list with `page`, `size`, `sort`, `sku`, `productType`, and `activeOnly`
- create with SKU uniqueness check
- get with optional `includeVariants`
- update
- soft delete

Validation:
- create requires `sku`, `name`, `productType`, `baseUom`
- update requires `name`, `productType`

Important implementation note:
- create applies optional `active`
- update applies optional `baseUom` and `active`

### Product Variants

Supported:
- list nested under product with `page`, `size`, `sort`, and `activeOnly`
- create with SKU uniqueness check
- get by `productId` and `variantId`
- update by `productId` and `variantId`
- soft delete by `productId` and `variantId`

Validation:
- create requires `sku`, `name`
- update requires `name`

Important implementation note:
- create applies optional `active`
- update applies optional `active`

### Locations

Supported:
- list with `page`, `size`, `sort`, `code`, `name`, `locationType`, `activeOnly`
- create with code uniqueness check
- get by ID
- get by code
- update
- delete

Validation:
- create requires `code`, `name`, `locationType`
- update requires `name`, `locationType`

Important implementation note:
- create and update apply optional `isActive`

### Master-data list validation

UOM, Product, Product Variant, and Location list endpoints apply the same query
rules:

- `page` defaults to `0` and must be an integer greater than or equal to `0`
- `size` defaults to `20` and must be an integer from `1` through `100`
- `sort` must use `field,asc` or `field,desc`
- `activeOnly` and `includeVariants` must be `true` or `false` when supplied

Supported sort fields:

- UOM: `code`, `name`, `createdAt`, `created_at`, `updatedAt`, `updated_at`
- Product: `sku`, `name`, `productType`, `product_type`, `baseUom`, `base_uom`, `active`, `createdAt`, `created_at`, `updatedAt`, `updated_at`
- Product Variant: `sku`, `name`, `active`, `createdAt`, `created_at`, `updatedAt`, `updated_at`
- Location: `code`, `name`, `locationType`, `location_type`, `isActive`, `is_active`, `createdAt`, `created_at`, `updatedAt`, `updated_at`

Product `metadata`, product variant `attributes`, and location `address` are
returned as empty JSON objects when the database value is null.

### Order Process

#### Create draft

Required:
- `locationId`

Optional:
- `salesChannel` defaults to `POS`
- `currency` defaults to `USD`
- `customerId`
- `notes`

#### Add line

Required:
- `productId`
- `quantityOrdered`
- `unitPrice`

Optional:
- `sku`

Rules:
- order must exist
- order must be `DRAFT`
- product must exist and be active
- `quantityOrdered > 0`
- `unitPrice >= 0`

#### Confirm

Rules:
- order must exist
- order must be `DRAFT`
- order must have at least one line
- reservations are created per non-fulfilled line
- lines move from `PENDING` to `RESERVED`
- order moves to `CONFIRMED`

#### Capture payment

Required:
- `paymentMethod`
- `amount`

Optional:
- `transactionRef`

Rules:
- amount must be positive
- order must be `CONFIRMED` or `FULFILLED`
- payment rows are created directly with `CAPTURED`

#### Fulfill

Optional body:
- `createdBy`
- `notes`

Rules:
- order must be `CONFIRMED`
- captured payment total must be at least the order total
- only remaining lines are fulfilled
- inventory movements are written as `OUT`
- reservations are marked `FULFILLED`
- order moves to `FULFILLED`

#### Cancel

Optional body:
- `reason`

Rules:
- fulfilled orders cannot be cancelled
- orders with captured payment cannot be cancelled
- non-fulfilled lines are marked `CANCELLED`
- active reservations are marked `CANCELLED`
- order moves to `CANCELLED`

## Response Shapes

Master-data response shapes are normalized to the OpenAPI contracts. Utility
and order-process response shapes are still documented separately below.

### Utility endpoints

`GET /` and `GET /health/db` return plain JSON objects without the handler envelope.

### List endpoints

Master-data list endpoints return:

```json
{
  "data": [],
  "pagination": {
    "page": 0,
    "size": 20,
    "totalElements": 0,
    "totalPages": 0
  }
}
```

### Master-data single-resource endpoints

UOM, Product, Product Variant, and Location create/get/update flows return:

```json
{
  "data": {
    "id": "..."
  }
}
```

Migration note for clients: master-data list payloads moved from `data.data` to
top-level `data`, list pagination moved from `data.pagination` to top-level
`pagination`, and single-resource payloads moved from `data.data` to `data`.

### Order-process command endpoints

Order-process commands return a single envelope:

```json
{
  "data": {
    "salesOrderId": "...",
    "status": "CONFIRMED"
  }
}
```

### Error response

```json
{
  "error": "message",
  "errorCode": "VALIDATION_ERROR",
  "status": 400,
  "errorId": "uuid"
}
```

## HTTP Status Mapping

- `200` successful read or command
- `201` successful create or payment capture
- `204` successful delete
- `400` validation error
- `404` not found
- `409` conflict or invalid state transition
- `500` internal failure
- `503` database health failure

## Delete Strategy

- UOM: hard delete; missing rows return `404`; foreign-key references return `409`
- Product: soft delete via `active = false`; missing or already inactive rows return `404`
- Product Variant: soft delete via `active = false`; missing, mismatched parent product, or already inactive rows return `404`
- Location: hard delete; missing rows return `404`; foreign-key references return `409`
- Sales Order: lifecycle-driven, not deleted by API

## Bruno and OpenAPI Assets

Bruno collection:
- `api_collections/Literp`

OpenAPI contracts:
- `api_collections/open_api_spec/*.yaml`

Important distinction:
- the Bruno collection is synchronized to the implemented handlers
- the master-data OpenAPI specs are synchronized with the implemented catalog and location handlers
- repository and HTTP integration tests cover master-data success and error flows

## Known Limitations

- confirm, fulfill, and cancel are multi-step flows without explicit database transactions
- order-process list responses are still double wrapped under `data`
- order fulfillment writes `to_location_id` equal to `from_location_id`
- receipt persistence exists in schema and seed data but is not wired to API operations
- refunds are not exposed through a dedicated endpoint
- partial fulfillment is not exposed through a dedicated endpoint
