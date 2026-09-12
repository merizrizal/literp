# Security Sequencing Decision

**Source:** `docs/implementation-plan/04-quality-contracts-observability.md` task 04.5 and `docs/implementation-plan/ads/phase-04-task-5-6.md`.

**Decision status:** Accepted.

**Approving maintainer:** Project maintainer.

**Approval date:** 2026-09-12.

**Source revision:** `c4ad200 build(deps): upgrade Kotlin and Vert.x versions`.

**Gate status:** This accepted decision is not authentication implementation and does not by itself complete task 04.5. The roadmap ownership and gate checkboxes remain pending the separate publication/closure chunks.

## Accepted Security Decision

Authentication and authorization implementation is owned by a proposed **05.0 Authentication and Authorization Baseline**. It must complete before Phase 05 expansion work starts at 05.1.

The first deployable baseline protects every current business operation, including read routes. It uses a minimal authenticated principal, explicit permissions, deny-by-default authorization, and resource-scope checks. It must not be released as a partially protected business API.

Until that baseline is implemented and validated, the unauthenticated business API must not be exposed to an internet-facing or otherwise untrusted network. Network isolation is an interim deployment constraint, not authorization.

### First Protected Surface

All paths in this table are relative to `/api/v1`. Capability names are approved conceptual permission boundaries; their claim/role representation remains an implementation decision.

| Operation ID | Method and path | Required capability |
|---|---|---|
| `listUnitOfMeasures` | GET `/uom` | `master-data.read` |
| `createUnitOfMeasure` | POST `/uom` | `master-data.write` |
| `getUnitOfMeasure` | GET `/uom/{uomId}` | `master-data.read` |
| `updateUnitOfMeasure` | PUT `/uom/{uomId}` | `master-data.write` |
| `deleteUnitOfMeasure` | DELETE `/uom/{uomId}` | `master-data.write` |
| `listProducts` | GET `/products` | `master-data.read` |
| `createProduct` | POST `/products` | `master-data.write` |
| `getProduct` | GET `/products/{productId}` | `master-data.read` |
| `updateProduct` | PUT `/products/{productId}` | `master-data.write` |
| `deleteProduct` | DELETE `/products/{productId}` | `master-data.write` |
| `listProductVariants` | GET `/products/{productId}/variants` | `master-data.read` |
| `createProductVariant` | POST `/products/{productId}/variants` | `master-data.write` |
| `getProductVariant` | GET `/products/{productId}/variants/{variantId}` | `master-data.read` |
| `updateProductVariant` | PUT `/products/{productId}/variants/{variantId}` | `master-data.write` |
| `deleteProductVariant` | DELETE `/products/{productId}/variants/{variantId}` | `master-data.write` |
| `listLocations` | GET `/locations` | `master-data.read` |
| `createLocation` | POST `/locations` | `master-data.write` |
| `getLocation` | GET `/locations/{locationId}` | `master-data.read` |
| `updateLocation` | PUT `/locations/{locationId}` | `master-data.write` |
| `deleteLocation` | DELETE `/locations/{locationId}` | `master-data.write` |
| `getLocationByCode` | GET `/locations/by-code/{code}` | `master-data.read` |
| `listSalesOrders` | GET `/orders` | `order.read` with authorized resource scope |
| `createSalesOrderDraft` | POST `/orders` | `order.write` with authorized location scope |
| `getSalesOrder` | GET `/orders/{salesOrderId}` | `order.read` with authorized resource scope |
| `addSalesOrderLine` | POST `/orders/{salesOrderId}/lines` | `order.write` with authorized resource scope |
| `confirmSalesOrder` | POST `/orders/{salesOrderId}/confirm` | `order.confirm` with authorized resource scope |
| `capturePayment` | POST `/orders/{salesOrderId}/payments` | `payment.capture` with authorized resource scope |
| `fulfillSalesOrder` | POST `/orders/{salesOrderId}/fulfill` | `order.fulfill` with authorized location and resource scope |
| `cancelSalesOrder` | POST `/orders/{salesOrderId}/cancel` | `order.cancel` with authorized resource scope |
| `getCurrentStock` | GET `/stock/current` | `inventory.read` with authorized location scope |
| `getAvailableStock` | GET `/stock/available` | `inventory.read` with authorized location scope |

### Utility-Route Policy

| Route | First-baseline policy |
|---|---|
| GET `/` | Explicit public exception; response remains minimal and non-sensitive. |
| GET `/health/live` | Explicit public probe exception; no dependency or operational details. |
| GET `/metrics` | Require an operator/service identity or an explicitly enforced private operator network; not public by default. |
| GET `/health/ready` | Require an operator/service identity or an explicitly enforced private operator network; not public by default. |
| GET `/health/db` | Require an operator/service identity or an explicitly enforced private operator network; not public by default. |

### Implementation Boundaries Still Required

The separate authentication implementation ADS must select an existing identity provider and define issuer/audience validation, credential lifecycle, organization/location/resource scope, actor derivation, deployment topology, and audit/logging behavior. Caller-supplied values such as `createdBy`, customer IDs, and location IDs are not authenticated identity.

## Decision Evidence

- The three OpenAPI YAML contracts define 31 `operationId` values, and `HttpServerVerticle` registers the same 31 OpenAPI routes.
- `HttpServerVerticle` separately exposes five utility routes: `/`, `/metrics`, `/health/live`, `/health/ready`, and `/health/db`.
- The inspected Kotlin/Java sources contain no authentication or authorization middleware. Catalog and location OpenAPI contracts contain bearer JWT schemes explicitly marked as future implementation.
- The Phase 05 owner task/link will be added by the roadmap publication chunk; its absence does not change this accepted security decision.

## Follow-Up Acceptance Criteria

The later authentication implementation ADS must define and validate:

- missing, invalid, and expired credentials are rejected before protected handler execution without database side effects;
- denied capabilities and denied organization/location/resource scope are rejected with no state mutation;
- each protected operation above succeeds for an authorized principal;
- operator routes are restricted and public probe exceptions remain minimal;
- existing lifecycle, transaction, idempotency, response, and request-ID behavior remain covered; and
- authorization outcomes use the repository's accepted error contracts before public OpenAPI requirements are changed.

## Deferred Scope

Do not treat this decision record as authentication implementation. Provider configuration, credential material, broad IAM administration, self-service account management, and runtime middleware are out of scope for this document.

## Review Triggers

Revisit this decision before an untrusted deployment, when a provider or organization/location scope is selected, or if Phase 05 sequencing changes.
