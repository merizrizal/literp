# 02. Master Data API

## Goal

Complete the catalog and location APIs so clients can safely manage the master
data required by order and inventory workflows.

## Scope

This phase covers Unit of Measure, Product, Product Variant, and Location
endpoints, including request validation, response contracts, delete semantics,
OpenAPI parity, Bruno examples, and automated verification.

## Current Completed Work

### Unit of Measure

- [x] `GET /uom` implemented
- [x] `POST /uom` implemented
- [x] `GET /uom/{uomId}` implemented
- [x] `PUT /uom/{uomId}` implemented
- [x] `DELETE /uom/{uomId}` implemented
- [x] create validates required `code` and `name`
- [x] update validates required `name`
- [x] duplicate code check exists
- [x] delete currently hard-deletes rows

### Product

- [x] `GET /products` implemented
- [x] `POST /products` implemented
- [x] `GET /products/{productId}` implemented
- [x] `PUT /products/{productId}` implemented
- [x] `DELETE /products/{productId}` implemented
- [x] create validates required `sku`, `name`, `productType`, and `baseUom`
- [x] update validates required `name` and `productType`
- [x] duplicate SKU check exists
- [x] delete soft-deletes with `active = false`

### Product Variant

- [x] `GET /products/{productId}/variants` implemented
- [x] `POST /products/{productId}/variants` implemented
- [x] `GET /products/{productId}/variants/{variantId}` implemented
- [x] `PUT /products/{productId}/variants/{variantId}` implemented
- [x] `DELETE /products/{productId}/variants/{variantId}` implemented
- [x] create validates required `sku` and `name`
- [x] update validates required `name`
- [x] duplicate SKU check exists
- [x] delete soft-deletes with `active = false`

### Location

- [x] `GET /locations` implemented
- [x] `POST /locations` implemented
- [x] `GET /locations/{locationId}` implemented
- [x] `GET /locations/by-code/{code}` implemented
- [x] `PUT /locations/{locationId}` implemented
- [x] `DELETE /locations/{locationId}` implemented
- [x] create validates required `code`, `name`, and `locationType`
- [x] update validates required `name` and `locationType`
- [x] duplicate code check exists
- [x] list supports `code`, `name`, `locationType`, and `activeOnly`
- [x] delete currently hard-deletes rows

### Assets

- [x] Product catalog OpenAPI YAML exists
- [x] Product catalog OpenAPI JSON exists
- [x] Location OpenAPI YAML exists
- [x] Location OpenAPI JSON exists
- [x] Bruno requests exist for master-data APIs
- [x] API testing guide includes master-data examples

## Remaining Work

- [ ] Normalize list response envelopes
- [ ] Normalize single-resource response envelopes
- [ ] Decide final delete policy for UOM and Location
- [ ] Return `404` when delete requests target missing resources
- [ ] Handle foreign-key delete conflicts with stable `409` responses
- [ ] Implement product list filters documented in OpenAPI
- [ ] Apply product update `baseUom` if the contract keeps it writable
- [ ] Apply product update `active` if the contract keeps it writable
- [ ] Apply location create/update `isActive` if the contract keeps it writable
- [ ] Add pagination bounds validation for `page` and `size`
- [ ] Make JSON metadata and address mapping robust when database values are null
- [ ] Add repository tests for duplicate checks, soft delete, hard delete, and not-found behavior
- [ ] Add HTTP integration tests for all master-data endpoints
- [ ] Keep Bruno examples aligned after response envelope changes
- [ ] Regenerate or update OpenAPI JSON after YAML changes

## Assumptions

- Product and variant SKUs remain immutable.
- UOM code and location code remain immutable.
- Product and variant soft delete remains the default because order history can reference them.
- Location and UOM hard delete can remain only if foreign-key conflict behavior is explicit.

## Definition of Done

- [ ] OpenAPI specs match handler behavior exactly
- [ ] Bruno examples match actual responses
- [ ] Master-data endpoints have automated success and error tests
- [ ] Response envelope shape is stable
- [ ] Delete behavior is documented and verified
