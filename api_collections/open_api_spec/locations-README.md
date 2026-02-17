# Literp Inventory Location API - OpenAPI Specification

This directory contains the OpenAPI 3.0 specification for the **Literp Inventory Location API**, which provides comprehensive CRUD operations for managing inventory locations (warehouses, stores, production facilities).

## Files

- **locations.yaml** - OpenAPI spec in YAML format (human-readable)
- **locations.json** - OpenAPI spec in JSON format (tool-compatible)

Both files contain the exact same specification in different formats. Choose based on your preference or tool requirements.

## API Overview

### Base URL
- Development: `http://localhost:8010/api/v1`
- Production: `https://api.literp.example.com/api/v1`

### Resource

#### Location (`/locations`)
Manage physical inventory locations across multi-location systems.

**Operations:**
- `GET /locations` - List all locations with pagination and filtering
- `POST /locations` - Create a new location
- `GET /locations/{locationId}` - Get location details
- `PUT /locations/{locationId}` - Update location (code is immutable)
- `DELETE /locations/{locationId}` - Delete location (if no relationships exist)
- `GET /locations/by-code/{code}` - Get location by code (convenience endpoint)

## Location Types

The Location API supports three types of inventory locations:

| Type | Purpose | Example Use Cases |
|------|---------|-------------------|
| **WAREHOUSE** | Central distribution or storage facility | Primary warehouse, distribution center, cold storage |
| **STORE** | Retail point-of-sale location | Retail shop, restaurant, kiosk, franchise location |
| **PRODUCTION** | Manufacturing or assembly facility | Factory floor, assembly line, production center |

Each location type may have different business logic and constraints in upstream systems.

## Key Design Principles

### 1. Code Immutability
- **Location code** is set at creation and cannot be changed
- This maintains referential integrity for inventory movements
- If a location code needs changing, you must delete and recreate (only if no active relationships)

### 2. Flexible Address Storage
- **Address field** uses JSONB for extensibility
- Supports arbitrary fields: street, city, state, postal_code, country, latitude, longitude, etc.
- Allows schema evolution without table migrations
- Example:
  ```json
  {
    "street": "123 Industrial Way",
    "city": "Springfield",
    "state": "IL",
    "postal_code": "62701",
    "country": "USA",
    "latitude": 39.7817,
    "longitude": -89.6501
  }
  ```

### 3. Soft Delete via Active Status
- Locations can be deactivated by setting `isActive = false`
- Prevents hard deletes from breaking historical references
- Filters default to `activeOnly=true` for cleaner views
- Useful for archiving old locations while preserving audit trails

### 4. Multi-Location Support
- Locations are central to Literp's multi-location inventory model
- Every inventory movement originates from or goes to a location
- Sales orders are tied to a location
- Work orders (manufacturing) target a location

### 5. Code Format & Uniqueness
- Location codes follow pattern: `^[A-Z0-9\-]+$` (uppercase alphanumeric and hyphens)
- Codes are globally unique
- Example codes: `WH-001`, `STORE-NYC`, `PROD-MAIN`, `FULFILLMENT-CENTER-1`

## Request/Response Examples

### Create a Location

**Request:**
```bash
POST /api/v1/locations
Content-Type: application/json

{
  "code": "WH-001",
  "name": "Main Warehouse",
  "locationType": "WAREHOUSE",
  "isActive": true,
  "address": {
    "street": "123 Industrial Way",
    "city": "Springfield",
    "state": "IL",
    "postal_code": "62701",
    "country": "USA",
    "latitude": 39.7817,
    "longitude": -89.6501
  }
}
```

**Response (201 Created):**
```json
{
  "data": {
    "locationId": "550e8400-e29b-41d4-a716-446655440000",
    "code": "WH-001",
    "name": "Main Warehouse",
    "locationType": "WAREHOUSE",
    "isActive": true,
    "address": {
      "street": "123 Industrial Way",
      "city": "Springfield",
      "state": "IL",
      "postal_code": "62701",
      "country": "USA",
      "latitude": 39.7817,
      "longitude": -89.6501
    },
    "createdAt": "2026-02-17T10:30:00Z",
    "updatedAt": "2026-02-17T10:30:00Z"
  }
}
```

### Create a Store Location

**Request:**
```bash
POST /api/v1/locations
Content-Type: application/json

{
  "code": "STORE-NYC-001",
  "name": "5th Avenue Store",
  "locationType": "STORE",
  "isActive": true,
  "address": {
    "street": "500 5th Avenue",
    "city": "New York",
    "state": "NY",
    "postal_code": "10110",
    "country": "USA"
  }
}
```

**Response (201 Created):**
```json
{
  "data": {
    "locationId": "660e8400-e29b-41d4-a716-446655440001",
    "code": "STORE-NYC-001",
    "name": "5th Avenue Store",
    "locationType": "STORE",
    "isActive": true,
    "address": {
      "street": "500 5th Avenue",
      "city": "New York",
      "state": "NY",
      "postal_code": "10110",
      "country": "USA"
    },
    "createdAt": "2026-02-17T10:30:00Z",
    "updatedAt": "2026-02-17T10:30:00Z"
  }
}
```

### Create a Production Facility

**Request:**
```bash
POST /api/v1/locations
Content-Type: application/json

{
  "code": "PROD-MAIN",
  "name": "Main Manufacturing Plant",
  "locationType": "PRODUCTION",
  "isActive": true,
  "address": {
    "street": "456 Factory Boulevard",
    "city": "Chicago",
    "state": "IL",
    "postal_code": "60601",
    "country": "USA"
  }
}
```

### Update a Location

**Request:**
```bash
PUT /api/v1/locations/550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{
  "name": "Main Warehouse - North Campus",
  "isActive": true,
  "address": {
    "street": "456 New Industrial Blvd",
    "city": "Springfield",
    "state": "IL",
    "postal_code": "62702",
    "country": "USA"
  }
}
```

**Response (200 OK):**
```json
{
  "data": {
    "locationId": "550e8400-e29b-41d4-a716-446655440000",
    "code": "WH-001",
    "name": "Main Warehouse - North Campus",
    "locationType": "WAREHOUSE",
    "isActive": true,
    "address": {
      "street": "456 New Industrial Blvd",
      "city": "Springfield",
      "state": "IL",
      "postal_code": "62702",
      "country": "USA"
    },
    "createdAt": "2026-02-17T10:30:00Z",
    "updatedAt": "2026-02-17T10:35:00Z"
  }
}
```

### Deactivate a Location

**Request:**
```bash
PUT /api/v1/locations/550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{
  "isActive": false
}
```

This performs a soft delete—the location is marked inactive but remains in the database.

### List Locations with Filters

**Request:**
```bash
GET /api/v1/locations?page=0&size=20&locationType=WAREHOUSE&activeOnly=true&sort=code,asc
```

**Response (200 OK):**
```json
{
  "data": [
    {
      "locationId": "550e8400-e29b-41d4-a716-446655440000",
      "code": "WH-001",
      "name": "Main Warehouse",
      "locationType": "WAREHOUSE",
      "isActive": true,
      "address": {...},
      "createdAt": "2026-02-17T10:30:00Z",
      "updatedAt": "2026-02-17T10:30:00Z"
    }
  ],
  "pagination": {
    "page": 0,
    "size": 20,
    "total": 45,
    "totalPages": 3,
    "hasNext": true,
    "hasPrevious": false
  }
}
```

### Get Location by Code

**Request:**
```bash
GET /api/v1/locations/by-code/WH-001
```

**Response (200 OK):**
```json
{
  "data": {
    "locationId": "550e8400-e29b-41d4-a716-446655440000",
    "code": "WH-001",
    "name": "Main Warehouse",
    "locationType": "WAREHOUSE",
    "isActive": true,
    "address": {...},
    "createdAt": "2026-02-17T10:30:00Z",
    "updatedAt": "2026-02-17T10:30:00Z"
  }
}
```

## Query Filters

When listing locations with `GET /locations`, you can filter and sort:

| Parameter | Type | Description | Example |
|-----------|------|-------------|---------|
| `page` | integer | Page number (0-indexed) | `page=0` |
| `size` | integer | Items per page (1-100) | `size=20` |
| `sort` | string | Sort field and order | `sort=code,asc` |
| `code` | string | Filter by code (wildcard) | `code=WH` |
| `name` | string | Filter by name (wildcard) | `name=warehouse` |
| `locationType` | string | Filter by type | `locationType=WAREHOUSE` |
| `activeOnly` | boolean | Only active locations | `activeOnly=true` |

Examples:
```bash
# Get all active warehouses, sorted by name
GET /api/v1/locations?locationType=WAREHOUSE&activeOnly=true&sort=name,asc

# Search for store locations containing "NYC"
GET /api/v1/locations?code=NYC&locationType=STORE

# Get all locations, inactive included
GET /api/v1/locations?activeOnly=false
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
  "path": "/api/v1/locations/invalid-id"
}
```

### Common HTTP Status Codes

| Status | Meaning | Example Scenarios |
|--------|---------|-------------------|
| 200 | OK | Successful GET or PUT |
| 201 | Created | Location created successfully |
| 204 | No Content | Location deleted successfully |
| 400 | Bad Request | Validation failed, invalid input |
| 404 | Not Found | Location doesn't exist |
| 409 | Conflict | Code already exists, or deletion conflicts |
| 500 | Server Error | Unexpected error on server |

### Common Error Codes

| Error Code | Meaning | Example |
|-----------|---------|---------|
| `LOCATION_NOT_FOUND` | Location doesn't exist | `GET /locations/invalid-id` |
| `INVALID_INPUT` | Validation failed | Missing required field |
| `CODE_ALREADY_EXISTS` | Location code is not unique | Duplicate code in POST |
| `LOCATION_HAS_RELATIONSHIPS` | Can't delete due to references | Inventory movements exist |
| `INTERNAL_ERROR` | Server error | Unexpected exception |

## Validation Rules

### Code Format
- Alphanumeric and hyphens only
- Pattern: `^[A-Z0-9\-]+$`
- Unique across all locations
- Max 50 characters
- Examples: `WH-001`, `STORE-NYC`, `PROD-MAIN`, `FC-02`

### Location Type
- Must be one of: `WAREHOUSE`, `STORE`, `PRODUCTION`
- Cannot be null

### Name
- Max 255 characters
- Required
- Example: "Main Warehouse", "5th Avenue Store"

### Address
- Optional (nullable)
- Flexible JSON structure
- Can contain any fields relevant to your business
- Common fields: street, city, state, postal_code, country, latitude, longitude

### Active Status
- Boolean field
- Defaults to `true` at creation
- Use for soft delete (set to `false` instead of hard delete)

## Compatibility with Database Schema

This OpenAPI specification is **fully aligned** with the database schema defined in:
- `./python/database/migration/alembic/versions/314b57a8dd0f_00_initial_migration.py`

| API Field | Database Column | Type | Constraints |
|-----------|-----------------|------|-------------|
| `locationId` | `location_id` | String(36) | Primary Key |
| `code` | `code` | String(50) | Unique, Indexed |
| `name` | `name` | String(255) | Required |
| `locationType` | `location_type` | ENUM | WAREHOUSE, STORE, PRODUCTION |
| `isActive` | `is_active` | Boolean | Default: true |
| `address` | `address` | JSON | Nullable |
| `createdAt` | `created_at` | DateTime | Auto-set, Immutable |
| `updatedAt` | `updated_at` | DateTime | Auto-updated |

## Referential Integrity

Locations are referenced by several other entities. Attempting to delete a location that has active relationships will result in a **409 Conflict** error:

```json
{
  "code": "LOCATION_HAS_RELATIONSHIPS",
  "message": "Cannot delete location: it is referenced by 5 inventory movements",
  "details": {
    "referencedBy": ["inventory_movement", "sales_order", "pos_terminal"],
    "count": 5
  },
  "timestamp": "2026-02-17T10:30:00Z"
}
```

### Entities Referencing Locations

| Entity | Reference | Cascade Delete? |
|--------|-----------|-----------------|
| Inventory Movement | `to_location_id`, `from_location_id` | No |
| Sales Order | `location_id` | No |
| POS Terminal | `location_id` | No |
| POS Shift | (via terminal) | No |
| Work Order | `location_id` | No |
| Inventory Reservation | `location_id` | No |

**Best Practice:** Deactivate locations instead of deleting them:
```bash
PUT /api/v1/locations/{locationId}
{"isActive": false}
```

## Using this Specification

### With Swagger UI
```bash
docker run -p 8000:8080 \
  -e SWAGGER_JSON=/spec/locations.yaml \
  -v $(pwd):/spec \
  swaggerapi/swagger-ui
```

### With Postman
1. Import: `File > Import > Select locations.json`
2. Set base URL in environment
3. Use generated collection for testing

### With OpenAPI Generator
```bash
# Generate Kotlin client
openapi-generator-cli generate \
  -i locations.yaml \
  -g kotlin \
  -o src/generated/openapi-client

# Generate Java server
openapi-generator-cli generate \
  -i locations.yaml \
  -g java-spring \
  -o src/generated/openapi-server
```

## Related APIs

These OpenAPI specs complement the Location API:

- **Product Catalog API** (`product-catalog.yaml`) - Manage products and SKUs
- **Sales Order API** (coming soon) - Manage orders tied to locations
- **Inventory Movement API** (coming soon) - Track stock movements between locations
- **Work Order API** (coming soon) - Manage manufacturing at production locations

## Future Enhancements

- **Geofencing**: Support for radius/polygon geolocation queries
- **Capacity Management**: Stock capacity and utilization tracking
- **Transfer Permissions**: Control which locations can transfer to which
- **Webhooks**: Notifications for location changes
- **Batch Operations**: Create/update multiple locations at once
- **Performance Zones**: Assign locations to regions for analytics

## Related Documentation

- [PROJECT_OVERVIEW.md](../../docs/knowledge/PROJECT_OVERVIEW.md) - Project vision
- [MODEL_DESIGN.md](../../docs/knowledge/MODEL_DESIGN.md) - Complete database schema
- [DATABASE_MIGRATION](../database/migration/) - Alembic migration files
