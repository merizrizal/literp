# Verification Checklist

Use this checklist to validate the current implementation, docs, and testing assets.

## Runtime Startup

- [ ] `DIR=pgsql make env-up` starts PostgreSQL and migration containers
- [ ] Alembic reaches `head`
- [ ] seed data is populated
- [ ] `./gradlew build` completes
- [ ] `./gradlew run` starts the server on the configured port
- [ ] `GET /` returns a JSON response
- [ ] `GET /health/db` returns database `UP`

## CI Verification

- [ ] `Foundation Verification / Build` passes
- [ ] `Foundation Verification / Migration Verification` passes
- [ ] Build failures can be reproduced with `./gradlew build`
- [ ] Migration failures can be reproduced with `python scripts/verify_migrations.py`

## API Coverage

### Unit of Measure

- [ ] `GET /api/v1/uom`
- [ ] `POST /api/v1/uom`
- [ ] `GET /api/v1/uom/{uomId}`
- [ ] `PUT /api/v1/uom/{uomId}`
- [ ] `DELETE /api/v1/uom/{uomId}`

### Product

- [ ] `GET /api/v1/products`
- [ ] `POST /api/v1/products`
- [ ] `GET /api/v1/products/{productId}`
- [ ] `PUT /api/v1/products/{productId}`
- [ ] `DELETE /api/v1/products/{productId}`

### Product Variant

- [ ] `GET /api/v1/products/{productId}/variants`
- [ ] `POST /api/v1/products/{productId}/variants`
- [ ] `GET /api/v1/products/{productId}/variants/{variantId}`
- [ ] `PUT /api/v1/products/{productId}/variants/{variantId}`
- [ ] `DELETE /api/v1/products/{productId}/variants/{variantId}`

### Location

- [ ] `GET /api/v1/locations`
- [ ] `POST /api/v1/locations`
- [ ] `GET /api/v1/locations/{locationId}`
- [ ] `GET /api/v1/locations/by-code/{code}`
- [ ] `PUT /api/v1/locations/{locationId}`
- [ ] `DELETE /api/v1/locations/{locationId}`

### Order Process

- [ ] `GET /api/v1/orders`
- [ ] `POST /api/v1/orders`
- [ ] `GET /api/v1/orders/{salesOrderId}`
- [ ] `POST /api/v1/orders/{salesOrderId}/lines`
- [ ] `POST /api/v1/orders/{salesOrderId}/confirm`
- [ ] `POST /api/v1/orders/{salesOrderId}/payments`
- [ ] `POST /api/v1/orders/{salesOrderId}/fulfill`
- [ ] `POST /api/v1/orders/{salesOrderId}/cancel`

Total API endpoints to verify: `29`

## Data and Lifecycle Rules

- [ ] Product delete sets `active = false`
- [ ] Product delete returns `404` for missing or already inactive products
- [ ] Product list applies `sku`, `productType`, and `activeOnly`
- [ ] Product get applies `includeVariants=true`
- [ ] Product create/update applies documented `active`
- [ ] Product update applies documented `baseUom`
- [ ] Product variant delete sets `active = false`
- [ ] Product variant delete returns `404` for missing, mismatched parent product, or already inactive variants
- [ ] Product variant list applies `sort` and `activeOnly`
- [ ] Product variant create/update applies documented `active`
- [ ] Product variant update returns `404` for mismatched parent product
- [ ] UOM delete removes the row
- [ ] UOM delete returns `404` for missing rows and `409` for referenced rows
- [ ] Location create/update applies documented `isActive`
- [ ] Location delete removes the row
- [ ] Location delete returns `404` for missing rows and `409` for referenced rows
- [ ] Draft order creation defaults `salesChannel` to `POS` when omitted
- [ ] Add line is blocked for non-`DRAFT` orders
- [ ] Confirm is blocked when the order has no lines
- [ ] Confirm creates reservations and moves the order to `CONFIRMED`
- [ ] Payment capture is blocked for `DRAFT` or `CANCELLED` orders
- [ ] Fulfillment is blocked until captured payment covers the order total
- [ ] Fulfillment creates `inventory_movement` rows
- [ ] Cancel is blocked when captured payment exists
- [ ] Cancel is blocked for fulfilled orders

## Response Verification

- [ ] Utility endpoints return plain JSON objects
- [ ] Master-data list endpoints return top-level `data` plus top-level `pagination`
- [ ] Master-data create/get/update responses return the resource under top-level `data`
- [ ] Order-process command responses return a single `data` envelope
- [ ] Error responses contain `error`, `errorCode`, `status`, and `errorId`

## Seed Data Verification

- [ ] UOM seed data exists
- [ ] product seed data exists
- [ ] variant seed data exists
- [ ] location seed data exists
- [ ] opening / transfer inventory movements exist
- [ ] sales orders, lines, reservations, and payments exist
- [ ] POS terminal and shift records exist
- [ ] BOM and work order records exist

## Asset Verification

- [ ] OpenAPI specs exist under `api_collections/open_api_spec`
- [ ] Bruno collection exists under `api_collections/Literp`
- [ ] Bruno collection includes utility endpoints
- [ ] Bruno collection includes all 29 API endpoints
- [ ] Bruno collection variables are defined in `collection.bru`

## Documentation Verification

- [ ] `README.md` matches the current branch scope
- [ ] `docs/QUICK_START.md` matches current startup flow
- [ ] `docs/API_IMPLEMENTATION.md` reflects actual handler behavior
- [ ] `docs/API_TESTING_GUIDE.md` includes order-process testing
- [ ] `docs/ENDPOINTS_OVERVIEW.md` lists 29 endpoints
- [ ] `docs/CI_VERIFICATION.md` documents required CI checks
- [ ] `docs/LOCAL_RESET.md` separates non-destructive and destructive reset paths
- [ ] `docs/IMPLEMENTATION_SUMMARY.md` reflects seed data and Bruno assets
- [ ] `docs/README_API.md` points to the updated docs
- [ ] `docs/knowledge/PROJECT_SUMMARY.md` reflects the implemented platform state

## Known Gaps to Keep in Mind

- [ ] order-process list responses still need envelope normalization
- [ ] order-process OpenAPI parity still needs review after response-envelope normalization
- [ ] some multi-step order flows still need broader transaction coverage
- [ ] fulfillment still uses the non-null destination workaround for inventory movement
