# Literp API - Quick Start Guide

## 30-Second Setup

Everything is already implemented! Just run:

```bash
# Build the project
./gradlew build

# Start the server (listening on http://localhost:8010)
./gradlew run
```

## Test Your First Endpoint (30 seconds)

```bash
# List all UOMs
curl http://localhost:8010/api/v1/uom | python3 -m json.tool

# Create a UOM
curl -X POST http://localhost:8010/api/v1/uom \
  -H "Content-Type: application/json" \
  -d '{"code":"EA","name":"Each"}' | python3 -m json.tool
```

## 21 Fully Implemented Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| /uom | GET | List units of measure |
| /uom | POST | Create UOM |
| /uom/{id} | GET | Get UOM details |
| /uom/{id} | PUT | Update UOM |
| /uom/{id} | DELETE | Delete UOM |
| /products | GET | List products |
| /products | POST | Create product |
| /products/{id} | GET | Get product |
| /products/{id} | PUT | Update product |
| /products/{id} | DELETE | Delete product |
| /products/{id}/variants | GET | List variants |
| /products/{id}/variants | POST | Create variant |
| /products/{id}/variants/{vid} | GET | Get variant |
| /products/{id}/variants/{vid} | PUT | Update variant |
| /products/{id}/variants/{vid} | DELETE | Delete variant |
| /locations | GET | List locations |
| /locations | POST | Create location |
| /locations/{id} | GET | Get location |
| /locations/by-code/{code} | GET | Find by code |
| /locations/{id} | PUT | Update location |
| /locations/{id} | DELETE | Delete location |

## What's Implemented

✅ **4 Resources**: UOM, Product, ProductVariant, Location
✅ **21 Endpoints**: Full CRUD for all resources
✅ **Pagination**: Size, page, sort parameters on list endpoints
✅ **Filtering**: Advanced filters on locations
✅ **Error Handling**: Proper HTTP status codes and messages
✅ **Data Validation**: Unique constraints, required fields
✅ **Database**: PostgreSQL with connection pooling
✅ **Async/Reactive**: Non-blocking I/O with RxJava3
✅ **Soft Deletes**: Products/Variants marked inactive
✅ **JSONB Support**: Metadata, attributes, address fields

## Key Features

### Pagination
```bash
curl "http://localhost:8010/api/v1/products?page=0&size=20&sort=sku,asc"
```

### Filtering Locations
```bash
curl "http://localhost:8010/api/v1/locations?locationType=WAREHOUSE&name=main"
```

### Create with Metadata
```bash
curl -X POST http://localhost:8010/api/v1/products \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "PROD-001",
    "name": "Product",
    "productType": "STOCK",
    "baseUom": "{uom_id}",
    "metadata": {"color": "red", "brand": "Acme"}
  }'
```

## Response Formats

### Success (200, 201)
```json
{
  "data": { "id": "...", "name": "...", ... }
}
```

### List (200)
```json
{
  "data": [ ... ],
  "pagination": { "page": 0, "size": 20, "totalElements": 100, "totalPages": 5 }
}
```

### Error (400, 404, 409, 500)
```json
{
  "error": "Descriptive error message",
  "status": 400
}
```

## Database

Connects to: `postgres://postgres:postgres@localhost:5432/literp`

Make sure Alembic migrations are applied:
```bash
cd python/database/migration
alembic upgrade head
```

## Documentation

- **[API_IMPLEMENTATION.md](API_IMPLEMENTATION.md)** - Technical deep dive
- **[API_TESTING_GUIDE.md](API_TESTING_GUIDE.md)** - All 21 curl examples
- **[IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md)** - What was built

## Docker Stack

```bash
# Database and infrastructure ready
make env-up  # Starts PostgreSQL, etc.
```

## Architecture

```
HTTP Requests (8010)
        ↓
HttpServerVerticle
        ↓
Route Handlers (21 handlers)
        ↓
Repository Layer (4 repos)
        ↓
PostgreSQL (RxJava3 async)
        ↓
Database (UOM, Product, ProductVariant, Location)
```

## Common Tasks

### List all products
```bash
curl http://localhost:8010/api/v1/products
```

### Get product with ID
```bash
curl http://localhost:8010/api/v1/products/{productId}
```

### Create location
```bash
curl -X POST http://localhost:8010/api/v1/locations \
  -H "Content-Type: application/json" \
  -d '{
    "code": "WH-001",
    "name": "Main Warehouse",
    "locationType": "WAREHOUSE",
    "address": {"street": "123 Main", "city": "Springfield"}
  }'
```

### Find location by code
```bash
curl http://localhost:8010/api/v1/locations/by-code/WH-001
```

### Update product
```bash
curl -X PUT http://localhost:8010/api/v1/products/{id} \
  -H "Content-Type: application/json" \
  -d '{
    "name": "New Name",
    "productType": "STOCK",
    "metadata": {"updated": true}
  }'
```

### Delete product (soft delete - marked inactive)
```bash
curl -X DELETE http://localhost:8010/api/v1/products/{id}
```

## Status Codes

- **200** - Successful GET/PUT
- **201** - Successfully created
- **204** - Successfully deleted (no body)
- **400** - Bad request (missing fields, validation error)
- **404** - Resource not found
- **409** - Conflict (duplicate SKU, code already exists)
- **500** - Server error

## Files You Need to Know

```
src/main/kotlin/com/literp/
├── verticle/HttpServerVerticle.kt  ← Main handler registry
├── repository/                      ← Data access layer
│   ├── UnitOfMeasureRepository.kt
│   ├── ProductRepository.kt
│   ├── ProductVariantRepository.kt
│   └── LocationRepository.kt
├── db/DatabaseConnection.kt         ← Connection pool
└── config/Config.kt                 ← HTTP port config
```

## Troubleshooting

**Port already in use?**
```bash
# Change in cfg.properties
http.port=8011  # or any available port
```

**Database connection failed?**
```bash
# Ensure PostgreSQL is running
make env-up

# Check credentials in DatabaseConnection.kt:
# localhost:5432, database: literp, user: postgres
```

**Build errors?**
```bash
./gradlew clean build
```

## Next Steps

1. ✅ Read [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md)
2. ✅ Review [API_TESTING_GUIDE.md](API_TESTING_GUIDE.md)
3. ✅ Test endpoints with curl or Postman
4. ⏳ Add authentication (JWT tokens)
5. ⏳ Add request validation (Bean Validation)
6. ⏳ Add caching for frequently accessed data
7. ⏳ Add metrics and monitoring

## Questions?

All implementation details are in:
- **Setup**: This file
- **Testing**: API_TESTING_GUIDE.md
- **Architecture**: API_IMPLEMENTATION.md
- **Summary**: IMPLEMENTATION_SUMMARY.md

---

**Status**: ✅ Ready to Use
**Total Endpoints**: 21
**Technology**: Vert.x 5.0.8, Kotlin 2.3.10, RxJava3
**Database**: PostgreSQL with Alembic migrations
