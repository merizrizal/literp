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

- [x] Implement product list filters documented in OpenAPI or remove them from the contract
- [x] Apply product update `baseUom` if the contract keeps it writable
- [x] Apply product update `active` if the contract keeps it writable
- [x] Apply location create/update `isActive` if the contract keeps it writable
- [x] Regenerate or update OpenAPI JSON after YAML changes

Done when:

- [x] Product catalog OpenAPI behavior matches handlers
- [x] Location OpenAPI behavior matches handlers
- [x] OpenAPI YAML and JSON are synchronized

Implementation notes:

- [x] Product list supports `sku`, `productType`, and `activeOnly` filters.
- [x] Product get supports `includeVariants=true`.
- [x] Product create/update applies optional `active`; update also applies optional `baseUom`.
- [x] Product variant list supports `sort` and `activeOnly`; variant update/delete are scoped by `productId` and `variantId`.
- [x] Location create/update applies optional `isActive`.

### 02.4 Pagination And JSON Robustness

Estimate: 1-1.5 engineer-days

Tasks:

- [x] Add pagination bounds validation for `page` and `size`
- [x] Add sort validation behavior for unsupported fields
- [x] Make JSON metadata mapping robust when database values are null
- [x] Make JSON address mapping robust when database values are null

Done when:

- [x] Invalid pagination input returns a clear validation error
- [x] JSON fields can be null without handler or repository crashes
- [x] Sorting behavior is documented and predictable

Implementation notes:

- [x] Master-data list handlers validate `page >= 0`, `size` from `1` through `100`, and `sort` as `field,asc` or `field,desc`.
- [x] UOM, product, variant, and location list handlers reject unsupported sort fields with `400` error envelopes.
- [x] `activeOnly` and `includeVariants` query parameters reject non-boolean values with `400` error envelopes.
- [x] Product `metadata`, product variant `attributes`, and location `address` map database nulls to empty JSON objects.
- [x] OpenAPI sort descriptions list the supported sort fields for each master-data list endpoint.

### 02.5 Master Data Verification

Estimate: 1.5-2.5 engineer-days

Tasks:

- [x] Add repository tests for duplicate checks
- [x] Add repository tests for soft delete and hard delete behavior
- [x] Add repository tests for not-found behavior
- [x] Add HTTP integration tests for all master-data endpoints
- [x] Keep Bruno examples aligned after response envelope changes

Done when:

- [x] Master-data happy paths are covered by automated tests
- [x] Master-data error paths are covered by automated tests
- [x] Bruno collection remains usable for manual verification

Implementation notes:

- [x] `MasterDataRepositoryTest` covers duplicate checks, delete/not-found behavior, and nullable JSON mapping.
- [x] `MasterDataHttpIntegrationTest` covers all UOM, product, product variant, and location HTTP endpoints through OpenAPI routers.
- [x] HTTP integration tests cover duplicate, missing resource, missing parent product, pagination, sort, and boolean validation errors.
- [x] Bruno master-data scripts read normalized `data` response payloads.

## Assumptions

- Product and variant SKUs remain immutable.
- UOM code and location code remain immutable.
- Product and variant soft delete remains the default because order history can reference them.
- Location and UOM hard delete remains the current policy because foreign-key conflict behavior is explicit.

## Definition of Done

- [x] OpenAPI specs match handler behavior exactly
- [x] Bruno examples match actual responses
- [x] Master-data endpoints have automated success and error tests
- [x] Response envelope shape is stable
- [x] Delete behavior is documented and verified
