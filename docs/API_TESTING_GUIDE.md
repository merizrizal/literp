# API Testing Guide

Quick reference for testing all 21 endpoints of the Literp REST API.

## Prerequisites
- Server running on `http://localhost:8010`
- PostgreSQL with literp database running
- `jq` tool for JSON formatting (optional)

## Unit of Measure (UOM) Endpoints

### 1. List all UOMs
```bash
curl -X GET "http://localhost:8010/api/v1/uom?page=0&size=20&sort=code,asc" \
  -H "Accept: application/json" | jq
```

### 2. Create a new UOM
```bash
curl -X POST "http://localhost:8010/api/v1/uom" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "EA",
    "name": "Each",
    "baseUnit": null
  }' | jq
```

**Response:**
```json
{
  "data": {
    "uomId": "550e8400-e29b-41d4-a716-446655440000",
    "code": "EA",
    "name": "Each",
    "baseUnit": null
  }
}
```

### 3. Get a specific UOM
```bash
curl -X GET "http://localhost:8010/api/v1/uom/{uomId}" \
  -H "Accept: application/json" | jq
```

### 4. Update UOM
```bash
curl -X PUT "http://localhost:8010/api/v1/uom/{uomId}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Each (Updated)",
    "baseUnit": "unit"
  }' | jq
```

### 5. Delete UOM
```bash
curl -X DELETE "http://localhost:8010/api/v1/uom/{uomId}" \
  -H "Accept: application/json"
```

---

## Product Endpoints

### 6. List all Products
```bash
curl -X GET "http://localhost:8010/api/v1/products?page=0&size=20&sort=sku,asc" \
  -H "Accept: application/json" | jq
```

### 7. Create a new Product
```bash
curl -X POST "http://localhost:8010/api/v1/products" \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "PROD-001",
    "name": "Sample Product",
    "productType": "STOCK",
    "baseUom": "{uomId}",
    "metadata": {
      "color": "red",
      "brand": "Acme",
      "size": "M"
    }
  }' | jq
```

**Note:** Replace `{uomId}` with an actual UOM ID from step 2.

### 8. Get a specific Product
```bash
curl -X GET "http://localhost:8010/api/v1/products/{productId}" \
  -H "Accept: application/json" | jq
```

### 9. Update Product
```bash
curl -X PUT "http://localhost:8010/api/v1/products/{productId}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Updated Product Name",
    "productType": "STOCK",
    "metadata": {
      "color": "blue",
      "brand": "Acme"
    }
  }' | jq
```

### 10. Delete Product (Soft Delete)
```bash
curl -X DELETE "http://localhost:8010/api/v1/products/{productId}" \
  -H "Accept: application/json"
```

---

## Product Variant Endpoints

### 11. List all Variants for a Product
```bash
curl -X GET "http://localhost:8010/api/v1/products/{productId}/variants?page=0&size=20&sort=sku,asc" \
  -H "Accept: application/json" | jq
```

### 12. Create a Product Variant
```bash
curl -X POST "http://localhost:8010/api/v1/products/{productId}/variants" \
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

### 13. Get a specific Product Variant
```bash
curl -X GET "http://localhost:8010/api/v1/products/{productId}/variants/{variantId}" \
  -H "Accept: application/json" | jq
```

### 14. Update Product Variant
```bash
curl -X PUT "http://localhost:8010/api/v1/products/{productId}/variants/{variantId}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Red Large",
    "attributes": {
      "color": "red",
      "size": "L"
    }
  }' | jq
```

### 15. Delete Product Variant (Soft Delete)
```bash
curl -X DELETE "http://localhost:8010/api/v1/products/{productId}/variants/{variantId}" \
  -H "Accept: application/json"
```

---

## Location Endpoints

### 16. List all Locations
```bash
curl -X GET "http://localhost:8010/api/v1/locations?page=0&size=20&sort=code,asc" \
  -H "Accept: application/json" | jq
```

### 17. List Locations with Filters
```bash
curl -X GET "http://localhost:8010/api/v1/locations?locationType=WAREHOUSE&name=main&activeOnly=true" \
  -H "Accept: application/json" | jq
```

### 18. Create a new Location
```bash
curl -X POST "http://localhost:8010/api/v1/locations" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "WH-001",
    "name": "Main Warehouse",
    "locationType": "WAREHOUSE",
    "address": {
      "street": "123 Main Street",
      "city": "Springfield",
      "state": "IL",
      "country": "USA",
      "postalCode": "62701"
    }
  }' | jq
```

### 19. Get a specific Location
```bash
curl -X GET "http://localhost:8010/api/v1/locations/{locationId}" \
  -H "Accept: application/json" | jq
```

### 20. Get Location by Code
```bash
curl -X GET "http://localhost:8010/api/v1/locations/by-code/WH-001" \
  -H "Accept: application/json" | jq
```

### 21. Update Location
```bash
curl -X PUT "http://localhost:8010/api/v1/locations/{locationId}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Updated Warehouse Name",
    "locationType": "WAREHOUSE",
    "address": {
      "street": "456 New Street",
      "city": "Springfield",
      "state": "IL"
    }
  }' | jq
```

### 22. Delete Location (Hard Delete)
```bash
curl -X DELETE "http://localhost:8010/api/v1/locations/{locationId}" \
  -H "Accept: application/json"
```

---

## Error Response Examples

### 400 Bad Request - Missing required field
```bash
curl -X POST "http://localhost:8010/api/v1/products" \
  -H "Content-Type: application/json" \
  -d '{"sku": "PROD-002"}'
```

**Response:**
```json
{
  "error": "sku, name, productType, and baseUom are required",
  "status": 400
}
```

### 409 Conflict - Duplicate SKU
```bash
curl -X POST "http://localhost:8010/api/v1/products" \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "PROD-001",
    "name": "Duplicate Product",
    "productType": "STOCK",
    "baseUom": "{uomId}"
  }'
```

**Response:**
```json
{
  "error": "Product SKU already exists",
  "status": 409
}
```

### 404 Not Found
```bash
curl -X GET "http://localhost:8010/api/v1/products/invalid-id"
```

**Response:**
```json
{
  "error": "Product not found",
  "status": 404
}
```

---

## Testing Workflow

### Complete End-to-End Test

1. **Create UOM**
   ```bash
   UOM_RESPONSE=$(curl -s -X POST "http://localhost:8010/api/v1/uom" \
     -H "Content-Type: application/json" \
     -d '{"code": "EA", "name": "Each"}')
   UOM_ID=$(echo $UOM_RESPONSE | jq -r '.data.uomId')
   echo "Created UOM: $UOM_ID"
   ```

2. **Create Product** (using UOM_ID)
   ```bash
   PRODUCT_RESPONSE=$(curl -s -X POST "http://localhost:8010/api/v1/products" \
     -H "Content-Type: application/json" \
     -d "{\"sku\": \"PROD-TEST\", \"name\": \"Test Product\", \"productType\": \"STOCK\", \"baseUom\": \"$UOM_ID\"}")
   PRODUCT_ID=$(echo $PRODUCT_RESPONSE | jq -r '.data.productId')
   echo "Created Product: $PRODUCT_ID"
   ```

3. **Create Product Variant**
   ```bash
   VARIANT_RESPONSE=$(curl -s -X POST "http://localhost:8010/api/v1/products/$PRODUCT_ID/variants" \
     -H "Content-Type: application/json" \
     -d '{"sku": "PROD-TEST-VAR1", "name": "Variant 1", "attributes": {"color": "red"}}')
   VARIANT_ID=$(echo $VARIANT_RESPONSE | jq -r '.data.variantId')
   echo "Created Variant: $VARIANT_ID"
   ```

4. **Create Location**
   ```bash
   LOCATION_RESPONSE=$(curl -s -X POST "http://localhost:8010/api/v1/locations" \
     -H "Content-Type: application/json" \
     -d '{"code": "LOC-001", "name": "Test Location", "locationType": "WAREHOUSE"}')
   LOCATION_ID=$(echo $LOCATION_RESPONSE | jq -r '.data.locationId')
   echo "Created Location: $LOCATION_ID"
   ```

5. **List all entities**
   ```bash
   curl -s "http://localhost:8010/api/v1/uom" | jq '.data[]'
   curl -s "http://localhost:8010/api/v1/products" | jq '.data[]'
   curl -s "http://localhost:8010/api/v1/products/$PRODUCT_ID/variants" | jq '.data[]'
   curl -s "http://localhost:8010/api/v1/locations" | jq '.data[]'
   ```

---

## Pagination Examples

### First page
```bash
curl "http://localhost:8010/api/v1/products?page=0&size=10"
```

### Second page
```bash
curl "http://localhost:8010/api/v1/products?page=1&size=10"
```

### Reverse sort
```bash
curl "http://localhost:8010/api/v1/products?sort=name,desc"
```

---

## Notes

- All timestamps are in ISO-8601 format (e.g., "2026-01-26T22:30:45.123456")
- UUIDs are standard string format (36 characters with hyphens)
- All endpoints return 200, 201, 204, 400, 404, 409, or 500 status codes
- Soft deletes only affect Products and ProductVariants (marked as inactive)
- Locations use hard deletes (physical removal from database)
- JSON fields (metadata, attributes, address) accept any valid JSON object
