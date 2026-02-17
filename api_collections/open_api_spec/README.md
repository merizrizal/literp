# Literp OpenAPI Specifications

This directory contains comprehensive OpenAPI 3.0 specifications for the **Literp** lightweight ERP system. Each specification provides CRUD operations for different modules of the system.

## Available APIs

### 1. Product Catalog API
**Location:** `product-catalog.yaml` / `product-catalog.json`

Manage products, variants, and units of measure—the foundation of the inventory system.

**Resources:**
- Unit of Measure - Standardize measurement units (EA, KG, LTR, etc.)
- Product - Core product catalog with STOCK/SERVICE types
- Product Variant - Manage product variants (sizes, colors, etc.)

**Documentation:** See [product-catalog-README.md](product-catalog-README.md) for detailed information.

**Features:**
- Immutable SKU enforcement
- Extensible metadata via JSONB
- Soft-delete via active flag
- Multi-location support

---

### 2. Inventory Location API
**Location:** `locations.yaml` / `locations.json`

Manage physical warehouse, store, and production locations across your multi-location inventory system.

**Resources:**
- Location - Warehouses, retail stores, production facilities

**Documentation:** See [locations-README.md](locations-README.md) for detailed information.

**Features:**
- Three location types: WAREHOUSE, STORE, PRODUCTION
- Flexible address storage via JSONB
- Code-based lookup endpoint
- Referential integrity support

---

## Quick Start

### View the Specs
Each API has both YAML and JSON versions for flexibility:

```bash
# YAML format (human-readable)
cat product-catalog.yaml
cat locations.yaml

# JSON format (tool-compatible)
cat product-catalog.json
cat locations.json
```

### Using with Swagger UI
```bash
docker run -p 8000:8080 \
  -e SWAGGER_JSON=/spec/product-catalog.yaml \
  -v $(pwd):/spec \
  swaggerapi/swagger-ui
```

Then visit: http://localhost:8000

### Using with Postman
1. Open Postman
2. Click `Import` → Select `File`
3. Choose any `.json` file from this directory
4. Collections and environment will be available for testing

### Using with ReDoc (Beautiful Documentation)
```bash
docker run -p 8000:80 \
  -e SPEC_URL=/spec/product-catalog.yaml \
  -v $(pwd):/spec \
  redoc
```

Then visit: http://localhost:8000

### Generate Client/Server Code
Use OpenAPI Generator to create code in your preferred language:

```bash
# Generate TypeScript client
openapi-generator-cli generate \
  -i product-catalog.yaml \
  -g typescript-axios \
  -o src/generated/typescript-client

# Generate Java Spring Boot server
openapi-generator-cli generate \
  -i product-catalog.yaml \
  -g java-spring \
  -o src/generated/server
```

## API Organization

All APIs follow a consistent design:

### Base URL
- **Development:** `http://localhost:8010/api/v1`
- **Production:** `https://api.literp.example.com/api/v1`

### Response Format
All responses follow a standard envelope:

```json
{
  "data": { ... },
  "pagination": { "page": 0, "size": 20, "total": 100, ... }
}
```

### Error Format
Consistent error responses across all APIs:

```json
{
  "code": "ERROR_CODE",
  "message": "Human-readable message",
  "details": { "field": "value" },
  "timestamp": "2026-02-17T10:30:00Z",
  "path": "/api/v1/resource"
}
```

### HTTP Status Codes
- **200** - OK (GET, PUT successful)
- **201** - Created (POST successful)
- **204** - No Content (DELETE successful)
- **400** - Bad Request (validation error)
- **404** - Not Found (resource doesn't exist)
- **409** - Conflict (duplicate, referential conflict)
- **500** - Server Error (unexpected error)

## Design Principles

All Literp APIs adhere to these core principles:

### 1. Immutable Keys
- Codes and SKUs cannot be modified once created
- Ensures referential integrity across systems
- Historical references remain valid

### 2. Soft Deletes
- Resources deactivated via `active`/`isActive` flag
- Preserves historical audit trails
- Prevents breaking existing references

### 3. Extensibility
- JSONB fields for flexible attributes
- Schema evolution without migrations
- Future-proof data structures

### 4. Multi-Location First
- All inventory operations location-aware
- Support for warehouses, stores, production
- Seamless transfers between locations

### 5. Pagination & Filtering
- All list endpoints support pagination
- Rich filtering capabilities
- Sortable results

## Database Alignment

All specs are **fully synchronized** with the database schema:
- `./python/database/migration/alembic/versions/314b57a8dd0f_00_initial_migration.py`

Each spec documents database table mappings and constraints.

## Upcoming APIs

The following APIs are planned for future implementation:

- **Sales Order API** - Create and manage customer orders
- **Inventory Movement API** - Track stock movements and ledger
- **Inventory Reservation API** - Reserve stock for orders
- **POS Operations API** - Manage terminals, shifts, and receipts
- **Bill of Materials API** - Define product recipes
- **Work Order API** - Manage manufacturing orders
- **Production Run API** - Track production batches

## Security

All APIs support:
- Bearer token JWT authentication (currently optional)
- Future: OAuth 2.0, API key authentication
- CORS support for web clients
- Input validation and sanitization

## Contributing

When adding new APIs:

1. Create OAS 3.0 spec (YAML + JSON)
2. Follow naming convention: `{resource-name}.yaml/json`
3. Add comprehensive README: `{resource-name}-README.md`
4. Document with examples and error cases
5. Update this main README with new API reference

## Related Documentation

- [PROJECT_OVERVIEW.md](../../docs/knowledge/PROJECT_OVERVIEW.md) - System vision and goals
- [MODEL_DESIGN.md](../../docs/knowledge/MODEL_DESIGN.md) - Complete database schema
- [DATABASE_MIGRATION](../database/migration/) - Migration files
- [ARCHITECTURE.md](../../docs/knowledge/ARCHITECTURE.md) - System architecture

## Support

For questions or issues with the OpenAPI specs:

1. Check the specific API README (e.g., `product-catalog-README.md`)
2. Review the MODEL_DESIGN.md for schema details
3. Validate specs using: `openapi-cli validate spec.yaml`

---

**Last Updated:** 2026-02-17
**OpenAPI Version:** 3.0.3
**Literp Version:** 1.0.0
