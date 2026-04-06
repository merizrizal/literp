# API Implementation

This document describes the implementation that exists on the current branch, not an aspirational target state.

## Stack

- Kotlin `2.3.20`
- Vert.x `5.0.10`
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

The Docker PostgreSQL stack runs migrations automatically on startup.

## Runtime Configuration

The server reads `cfg.properties` from the repository root.

Current defaults:

```properties
http.port=8010
pg.host=localhost
pg.port=5432
pg.user=root
pg.password=pgdevpassword
pg.database=literp
```

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
- list with `page`, `size`, `sort`
- create with SKU uniqueness check
- get, update, soft delete

Validation:
- create requires `sku`, `name`, `productType`, `baseUom`
- update requires `name`, `productType`

Important implementation note:
- handler and repository currently only honor `page`, `size`, and `sort` on list
- `baseUom` and `active` are not currently applied on update even if sent

### Product Variants

Supported:
- list nested under product
- create with SKU uniqueness check
- get by `productId` and `variantId`
- update by `variantId`
- soft delete by `variantId`

Validation:
- create requires `sku`, `name`
- update requires `name`

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
- `isActive` is documented in OpenAPI but is not currently used by create or update handlers

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

Current response shapes are not fully normalized across handlers.

### Utility endpoints

`GET /` and `GET /health/db` return plain JSON objects without the handler envelope.

### List endpoints

List endpoints currently return:

```json
{
  "data": {
    "data": [],
    "pagination": {
      "page": 0,
      "size": 20,
      "totalElements": 0,
      "totalPages": 0
    }
  }
}
```

### Master-data single-resource endpoints

Most master-data CRUD reads and writes currently return:

```json
{
  "data": {
    "data": {
      "id": "..."
    }
  }
}
```

This applies to UOM, Product, Product Variant, and Location create/get/update flows.

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

- UOM: hard delete
- Product: soft delete via `active = false`
- Product Variant: soft delete via `active = false`
- Location: hard delete
- Sales Order: lifecycle-driven, not deleted by API

## Bruno and OpenAPI Assets

Bruno collection:
- `api_collections/Literp`

OpenAPI contracts:
- `api_collections/open_api_spec/*.yaml`

Important distinction:
- the Bruno collection is synchronized to the implemented handlers
- the OpenAPI specs still include a few forward-looking fields that the current handlers ignore

## Known Limitations

- confirm, fulfill, and cancel are multi-step flows without explicit database transactions
- list responses and some single-resource responses are double wrapped under `data`
- product list filtering in OpenAPI is broader than the current implementation
- location `isActive` and product update `baseUom` / `active` are not currently applied
- order fulfillment writes `to_location_id` equal to `from_location_id`
- receipt persistence exists in schema and seed data but is not wired to API operations
- refunds are not exposed through a dedicated endpoint
- partial fulfillment is not exposed through a dedicated endpoint
