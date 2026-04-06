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
curl http://localhost:8010 | jq
curl http://localhost:8010/health/db | jq
```

If you need fresh local data:

```bash
cd docker
source envrc
make network
DIR=pgsql make env-up
```

## Response Shape Note

Master-data endpoints currently wrap the payload twice:

```json
{
  "data": {
    "data": {}
  }
}
```

List endpoints currently return:

```json
{
  "data": {
    "data": [],
    "pagination": {}
  }
}
```

Order-process commands usually return a single `data` envelope.

## Utility Endpoints

### Index

```bash
curl http://localhost:8010 | jq
```

### Database health

```bash
curl http://localhost:8010/health/db | jq
```

## Unit of Measure

### List

```bash
curl "$BASE_URL/uom?page=0&size=20&sort=code,asc" | jq
```

### Create

```bash
curl -X POST "$BASE_URL/uom" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "EA",
    "name": "Each",
    "baseUnit": null
  }' | jq
```

### Get

```bash
curl "$BASE_URL/uom/{uomId}" | jq
```

### Update

```bash
curl -X PUT "$BASE_URL/uom/{uomId}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Each Updated",
    "baseUnit": "unit"
  }' | jq
```

### Delete

```bash
curl -i -X DELETE "$BASE_URL/uom/{uomId}"
```

## Products

### List

```bash
curl "$BASE_URL/products?page=0&size=20&sort=sku,asc" | jq
```

### Create

```bash
curl -X POST "$BASE_URL/products" \
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
curl "$BASE_URL/products/{productId}" | jq
```

### Update

```bash
curl -X PUT "$BASE_URL/products/{productId}" \
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
curl -i -X DELETE "$BASE_URL/products/{productId}"
```

## Product Variants

### List

```bash
curl "$BASE_URL/products/{productId}/variants?page=0&size=20&sort=sku,asc" | jq
```

### Create

```bash
curl -X POST "$BASE_URL/products/{productId}/variants" \
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
curl "$BASE_URL/products/{productId}/variants/{variantId}" | jq
```

### Update

```bash
curl -X PUT "$BASE_URL/products/{productId}/variants/{variantId}" \
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
curl -i -X DELETE "$BASE_URL/products/{productId}/variants/{variantId}"
```

## Locations

### List

```bash
curl "$BASE_URL/locations?page=0&size=20&sort=code,asc&activeOnly=true" | jq
```

### Filtered list

```bash
curl "$BASE_URL/locations?page=0&size=20&sort=code,asc&locationType=WAREHOUSE&name=main&activeOnly=true" | jq
```

### Create

```bash
curl -X POST "$BASE_URL/locations" \
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
curl "$BASE_URL/locations/{locationId}" | jq
```

### Get by code

```bash
curl "$BASE_URL/locations/by-code/WH-001" | jq
```

### Update

```bash
curl -X PUT "$BASE_URL/locations/{locationId}" \
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
curl -i -X DELETE "$BASE_URL/locations/{locationId}"
```

## Order Process

### List orders

```bash
curl "$BASE_URL/orders?page=0&size=20&sort=orderDate,desc" | jq
```

### Create draft

```bash
curl -X POST "$BASE_URL/orders" \
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
curl "$BASE_URL/orders/{salesOrderId}" | jq
```

### Add line

```bash
curl -X POST "$BASE_URL/orders/{salesOrderId}/lines" \
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
curl -X POST "$BASE_URL/orders/{salesOrderId}/confirm" | jq
```

### Capture payment

```bash
curl -X POST "$BASE_URL/orders/{salesOrderId}/payments" \
  -H "Content-Type: application/json" \
  -d '{
    "paymentMethod": "CASH",
    "amount": 39.98,
    "transactionRef": "POS-CASH-0001"
  }' | jq
```

### Fulfill

```bash
curl -X POST "$BASE_URL/orders/{salesOrderId}/fulfill" \
  -H "Content-Type: application/json" \
  -d '{
    "createdBy": "manual-test",
    "notes": "Handed over to customer"
  }' | jq
```

### Cancel

```bash
curl -X POST "$BASE_URL/orders/{salesOrderId}/cancel" \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "Customer changed mind"
  }' | jq
```

## End-to-End Happy Path

```bash
UOM_ID=...
PRODUCT_ID=...
LOCATION_ID=...

ORDER_ID=$(curl -s -X POST "$BASE_URL/orders" \
  -H "Content-Type: application/json" \
  -d "{\"locationId\":\"$LOCATION_ID\",\"salesChannel\":\"POS\"}" | jq -r '.data.salesOrderId')

curl -s -X POST "$BASE_URL/orders/$ORDER_ID/lines" \
  -H "Content-Type: application/json" \
  -d "{\"productId\":\"$PRODUCT_ID\",\"quantityOrdered\":2,\"unitPrice\":19.99}" | jq

curl -s -X POST "$BASE_URL/orders/$ORDER_ID/confirm" | jq

curl -s -X POST "$BASE_URL/orders/$ORDER_ID/payments" \
  -H "Content-Type: application/json" \
  -d '{"paymentMethod":"CASH","amount":39.98}' | jq

curl -s -X POST "$BASE_URL/orders/$ORDER_ID/fulfill" \
  -H "Content-Type: application/json" \
  -d '{"createdBy":"manual-test"}' | jq

curl -s "$BASE_URL/orders/$ORDER_ID" | jq
```

## Common Error Cases

### Missing required field

```bash
curl -X POST "$BASE_URL/products" \
  -H "Content-Type: application/json" \
  -d '{"sku":"PROD-002"}' | jq
```

### Duplicate SKU

```bash
curl -X POST "$BASE_URL/products" \
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
curl -X POST "$BASE_URL/orders/{salesOrderId}/confirm" | jq
```

### Fulfill before full payment

```bash
curl -X POST "$BASE_URL/orders/{salesOrderId}/fulfill" | jq
```

### Cancel order with captured payment

```bash
curl -X POST "$BASE_URL/orders/{salesOrderId}/cancel" \
  -H "Content-Type: application/json" \
  -d '{"reason":"late test"}' | jq
```
