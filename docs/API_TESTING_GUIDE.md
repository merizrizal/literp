# API Testing Guide

Use Bruno for routine testing and curl for quick verification.

Bruno collection:

```text
api_collections/Literp
```

Base URL used below:

```bash
BASE_URL=http://localhost:8010/api/v1
```

## Prerequisites

```bash
# Public probes
curl http://localhost:8010 | jq
curl http://localhost:8010/health/live | jq

# Operational routes require an approved operator/service access token.
: "${LITERP_ACCESS_TOKEN:?Set LITERP_ACCESS_TOKEN before calling protected routes}"
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" http://localhost:8010/health/ready | jq
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" http://localhost:8010/health/db | jq
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" http://localhost:8010/metrics | jq
```

If you need fresh local data:

```bash
cd docker
source envrc
make network
DIR=pgsql make env-up
```

## Authentication

All 31 business operations require an authorized bearer access token. Add
`-H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}"` to protected curl
examples below. `GET /` and `GET /health/live` are the only public probes;
`/metrics`, `/health/ready`, and `/health/db` require an operator or service
principal with `operations.read`. Do not place a real token in this guide.

The runtime derives fulfillment attribution from the verified token subject and
ignores any request-body `createdBy` value. For the complete claim, key
rotation, and acceptance runbook, see
[`docs/knowledge/AUTHENTICATION_BASELINE.md`](knowledge/AUTHENTICATION_BASELINE.md).

## Response Shape Note

Master-data list endpoints return:

```json
{
  "data": [],
  "pagination": {}
}
```

Master-data create/get/update endpoints return the resource under `data`.
Order-process commands usually return a single `data` envelope.

## Request Tracing

The server echoes `X-Request-ID` on responses. If you omit it, the server
generates one.

```bash
curl -i -H "X-Request-ID: demo-123" http://localhost:8010/health/live
```

## Automated Verification

Start the isolated PostgreSQL test database:

```bash
cd docker
source envrc
make network
DIR=pgsql-test make env-up
```

Run the current Phase 04.1–04.2 baseline:

```bash
./gradlew test --tests com.literp.verticle.MasterDataHttpIntegrationTest \
  --tests com.literp.verticle.OrderProcessHttpIntegrationTest \
  --tests com.literp.contract.OpenApiOperationIdRegistrationTest
./gradlew test
./gradlew build
```

These Vert.x integration tests use `src/test/kotlin/com/literp/test/TestDatabase.kt`, which defaults to `127.0.0.1:55432/literp_test`. If PostgreSQL is unavailable, the database-backed tests are skipped via a JUnit assumption and should be rerun after `DIR=pgsql-test make env-up`.

## Master-data Query Validation

Master-data list endpoints share these query rules:

- `page` defaults to `0` and must be greater than or equal to `0`
- `size` defaults to `20` and must be from `1` through `100`
- `sort` must use `field,asc` or `field,desc`
- unsupported sort fields return `400`
- `activeOnly` and `includeVariants` must be `true` or `false`

Examples:

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/uom?page=-1" | jq
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/uom?size=101" | jq
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/uom?sort=unknown,asc" | jq
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/products?activeOnly=maybe" | jq
```

## Utility Endpoints

### Index

```bash
curl http://localhost:8010 | jq
```

### Liveness

```bash
curl http://localhost:8010/health/live | jq
```

### Readiness

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" http://localhost:8010/health/ready | jq
```

### Database health

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" http://localhost:8010/health/db | jq
```

### Metrics

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" http://localhost:8010/metrics | jq
```

## Unit of Measure

### List

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/uom?page=0&size=20&sort=code,asc" | jq
```

### Create

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/uom" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "EA",
    "name": "Each",
    "baseUnit": null
  }' | jq
```

### Get

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/uom/{uomId}" | jq
```

### Update

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X PUT "$BASE_URL/uom/{uomId}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Each Updated",
    "baseUnit": "unit"
  }' | jq
```

### Delete

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -i -X DELETE "$BASE_URL/uom/{uomId}"
```

Expected delete errors:
- `404` when the UOM does not exist
- `409` when products still reference the UOM

## Products

### List

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/products?page=0&size=20&sort=sku,asc" | jq
```

### Create

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/products" \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "PROD-001",
    "name": "Widget A",
    "productType": "STOCK",
    "baseUom": "{uomId}",
    "metadata": {
      "brand": "Literp",
      "color": "blue"
    }
  }' | jq
```

### Get

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/products/{productId}" | jq
```

### Update

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X PUT "$BASE_URL/products/{productId}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Widget A Updated",
    "productType": "STOCK",
    "metadata": {
      "brand": "Literp",
      "color": "red"
    }
  }' | jq
```

### Delete

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -i -X DELETE "$BASE_URL/products/{productId}"
```

Product delete is a soft delete. A missing or already inactive product returns `404`.

## Product Variants

### List

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/products/{productId}/variants?page=0&size=20&sort=sku,asc" | jq
```

### Create

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/products/{productId}/variants" \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "PROD-001-RED-M",
    "name": "Red Medium",
    "attributes": {
      "color": "red",
      "size": "M"
    }
  }' | jq
```

### Get

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/products/{productId}/variants/{variantId}" | jq
```

### Update

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X PUT "$BASE_URL/products/{productId}/variants/{variantId}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Red Large",
    "attributes": {
      "color": "red",
      "size": "L"
    }
  }' | jq
```

### Delete

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -i -X DELETE "$BASE_URL/products/{productId}/variants/{variantId}"
```

Product variant delete is a soft delete scoped to the parent product. A missing variant,
mismatched parent product, or already inactive variant returns `404`.

## Locations

### List

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/locations?page=0&size=20&sort=code,asc&activeOnly=true" | jq
```

### Filtered list

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/locations?page=0&size=20&sort=code,asc&locationType=WAREHOUSE&name=main&activeOnly=true" | jq
```

### Create

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/locations" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "WH-001",
    "name": "Main Warehouse",
    "locationType": "WAREHOUSE",
    "address": {
      "street": "123 Industrial Way",
      "city": "Springfield",
      "state": "IL"
    }
  }' | jq
```

### Get by ID

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/locations/{locationId}" | jq
```

### Get by code

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/locations/by-code/WH-001" | jq
```

### Update

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X PUT "$BASE_URL/locations/{locationId}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Main Warehouse Updated",
    "locationType": "WAREHOUSE",
    "address": {
      "street": "456 New Industrial Blvd",
      "city": "Springfield",
      "state": "IL"
    }
  }' | jq
```

### Delete

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -i -X DELETE "$BASE_URL/locations/{locationId}"
```

Expected delete errors:
- `404` when the location does not exist
- `409` when inventory, order, POS, or manufacturing records still reference the location

## Order Process

### List orders

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/orders?page=0&size=20&sort=orderDate,desc" | jq
```

### Create draft

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/orders" \
  -H "Content-Type: application/json" \
  -d '{
    "salesChannel": "POS",
    "locationId": "{locationId}",
    "customerId": "CUST-001",
    "currency": "USD",
    "notes": "Walk-in customer order"
  }' | jq
```

### Get order

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/orders/{salesOrderId}" | jq
```

### Add line

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/orders/{salesOrderId}/lines" \
  -H "Content-Type: application/json" \
  -d '{
    "productId": "{productId}",
    "sku": "PROD-001",
    "quantityOrdered": 2,
    "unitPrice": 19.99
  }' | jq
```

### Confirm

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/orders/{salesOrderId}/confirm" \
  -H "Idempotency-Key: confirm-test-001" | jq
```

Confirm creates `RESERVED` inventory reservations only when available stock is sufficient. Phase 03 uses a no-oversell policy; insufficient stock returns `409` and leaves the order in `DRAFT`.

### Capture payment

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/orders/{salesOrderId}/payments" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: payment-test-001" \
  -d '{
    "paymentMethod": "CASH",
    "amount": 39.98,
    "transactionRef": "POS-CASH-0001"
  }' | jq
```

### Fulfill

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/orders/{salesOrderId}/fulfill" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: fulfill-test-001" \
  -d '{
    "createdBy": "manual-test",
    "notes": "Handed over to customer"
  }' | jq
```

### Cancel

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/orders/{salesOrderId}/cancel" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: cancel-test-001" \
  -d '{
    "reason": "Customer changed mind"
  }' | jq
```

### Current stock

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/stock/current?productId={productId}&locationId={locationId}" | jq
```

### Available stock

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/stock/available?productId={productId}&locationId={locationId}" | jq
```

Available stock is current stock from inventory movements minus active `RESERVED` inventory reservations.

## End-to-End Happy Path

```bash
UOM_ID=...
PRODUCT_ID=...
LOCATION_ID=...

ORDER_ID=$(curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -s -X POST "$BASE_URL/orders" \
  -H "Content-Type: application/json" \
  -d "{\"locationId\":\"$LOCATION_ID\",\"salesChannel\":\"POS\"}" | jq -r '.data.salesOrderId')

curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -s -X POST "$BASE_URL/orders/$ORDER_ID/lines" \
  -H "Content-Type: application/json" \
  -d "{\"productId\":\"$PRODUCT_ID\",\"quantityOrdered\":2,\"unitPrice\":19.99}" | jq

curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -s -X POST "$BASE_URL/orders/$ORDER_ID/confirm" \
  -H "Idempotency-Key: confirm-$ORDER_ID" | jq

curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -s -X POST "$BASE_URL/orders/$ORDER_ID/payments" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: payment-$ORDER_ID" \
  -d '{"paymentMethod":"CASH","amount":39.98}' | jq

curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -s -X POST "$BASE_URL/orders/$ORDER_ID/fulfill" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: fulfill-$ORDER_ID" \
  -d '{"createdBy":"manual-test"}' | jq

curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -s "$BASE_URL/orders/$ORDER_ID" | jq
```

## Common Error Cases

### Invalid pagination or sort

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/uom?page=-1" | jq
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/uom?size=101" | jq
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/uom?sort=unknown,asc" | jq
```

### Invalid boolean query

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/products?activeOnly=maybe" | jq
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" "$BASE_URL/products/{productId}?includeVariants=maybe" | jq
```

### Missing required field

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/products" \
  -H "Content-Type: application/json" \
  -d '{"sku":"PROD-002"}' | jq
```

### Duplicate SKU

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/products" \
  -H "Content-Type: application/json" \
  -d '{
    "sku":"PROD-001",
    "name":"Duplicate Product",
    "productType":"STOCK",
    "baseUom":"{uomId}"
  }' | jq
```

### Confirm order with no lines

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/orders/{salesOrderId}/confirm" \
  -H "Idempotency-Key: confirm-empty-order" | jq
```

### Confirm order with insufficient stock

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/orders/{salesOrderId}/confirm" \
  -H "Idempotency-Key: confirm-insufficient-stock" | jq
```

Expected result: `409 Conflict`; no reservations are created and the order remains `DRAFT`.

### Fulfill before full payment

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/orders/{salesOrderId}/fulfill" \
  -H "Idempotency-Key: fulfill-before-payment" | jq
```

### Cancel order with captured payment

```bash
curl -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" -X POST "$BASE_URL/orders/{salesOrderId}/cancel" \
  -H "Content-Type: application/json" \
  -d '{"reason":"late test"}' | jq
```
