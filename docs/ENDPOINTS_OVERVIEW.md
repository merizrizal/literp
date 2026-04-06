# Endpoint Overview

## Runtime Surface

Utility endpoints:
- `GET /`
- `GET /health/db`

API base:

```text
/api/v1
```

## API Domains

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

## Totals

| Area | Count |
|---|---:|
| Utility endpoints | 2 |
| API endpoints | 29 |
| API domains | 5 |

## Architecture Path

```text
HTTP Request
  -> OpenAPI RouterBuilder route
  -> Handler
  -> Vert.x service proxy
  -> Repository
  -> PostgreSQL
```

Implemented layers:
- handlers in `src/main/kotlin/com/literp/verticle/handler`
- service interfaces in `src/main/java/com/literp/service`
- service implementations in `src/main/kotlin/com/literp/service`
- repositories in `src/main/kotlin/com/literp/repository`

## Order Lifecycle

```text
Draft
  -> add lines
Confirmed
  -> reserve stock
  -> capture payment
Fulfilled
```

Cancellation path:

```text
DRAFT -> CANCELLED
CONFIRMED -> CANCELLED    only when no captured payment exists
FULFILLED -> cannot cancel
```

## Request Notes by Domain

### Master data

- UOM create requires: `code`, `name`
- Product create requires: `sku`, `name`, `productType`, `baseUom`
- Product variant create requires: `sku`, `name`
- Location create requires: `code`, `name`, `locationType`

### Order process

- Order draft create requires: `locationId`
- Add line requires: `productId`, `quantityOrdered`, `unitPrice`
- Capture payment requires: `paymentMethod`, `amount`
- Fulfill and cancel request bodies are optional

## Current Implementation Caveats

- List responses are wrapped as `data.data` plus pagination under `data.pagination`.
- Master-data single-resource responses are also wrapped as `data.data`.
- Order-process command responses use a single `data` envelope.
- Some fields documented in OpenAPI are not currently acted on by handlers:
  - product list filters beyond `page`, `size`, `sort`
  - product `baseUom` and `active` on update
  - location `isActive` on create/update
- Fulfillment currently writes `from_location_id` and `to_location_id` with the same location.

## Test Assets

- Bruno collection: [`../api_collections/Literp`](../api_collections/Literp)
- OpenAPI specs: [`../api_collections/open_api_spec`](../api_collections/open_api_spec)
