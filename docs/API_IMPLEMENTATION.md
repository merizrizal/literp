# Literp REST API Implementation

## Overview

This document describes the complete REST API implementation for Literp using Vert.x 5.0.8, Kotlin 2.3.10, and reactive RxJava3. All endpoints from the Product Catalog and Inventory Location OpenAPI specifications have been fully implemented.

## Architecture

### Technology Stack
- **Framework**: Vert.x 5.0.8 (reactive, event-driven)
- **Language**: Kotlin 2.3.10 on Java 25
- **Async Model**: RxJava3 with reactive streams
- **Database**: PostgreSQL (via Vert.x PG Client)
- **API Specification**: OpenAPI 3.0 with vertx-openapi router integration
- **Build**: Gradle with Kotlin DSL

### Project Structure

```
src/main/kotlin/com/literp/
├── App.kt                          # Application entry point
├── config/
│   └── Config.kt                   # Configuration (HTTP port, etc.)
├── db/
│   └── DatabaseConnection.kt       # Database pool initialization
├── repository/
│   ├── UnitOfMeasureRepository.kt  # UOM CRUD operations
│   ├── ProductRepository.kt        # Product CRUD operations
│   ├── ProductVariantRepository.kt # Product Variant CRUD operations
│   └── LocationRepository.kt       # Location CRUD operations
└── verticle/
    ├── MainVerticle.kt             # Main verticle entry point
    └── HttpServerVerticle.kt       # HTTP server with route handlers
```

## Implementation Details

### 1. Database Connection Layer (`DatabaseConnection.kt`)

Manages the PostgreSQL connection pool:
- Host: localhost
- Port: 5432
- Database: literp
- Credentials: postgres/postgres
- Pool Size: 4 connections

```kotlin
val pool = DatabaseConnection.createPool()
```

### 2. Repository Pattern

Four repository classes handle all database operations using parameterized queries:

#### UnitOfMeasureRepository
- `listUnitOfMeasures(page, size, sort)` - Paginated list with sorting
- `createUnitOfMeasure(code, name, baseUnit)` - Create new UOM
- `getUnitOfMeasure(uomId)` - Fetch single UOM
- `updateUnitOfMeasure(uomId, name, baseUnit)` - Update UOM details
- `deleteUnitOfMeasure(uomId)` - Delete UOM
- `checkCodeExists(code)` - Validate unique code constraint

#### ProductRepository
- `listProducts(page, size, sort)` - Paginated list (active only)
- `createProduct(sku, name, productType, baseUom, metadata)` - Create with JSONB metadata
- `getProduct(productId)` - Fetch active product
- `updateProduct(productId, name, productType, metadata)` - Update product (SKU immutable)
- `deleteProduct(productId)` - Soft delete (sets active=false)
- `checkSkuExists(sku)` - Validate unique SKU constraint

#### ProductVariantRepository
- `listProductVariants(productId, page, size, sort)` - Variants for specific product
- `createProductVariant(productId, sku, name, attributes)` - Create with JSONB attributes
- `getProductVariant(productId, variantId)` - Fetch variant
- `updateProductVariant(variantId, name, attributes)` - Update variant
- `deleteProductVariant(variantId)` - Soft delete
- `checkSkuExists(sku)` - Validate unique SKU per variant

#### LocationRepository
- `listLocations(page, size, sort, code, name, locationType, activeOnly)` - Filtered pagination
- `createLocation(code, name, locationType, address)` - Create with JSONB address
- `getLocation(locationId)` - Fetch by ID
- `getLocationByCode(code)` - Lookup by code
- `updateLocation(locationId, name, locationType, address)` - Update location
- `deleteLocation(locationId)` - Delete location
- `checkCodeExists(code)` - Validate unique code constraint

### 3. HTTP Server & Route Handlers (`HttpServerVerticle.kt`)

**Key Features:**
- Loads both OpenAPI specs (product-catalog.yaml and locations.yaml)
- Creates RouterBuilders for each spec
- Registers handlers for all 21 operation IDs
- Mounts routers under `/api/v1` path
- Returns proper HTTP status codes (200, 201, 204, 400, 404, 409, 500)
- Comprehensive error handling with descriptive messages
- JSON request/response bodies

**Handler Organization:**

1. **Unit of Measure Handlers** (5 endpoints)
   - `listUnitOfMeasures()` - GET /uom
   - `createUnitOfMeasure()` - POST /uom
   - `getUnitOfMeasure()` - GET /uom/{uomId}
   - `updateUnitOfMeasure()` - PUT /uom/{uomId}
   - `deleteUnitOfMeasure()` - DELETE /uom/{uomId}

2. **Product Handlers** (5 endpoints)
   - `listProducts()` - GET /products
   - `createProduct()` - POST /products
   - `getProduct()` - GET /products/{productId}
   - `updateProduct()` - PUT /products/{productId}
   - `deleteProduct()` - DELETE /products/{productId}

3. **Product Variant Handlers** (5 endpoints)
   - `listProductVariants()` - GET /products/{productId}/variants
   - `createProductVariant()` - POST /products/{productId}/variants
   - `getProductVariant()` - GET /products/{productId}/variants/{variantId}
   - `updateProductVariant()` - PUT /products/{productId}/variants/{variantId}
   - `deleteProductVariant()` - DELETE /products/{productId}/variants/{variantId}

4. **Location Handlers** (6 endpoints)
   - `listLocations()` - GET /locations
   - `createLocation()` - POST /locations
   - `getLocation()` - GET /locations/{locationId}
   - `getLocationByCode()` - GET /locations/by-code/{code}
   - `updateLocation()` - PUT /locations/{locationId}
   - `deleteLocation()` - DELETE /locations/{locationId}

## API Response Format

### Success Response (2xx)
```json
{
  "data": { ... }
}
```

### Paginated Response (List endpoints)
```json
{
  "data": [ ... ],
  "pagination": {
    "page": 0,
    "size": 20,
    "totalElements": 100,
    "totalPages": 5
  }
}
```

### Error Response (4xx, 5xx)
```json
{
  "error": "Description of error",
  "status": 400
}
```

## Request Examples

### Create Unit of Measure
```bash
curl -X POST http://localhost:8010/api/v1/uom \
  -H "Content-Type: application/json" \
  -d '{
    "code": "EA",
    "name": "Each",
    "baseUnit": null
  }'
```

### Create Product
```bash
curl -X POST http://localhost:8010/api/v1/products \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "PROD-001",
    "name": "Sample Product",
    "productType": "STOCK",
    "baseUom": "{uomId}",
    "metadata": {
      "color": "red",
      "brand": "Acme"
    }
  }'
```

### Create Location
```bash
curl -X POST http://localhost:8010/api/v1/locations \
  -H "Content-Type: application/json" \
  -d '{
    "code": "WH-001",
    "name": "Main Warehouse",
    "locationType": "WAREHOUSE",
    "address": {
      "street": "123 Main St",
      "city": "Springfield",
      "state": "IL",
      "zip": "62701"
    }
  }'
```

### List with Pagination
```bash
curl "http://localhost:8010/api/v1/products?page=0&size=20&sort=sku,asc"
```

### Filter Locations
```bash
curl "http://localhost:8010/api/v1/locations?name=warehouse&locationType=WAREHOUSE&activeOnly=true"
```

## Error Handling

The API implements comprehensive error handling:

| Status Code | Scenario | Example |
|-------------|----------|---------|
| 200 | Successful GET/PUT | Fetch product details |
| 201 | Resource created | New product created successfully |
| 204 | No content (DELETE) | Product deleted successfully |
| 400 | Bad request | Missing required fields |
| 404 | Not found | Product ID doesn't exist |
| 409 | Conflict | SKU or code already exists |
| 500 | Server error | Database connection failure |

## Data Validation

### Uniqueness Constraints
- **UOM**: `code` must be unique
- **Product**: `sku` must be unique
- **Product Variant**: `sku` must be unique
- **Location**: `code` must be unique

### Required Fields
- **UOM**: code, name
- **Product**: sku, name, productType, baseUom
- **Product Variant**: sku, name
- **Location**: code, name, locationType

### Immutable Fields
- **SKU** in both Product and ProductVariant
- **Location Code** cannot be changed after creation

## Soft Delete Strategy

Products and ProductVariants use soft deletes:
- **Update**: `active = false, updated_at = NOW()`
- **List**: Returns only `active = true` records
- **Fetch**: Returns only `active = true` records
- **Data preservation**: Original data kept for audit trails

Locations use hard deletes (physical removal from database).

## Pagination & Sorting

All list endpoints support:

**Query Parameters:**
- `page` (default: 0) - Zero-indexed page number
- `size` (default: 20) - Items per page (max: 100)
- `sort` (default: entity-specific) - Format: `field,order` (e.g., "sku,asc")

**Response Metadata:**
```json
{
  "pagination": {
    "page": 0,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8
  }
}
```

## Filtering (Locations)

Location list endpoint supports filtering:
- `code` - Wildcard search (ILIKE)
- `name` - Wildcard search (ILIKE)
- `locationType` - Exact match (WAREHOUSE|STORE|PRODUCTION)
- `activeOnly` (default: true) - Boolean filter

**Example:**
```bash
curl "http://localhost:8010/api/v1/locations?locationType=WAREHOUSE&name=main&activeOnly=true"
```

## JSONB Fields

**Product Metadata:**
```json
{
  "metadata": {
    "color": "red",
    "size": "large",
    "material": "cotton",
    "supplier": "ABC Corp"
  }
}
```

**Product Variant Attributes:**
```json
{
  "attributes": {
    "size": "M",
    "color": "blue",
    "sku_suffix": "-BLU-M"
  }
}
```

**Location Address:**
```json
{
  "address": {
    "street": "123 Main Street",
    "city": "Springfield",
    "state": "IL",
    "country": "USA",
    "postalCode": "62701",
    "coordinates": {
      "latitude": 39.7817,
      "longitude": -89.6501
    }
  }
}
```

## Starting the Application

1. Ensure PostgreSQL is running with the Literp database migrated
2. Update `cfg.properties` with correct HTTP port (default: 8010)
3. Build the project:
   ```bash
   ./gradlew build
   ```
4. Run the application:
   ```bash
   ./gradlew run
   ```
5. API will be available at `http://localhost:8010/api/v1`

## Database Dependencies

Ensure the following tables exist (created by Alembic migration):
- `unit_of_measure`
- `product`
- `product_variant`
- `location`

All foreign key relationships are properly established:
- `product.base_uom` → `unit_of_measure.uom_id` (FK)
- `product_variant.product_id` → `product.product_id` (FK, CASCADE)

## Configuration

**File: `cfg.properties`**
```properties
http.port=8010
```

## OpenAPI Specs

The implementation is based on two OpenAPI 3.0 specifications:
- `api_collections/open_api_spec/product-catalog.yaml` - Product catalog operations
- `api_collections/open_api_spec/locations.yaml` - Location management operations

Total: 21 endpoints across 4 resources

## Reactive Processing

All handlers use RxJava3 Single/Observable for:
- Non-blocking database queries
- Efficient resource utilization
- Automatic thread management
- Error propagation through observable chains
- Composable async operations

**Handler Pattern:**
```kotlin
repository.someOperation(params)
    .subscribe(
        { result -> putResponse(context, 200, result) },
        { error -> putErrorResponse(context, 500, error.message) }
    )
```

## Future Enhancements

- Authentication/Authorization (JWT tokens)
- Request validation with Bean Validation
- Logging and request tracing
- Metrics collection (Micrometer)
- Caching strategies for frequently accessed entities
- Batch operations for bulk inserts/updates
- Full-text search capabilities
- Audit log tracking
- API rate limiting
