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

## Ordered Tasks

### 02.1 Response Envelope Normalization

Estimate: 1-2 engineer-days

Tasks:

- [x] Choose the final list response envelope
- [x] Normalize list response envelopes
- [x] Choose the final single-resource response envelope
- [x] Normalize single-resource response envelopes
- [x] Update docs and Bruno examples for the final response shape

Done when:

- [x] UOM, product, variant, and location responses use one documented envelope style
- [x] API docs and Bruno examples match the actual response shape
- [x] Existing clients have a documented migration note if response shape changes

Implementation notes:

- [x] Master-data list responses use `{ "data": [], "pagination": {} }`
- [x] Master-data create/get/update responses use `{ "data": { ... } }`
- [x] Client migration note: replace `data.data` reads with `data`, and replace `data.pagination` reads with top-level `pagination`

### 02.2 Delete Semantics And Error Behavior

Estimate: 1-2 engineer-days

Tasks:

- [x] Decide final delete policy for UOM and Location
- [x] Return `404` when delete requests target missing resources
- [x] Handle foreign-key delete conflicts with stable `409` responses
- [x] Document hard delete and soft delete behavior per resource

Done when:

- [x] Delete behavior is explicit for every master-data resource
- [x] Missing resources return stable not-found responses
- [x] Referential conflicts return stable conflict responses

Implementation notes:

- [x] UOM and location use hard delete; missing rows return `404`, and PostgreSQL foreign-key violations return stable `409` responses.
- [x] Product and product variant use soft delete via `active = false`; missing or already inactive rows return `404`.
- [x] Product variant delete is scoped by both `productId` and `variantId`; mismatched parent product paths return `404`.

### 02.3 OpenAPI And Handler Parity

Estimate: 2-3 engineer-days

Tasks:

- [ ] Implement product list filters documented in OpenAPI or remove them from the contract
- [ ] Apply product update `baseUom` if the contract keeps it writable
- [ ] Apply product update `active` if the contract keeps it writable
- [ ] Apply location create/update `isActive` if the contract keeps it writable
- [ ] Regenerate or update OpenAPI JSON after YAML changes

Done when:

- [ ] Product catalog OpenAPI behavior matches handlers
- [ ] Location OpenAPI behavior matches handlers
- [ ] OpenAPI YAML and JSON are synchronized

### 02.4 Pagination And JSON Robustness

Estimate: 1-1.5 engineer-days

Tasks:

- [ ] Add pagination bounds validation for `page` and `size`
- [ ] Add sort validation behavior for unsupported fields
- [ ] Make JSON metadata mapping robust when database values are null
- [ ] Make JSON address mapping robust when database values are null

Done when:

- [ ] Invalid pagination input returns a clear validation error
- [ ] JSON fields can be null without handler or repository crashes
- [ ] Sorting behavior is documented and predictable

### 02.5 Master Data Verification

Estimate: 1.5-2.5 engineer-days

Tasks:

- [ ] Add repository tests for duplicate checks
- [ ] Add repository tests for soft delete and hard delete behavior
- [ ] Add repository tests for not-found behavior
- [ ] Add HTTP integration tests for all master-data endpoints
- [ ] Keep Bruno examples aligned after response envelope changes

Done when:

- [ ] Master-data happy paths are covered by automated tests
- [ ] Master-data error paths are covered by automated tests
- [ ] Bruno collection remains usable for manual verification

## Assumptions

- Product and variant SKUs remain immutable.
- UOM code and location code remain immutable.
- Product and variant soft delete remains the default because order history can reference them.
- Location and UOM hard delete remains the current policy because foreign-key conflict behavior is explicit.

## Definition of Done

- [ ] OpenAPI specs match handler behavior exactly
- [ ] Bruno examples match actual responses
- [ ] Master-data endpoints have automated success and error tests
- [ ] Response envelope shape is stable
- [x] Delete behavior is documented and verified
