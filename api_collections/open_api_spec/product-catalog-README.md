# Literp Product Catalog API - OpenAPI Specification

This document contains the OpenAPI 3.0 specification for the **Literp Product Catalog API**, which provides comprehensive CRUD operations for managing products, variants, and units of measure.

## Files

- **product-catalog.yaml** - OpenAPI spec in YAML format (human-readable)
- **product-catalog.json** - OpenAPI spec in JSON format (tool-compatible)

Both files contain the exact same specification in different formats. Choose based on your preference or tool requirements.

## API Overview

### Base URL
- Development: `http://localhost:8010/api/v1`
- Production: `https://api.literp.example.com/api/v1`

### Three Main Resource Groups

#### 1. Unit of Measure (`/uom`)
Manage standardized units of measure (EA, KG, LTR, etc.)

**Operations:**
- `GET /uom` - List all units with pagination
- `POST /uom` - Create a new unit
- `GET /uom/{uomId}` - Get unit details
- `PUT /uom/{uomId}` - Update unit (code is immutable)
- `DELETE /uom/{uomId}` - Delete unit (if no products reference it)

**Example UOM Codes:** EA (Each), KG (Kilogram), LTR (Liter), M (Meter), H (Hour)

#### 2. Product (`/products`)
Manage core product entities with SKU, pricing, and type.

**Operations:**
- `GET /products` - List all products with filtering and pagination
- `POST /products` - Create a new product
- `GET /products/{productId}` - Get product details with optional variants
- `PUT /products/{productId}` - Update product (SKU is immutable)
- `DELETE /products/{productId}` - Delete product (if no inventory/orders exist)

**Product Types:**
- `STOCK` - Inventory-tracked products
- `SERVICE` - Service offerings (not inventory-tracked)

**Query Filters:**
- `sku` - Filter by SKU (wildcard search)
- `productType` - Filter by STOCK or SERVICE
- `activeOnly` - Return only active products

#### 3. Product Variant (`/products/{productId}/variants`)
Manage variants like sizes, colors, or other combinations.

**Operations:**
- `GET /products/{productId}/variants` - List all variants for a product
- `POST /products/{productId}/variants` - Create a new variant
- `GET /products/{productId}/variants/{variantId}` - Get variant details
- `PUT /products/{productId}/variants/{variantId}` - Update variant (SKU is immutable)
- `DELETE /products/{productId}/variants/{variantId}` - Delete variant

**Variant Examples:**
- A "T-Shirt" product might have variants: "T-Shirt - Small - Red", "T-Shirt - Medium - Blue", etc.
- Each variant gets its own unique SKU (e.g., TSHIRT-S-RED, TSHIRT-M-BLU)

## Key Design Principles

### 1. SKU Immutability
- **Product SKU** is set at creation and cannot be changed to maintain referential integrity
- **Variant SKU** is also immutable once created
- This ensures inventory movements, sales orders, and reservations remain consistent

### 2. Movement-Based Inventory
- Products support inventory tracking via the movement-based ledger
- The spec defines product types (STOCK/SERVICE) for inventory applicability
- SERVICE products do not create inventory movements

### 3. Extensibility via JSONB
- **Product metadata** field stores extensible attributes (weight, dimensions, color palette, etc.)
- **Variant attributes** field holds variant-specific data (size, color, pattern, etc.)
- Both allow schema evolution without table migrations

### 4. Multi-Location Support
- Products are location-agnostic; they have a base UOM
- Inventory is always location-specific (handled by inventory movements)
- Variants inherit parent product's UOM

### 5. Active/Inactive Status
- Products and variants can be soft-deleted by setting `active = false`
- This prevents hard deletes from breaking historical references
- Filters default to `activeOnly=true` for cleaner list views

## Request/Response Examples

### Create a Unit of Measure

**Request:**
```bash
POST /api/v1/uom
Content-Type: application/json

{
  "code": "KG",
  "name": "Kilogram",
  "baseUnit": "g"
}
```

**Response (201 Created):**
```json
{
  "data": {
    "uomId": "550e8400-e29b-41d4-a716-446655440000",
    "code": "KG",
    "name": "Kilogram",
    "baseUnit": "g",
    "createdAt": "2026-02-17T10:30:00Z",
    "updatedAt": "2026-02-17T10:30:00Z"
  }
}
```

### Create a Product

**Request:**
```bash
POST /api/v1/products
Content-Type: application/json

{
  "sku": "WIDGET-A",
  "name": "Widget A",
  "productType": "STOCK",
  "baseUom": "550e8400-e29b-41d4-a716-446655440000",
  "active": true,
  "metadata": {
    "weight": 2.5,
    "weight_unit": "kg",
    "color": "blue"
  }
}
```

**Response (201 Created):**
```json
{
  "data": {
    "productId": "660e8400-e29b-41d4-a716-446655440000",
    "sku": "WIDGET-A",
    "name": "Widget A",
    "productType": "STOCK",
    "baseUom": "550e8400-e29b-41d4-a716-446655440000",
    "baseUomCode": "KG",
    "active": true,
    "metadata": {
      "weight": 2.5,
      "weight_unit": "kg",
      "color": "blue"
    },
    "createdAt": "2026-02-17T10:30:00Z",
    "updatedAt": "2026-02-17T10:30:00Z"
  }
}
```

### Create a Product Variant

**Request:**
```bash
POST /api/v1/products/660e8400-e29b-41d4-a716-446655440000/variants
Content-Type: application/json

{
  "sku": "WIDGET-A-S",
  "name": "Widget A - Small",
  "attributes": {
    "size": "S",
    "color": "blue"
  },
  "active": true
}
```

**Response (201 Created):**
```json
{
  "data": {
    "variantId": "770e8400-e29b-41d4-a716-446655440000",
    "productId": "660e8400-e29b-41d4-a716-446655440000",
    "sku": "WIDGET-A-S",
    "name": "Widget A - Small",
    "attributes": {
      "size": "S",
      "color": "blue"
    },
    "active": true,
    "createdAt": "2026-02-17T10:30:00Z",
    "updatedAt": "2026-02-17T10:30:00Z"
  }
}
```

### List Products with Filters

**Request:**
```bash
GET /api/v1/products?page=0&size=20&productType=STOCK&activeOnly=true&sort=sku,asc
```

**Response (200 OK):**
```json
{
  "data": [
    {
      "productId": "660e8400-e29b-41d4-a716-446655440000",
      "sku": "WIDGET-A",
      "name": "Widget A",
      "productType": "STOCK",
      "baseUom": "550e8400-e29b-41d4-a716-446655440000",
      "baseUomCode": "KG",
      "active": true,
      "metadata": {...},
      "createdAt": "2026-02-17T10:30:00Z",
      "updatedAt": "2026-02-17T10:30:00Z"
    }
  ],
  "pagination": {
    "page": 0,
    "size": 20,
    "total": 150,
    "totalPages": 8,
    "hasNext": true,
    "hasPrevious": false
  }
}
```

## Error Handling

All error responses follow a consistent format:

```json
{
  "code": "ERROR_CODE",
  "message": "Human-readable error message",
  "details": {
    "field": "fieldName",
    "value": "problematicValue"
  },
  "timestamp": "2026-02-17T10:30:00Z",
  "path": "/api/v1/products/invalid-id"
}
```

### Common HTTP Status Codes

| Status | Meaning | Example Scenarios |
|--------|---------|-------------------|
| 201 | Created | Resource created successfully |
| 204 | No Content | Resource deleted successfully |
| 400 | Bad Request | Validation failed, invalid input |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | SKU already exists, or deletion conflicts |
| 500 | Server Error | Unexpected error on server |

## Validation Rules

### SKU Format
- Alphanumeric and hyphens only
- Pattern: `^[A-Z0-9\-]+$`
- Unique across products and variants
- Examples: `PROD-001`, `WIDGET-A-S`, `SERVICE-001`

### UOM Code Format
- Uppercase alphanumeric
- Pattern: `^[A-Z0-9]{1,50}$`
- Standard codes: EA, KG, LTR, M, CM, H, MIN, etc.

### Field Lengths
- Product/Variant names: max 255 characters
- UOM code: max 50 characters
- SKU: max 255 characters

### Immutable Fields
- Product SKU (cannot be updated)
- Variant SKU (cannot be updated)
- UOM code (cannot be updated)

## Using this Specification

### With Swagger UI
```bash
docker run -p 8000:8080 \
  -e SWAGGER_JSON=/spec/product-catalog.yaml \
  -v $(pwd):/spec \
  swaggerapi/swagger-ui
```

### With Postman
1. Import the OpenAPI file: `File > Import > Select product-catalog.json`
2. Set the base URL in environment variables
3. Use the generated collection for testing

### With OpenAPI Generator
```bash
# Generate Kotlin client
openapi-generator-cli generate \
  -i product-catalog.yaml \
  -g kotlin \
  -o src/generated/openapi-client

# Generate Java server
openapi-generator-cli generate \
  -i product-catalog.yaml \
  -g java-spring \
  -o src/generated/openapi-server
```

### With ReDoc
```bash
docker run -p 8000:80 \
  -e SPEC_URL=/spec/product-catalog.yaml \
  -v $(pwd):/spec \
  redoc
```

## Compatibility with Database Schema

This OpenAPI specification is **fully aligned** with the database schema defined in:
- `./python/database/migration/alembic/versions/314b57a8dd0f_00_initial_migration.py`

All entity fields, types, enums, and constraints in this spec match the database migration exactly:

| API Resource | Database Table | Notes |
|--------------|----------------|-------|
| Unit of Measure | `unit_of_measure` | Immutable code |
| Product | `product` | Soft-delete via active flag |
| Product Variant | `product_variant` | Cascade delete with product |

## Future Enhancements

- **Authentication**: Bearer token JWT support (placeholder in spec)
- **Bulk Operations**: Batch create/update for products and variants
- **Search**: Full-text search on product names and metadata
- **Caching Headers**: ETag and Last-Modified support
- **Webhooks**: Event notifications for product changes
- **GraphQL**: Alternative query interface (alongside REST)

## Related Documentation

- [PROJECT_OVERVIEW.md](../../docs/knowledge/PROJECT_OVERVIEW.md) - Project vision and principles
- [MODEL_DESIGN.md](../../docs/knowledge/MODEL_DESIGN.md) - Complete database schema design
- [DATABASE_MIGRATION](../database/migration/) - Alembic migration files
- [Inventory Location API](locations-README.md) - Manage warehouses and stores
