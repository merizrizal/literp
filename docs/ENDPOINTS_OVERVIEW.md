# Literp REST API - Endpoint Overview

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│         HTTP Requests (localhost:8010)                  │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│         OpenAPI Router (Vert.x)                         │
│    Loads: product-catalog.yaml + locations.yaml         │
└─────────────────────────────────────────────────────────┘
         ↙               ↓              ↘
    UOM Routes      Product Routes   Location Routes
         ↓               ↓              ↓
    ┌────────────┐ ┌────────────┐ ┌────────────┐
    │  5 Handlers│ │  10 Handlers│ │  6 Handlers│
    └────────────┘ └────────────┘ └────────────┘
         ↓               ↓              ↓
    ┌────────────────────────────────────────┐
    │  Repository Layer (Data Access)        │
    │  - UnitOfMeasureRepository             │
    │  - ProductRepository                   │
    │  - ProductVariantRepository            │
    │  - LocationRepository                  │
    └────────────────────────────────────────┘
         ↓
    ┌────────────────────────────────────────┐
    │  PostgreSQL (Async RxJava3)            │
    │  localhost:5432/literp                 │
    └────────────────────────────────────────┘
         ↓
    ┌────────────────────────────────────────┐
    │  Database Tables                       │
    │  - unit_of_measure                     │
    │  - product                             │
    │  - product_variant                     │
    │  - location                            │
    └────────────────────────────────────────┘
```

## Resource Hierarchy

```
API Root: /api/v1/

├── UOM (Unit of Measure)
│   ├── GET    /uom                 → listUnitOfMeasures
│   ├── POST   /uom                 → createUnitOfMeasure
│   ├── GET    /uom/{uomId}         → getUnitOfMeasure
│   ├── PUT    /uom/{uomId}         → updateUnitOfMeasure
│   └── DELETE /uom/{uomId}         → deleteUnitOfMeasure
│
├── Products
│   ├── GET    /products            → listProducts
│   ├── POST   /products            → createProduct
│   ├── GET    /products/{productId}         → getProduct
│   ├── PUT    /products/{productId}         → updateProduct
│   ├── DELETE /products/{productId}         → deleteProduct
│   │
│   └── Product Variants (nested under product)
│       ├── GET    /products/{productId}/variants              → listProductVariants
│       ├── POST   /products/{productId}/variants              → createProductVariant
│       ├── GET    /products/{productId}/variants/{variantId}  → getProductVariant
│       ├── PUT    /products/{productId}/variants/{variantId}  → updateProductVariant
│       └── DELETE /products/{productId}/variants/{variantId}  → deleteProductVariant
│
└── Locations
    ├── GET    /locations                   → listLocations
    ├── POST   /locations                   → createLocation
    ├── GET    /locations/{locationId}      → getLocation
    ├── GET    /locations/by-code/{code}    → getLocationByCode
    ├── PUT    /locations/{locationId}      → updateLocation
    └── DELETE /locations/{locationId}      → deleteLocation
```

## HTTP Methods & Status Codes

```
┌────────┬──────────────────────────────┬──────────────────────┐
│ Method │ Purpose                      │ Status Codes         │
├────────┼──────────────────────────────┼──────────────────────┤
│ GET    │ Retrieve resource(s)         │ 200, 404, 500        │
│ POST   │ Create new resource          │ 201, 400, 409, 500   │
│ PUT    │ Update existing resource     │ 200, 400, 404, 500   │
│ DELETE │ Remove/archive resource      │ 204, 404, 500        │
└────────┴──────────────────────────────┴──────────────────────┘
```

## Response Format by Endpoint Type

### Single Resource Response (GET by ID, POST, PUT)
```
Status: 200 or 201
Body:
{
  "data": {
    "id": "...",
    "code|sku": "...",
    "name": "...",
    ... other fields ...
    "createdAt": "2026-01-26T...",
    "updatedAt": "2026-01-26T..."
  }
}
```

### Collection Response (GET list)
```
Status: 200
Body:
{
  "data": [
    { ... object 1 ... },
    { ... object 2 ... },
    { ... object 3 ... }
  ],
  "pagination": {
    "page": 0,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8
  }
}
```

### Delete Response
```
Status: 204 No Content
Body: (empty)
```

### Error Response
```
Status: 400, 404, 409, or 500
Body:
{
  "error": "Descriptive error message",
  "status": 400
}
```

## Handler Implementation Flow

Each endpoint follows this pattern:

```
HTTP Request
    ↓
Route Handler (in HttpServerVerticle)
    ↓
Extract & Validate Request Data
    ↓
Call Repository Method
    ↓
Repository executes SQL via Vert.x SQL Client
    ↓
Return RxJava Single<JsonObject>
    ↓
Subscribe to Single
    ├─ Success: putResponse(context, statusCode, result)
    └─ Error:   putErrorResponse(context, statusCode, message)
    ↓
HTTP Response sent to client
```

## Data Flow Example: Create Product

```
curl -X POST http://localhost:8010/api/v1/products \
  -d '{"sku":"P001","name":"Product","productType":"STOCK","baseUom":"uom-id"}'

        ↓

HttpServerVerticle.createProduct(context)
  ├─ Extract: sku, name, productType, baseUom, metadata
  ├─ Validate: All required fields present
  ├─ Check: checkSkuExists(sku) → Single<Boolean>
  │         └─ if true → Single.error("SKU already exists")
  │         └─ if false → continue
  ├─ Call: productRepository.createProduct(...)
  │        └─ Insert with UUID, timestamps
  │        └─ Return Single<JsonObject>
  └─ Subscribe:
     ├─ Success: putResponse(context, 201, result)
     │           → {data: {productId: "...", sku: "P001", ...}}
     └─ Error:   putErrorResponse(context, 409, message)
                 → {error: "Product SKU already exists", status: 409}
```

## Pagination & Sorting Example

```
GET /api/v1/products?page=1&size=50&sort=name,desc

Query Parameters:
├─ page: 1 (second page, zero-indexed)
├─ size: 50 (items per page)
└─ sort: name,desc (sort by name descending)

Internal Processing:
├─ offset = 1 * 50 = 50
├─ ORDER BY name DESC
├─ LIMIT 50 OFFSET 50
└─ Calculate totalPages = ceil(totalElements / 50)

Response:
{
  "data": [ 50 items at offset 50 ... ],
  "pagination": {
    "page": 1,
    "size": 50,
    "totalElements": 245,
    "totalPages": 5
  }
}
```

## Filtering Example: Locations

```
GET /api/v1/locations?locationType=WAREHOUSE&name=main&activeOnly=true

Query Parameters:
├─ locationType: WAREHOUSE (exact match)
├─ name: main (wildcard search with ILIKE)
└─ activeOnly: true (filter is_active = true)

Internal Processing:
├─ WHERE location.active = true
├─ AND location_type = 'WAREHOUSE'
├─ AND location.name ILIKE '%main%'
└─ ORDER BY code ASC (default sort)

Response:
{
  "data": [ matching locations ... ],
  "pagination": { ... }
}
```

## Unique Constraint Validation

```
Before Create Operations:
├─ UOM: checkCodeExists(code)
├─ Product: checkSkuExists(sku)
├─ ProductVariant: checkSkuExists(sku)
└─ Location: checkCodeExists(code)

Flow:
repository.checkExists(value)
    ↓
Single<Boolean>
    ├─ if true:  Single.error("... already exists")
    └─ if false: Single.just(createNewRecord())
    ↓
subscribe onSuccess: putResponse(201, ...)
        onError:   putErrorResponse(409, ...)
```

## Soft Delete vs Hard Delete

```
Product / ProductVariant (Soft Delete):
├─ UPDATE product SET active = false, updated_at = NOW()
├─ List endpoints return WHERE active = true
├─ Data preserved for audit/history
└─ Can be "undeleted" with UPDATE

Location (Hard Delete):
├─ DELETE FROM location WHERE location_id = $1
├─ Data physically removed from database
└─ Cannot be recovered
```

## JSONB Field Examples

```
Product Metadata:
{
  "metadata": {
    "color": "red",
    "size": "M",
    "brand": "Acme",
    "supplier": "ABC Corp",
    "customField": "anyValue"
  }
}

ProductVariant Attributes:
{
  "attributes": {
    "size": "M",
    "color": "blue",
    "weight": 500,
    "sku_suffix": "-BLU-M"
  }
}

Location Address:
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
    },
    "timezone": "America/Chicago"
  }
}
```

## Error Scenarios & Status Codes

```
┌──────┬─────────────────────────────────────────────────┐
│ 400  │ Bad Request - Missing/invalid required field    │
│      │ • Missing code when creating UOM               │
│      │ • Invalid sort order syntax                    │
│      │ • Out of range page number                     │
├──────┼─────────────────────────────────────────────────┤
│ 404  │ Not Found - Resource doesn't exist             │
│      │ • GET /products/{id} where id not found        │
│      │ • GET /locations/by-code/INVALID-CODE          │
│      │ • Update non-existent product                 │
├──────┼─────────────────────────────────────────────────┤
│ 409  │ Conflict - Uniqueness constraint violated       │
│      │ • Create product with existing SKU            │
│      │ • Create location with existing code           │
│      │ • Create variant with duplicate SKU            │
├──────┼─────────────────────────────────────────────────┤
│ 500  │ Internal Server Error - Database failure        │
│      │ • Connection pool exhausted                    │
│      │ • Database query timeout                       │
│      │ • Unexpected exception                         │
└──────┴─────────────────────────────────────────────────┘
```

## Endpoint Statistics

```
┌─────────────────────┬───────┬────────────────────┐
│ Resource            │ Count │ Delete Strategy    │
├─────────────────────┼───────┼────────────────────┤
│ UOM                 │   5   │ Hard Delete        │
│ Product             │   5   │ Soft Delete        │
│ ProductVariant      │   5   │ Soft Delete        │
│ Location            │   6   │ Hard Delete        │
├─────────────────────┼───────┼────────────────────┤
│ TOTAL               │  21   │ Mixed              │
└─────────────────────┴───────┴────────────────────┘
```

## Request/Response Time Estimate

```
List (with pagination):     50-100ms  (depending on DB size & network)
Get Single:                 10-30ms   (indexed by primary key)
Create:                     30-60ms   (includes validation)
Create with Duplicate:      10-15ms   (constraint check fails fast)
Update:                     20-50ms   (includes validation)
Delete:                     15-30ms   (quick operation)
```

## Database Indexing

```
Automatic Indexes (Primary Keys):
├─ unit_of_measure(uom_id)
├─ product(product_id)
├─ product_variant(variant_id)
└─ location(location_id)

Unique Indexes:
├─ unit_of_measure(code) UNIQUE
├─ product(sku) UNIQUE
├─ product_variant(sku) UNIQUE
└─ location(code) UNIQUE

Regular Indexes:
├─ product(sku) for lookups
├─ product_variant(product_id) for nesting
└─ location(code) for lookups
```

## Concurrency Model

```
Each HTTP Request:
├─ Assigned to Vert.x event loop
├─ Async database query sent via RxJava Single
├─ Thread released while awaiting DB response
├─ Response processed when DB completes
└─ Result returned to client

Result:
├─ Single thread-per-request in theory
├─ Hundreds of concurrent requests possible
├─ No blocking I/O (async all the way)
└─ Efficient resource utilization
```

## Files & Functions

```
HttpServerVerticle (src/main/kotlin/com/literp/verticle/)
├─ loadProductCatalogAndLocations()
├─ registerProductCatalogHandlers()
├─ registerLocationHandlers()
├─ listUnitOfMeasures() → UOM list
├─ createUnitOfMeasure() → UOM create
├─ getUnitOfMeasure() → UOM get
├─ updateUnitOfMeasure() → UOM update
├─ deleteUnitOfMeasure() → UOM delete
├─ listProducts() → Product list
├─ createProduct() → Product create
├─ getProduct() → Product get
├─ updateProduct() → Product update
├─ deleteProduct() → Product delete
├─ listProductVariants() → Variant list
├─ createProductVariant() → Variant create
├─ getProductVariant() → Variant get
├─ updateProductVariant() → Variant update
├─ deleteProductVariant() → Variant delete
├─ listLocations() → Location list
├─ createLocation() → Location create
├─ getLocation() → Location get
├─ getLocationByCode() → Location by code
├─ updateLocation() → Location update
├─ deleteLocation() → Location delete
└─ (21 handler methods total)
```

---

**Total Endpoints**: 21
**Resources**: 4 (UOM, Product, ProductVariant, Location)
**HTTP Methods**: 4 (GET, POST, PUT, DELETE)
**Status Codes**: 6 (200, 201, 204, 400, 404, 409, 500)
**Response Types**: 3 (Single, List with Pagination, Error)
**Database Tables**: 4 (with foreign key relationships)
**Async Model**: RxJava3 with Vert.x
