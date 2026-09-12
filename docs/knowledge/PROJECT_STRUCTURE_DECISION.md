# Project Structure Decision

**Source:** `docs/implementation-plan/04-quality-contracts-observability.md` task 04.6 and `docs/implementation-plan/ads/phase-04-task-5-6.md`.

**Decision status:** Accepted.

**Approving maintainer:** Project maintainer.

**Approval date:** 2026-09-12.

**Source revision:** `c4ad200 build(deps): upgrade Kotlin and Vert.x versions`.

**Gate status:** This accepted layout decision does not move source files or API assets. The README and Phase 05 plan publication remain pending later chunks.

## Accepted Structure Decision

Retain the current layer-based Kotlin backend layout with domain-grouped Java service interfaces through Phase 05. Retain OpenAPI assets under `api_collections/open_api_spec` and the Bruno collection under `api_collections/Literp`.

No domain-first source migration or `api/openapi` / `api/bruno` asset relocation is authorized before Phase 05. New feature work must use the placement map below and must not create empty packages merely to reserve names.

### Domain Placement Map

All Kotlin paths are relative to `src/main/kotlin/com/literp`; Java paths are relative to `src/main/java/com/literp`.

| Domain | Accepted placement |
|---|---|
| Catalog | Existing Kotlin `verticle/handler`, `repository`, and `service/master/impl`; existing Java `service/master`. |
| Location | Existing Kotlin `verticle/handler`, `repository`, and `service/master/impl`; existing Java `service/master`. |
| Order | Existing Kotlin `verticle/handler`, `repository`, and `service/order/impl`; existing Java `service/order`. |
| Inventory | Keep current stock operations in the order-process handler/service/repository path; do not extract a package only to satisfy this decision. |
| POS | Proposed Kotlin `verticle/handler`, `repository`, and `service/pos/impl`; proposed Java `service/pos` when concrete POS services require proxy contracts. |
| Manufacturing | Proposed Kotlin `verticle/handler`, `repository`, and `service/manufacturing/impl`; proposed Java `service/manufacturing` when concrete manufacturing services require proxy contracts. |

The proposed POS/manufacturing Java service groups require codegen convention confirmation before creation. They must add their own `@ModuleGen` package contract only when a concrete proxy service is introduced.

### API Asset Policy

Future OpenAPI contracts remain in `api_collections/open_api_spec` as matching YAML/JSON pairs with their supporting README files. Future Bruno requests remain in `api_collections/Literp` and use the existing collection/environment conventions.

Adding an OpenAPI bundle later requires deliberate updates to the runtime loader, `scripts/verify_openapi_assets.py`, `OpenApiOperationIdRegistrationTest`, relevant Bruno assets, documentation, and CI evidence. This is feature work in its own approved slice, not an asset-layout migration.

## Decision Evidence

- Existing Kotlin code is organized by `verticle/handler`, `repository`, and service implementation layers; Java proxy contracts are currently grouped in `service/master` and `service/order`.
- The existing master/order `package-info.java` files declare distinct Vert.x `@ModuleGen` group packages, and service proxies are generated from those contracts.
- `HttpServerVerticle` loads three OpenAPI YAML contracts from `api_collections/open_api_spec`.
- `scripts/verify_openapi_assets.py` hardcodes the same asset root and current bundle names. `OpenApiOperationIdRegistrationTest` hardcodes the YAML paths and `HttpServerVerticle` path.
- The existing Bruno collection root is `api_collections/Literp` and includes its collection metadata and local environment.

Keeping these paths avoids a broad cross-layer rewrite while Phase 05 adds new behavior. The current structure is therefore accepted as sufficient through Phase 05, subject to the review triggers below.

## Migration Constraints and Deferred Alternatives

No source or asset move belongs in this decision record. Domain-first packages and an `api/openapi` / `api/bruno` relocation are deferred.

If a relocation is approved later, first create a separate migration ADS with an old-to-new mapping and compile-safe mechanical slices. Each slice must cover imports, service proxy references, `@ModuleGen`/generated proxy metadata, loader/verifier/test paths, Bruno paths, documentation, CI, and runtime startup checks. Do not edit generated proxy output directly.

A future move requires behavior coverage adequate to expose relocation regressions, plus targeted build, integration-test, OpenAPI-verification, Bruno-path, and production-router startup evidence. Do not mix a mechanical move with authentication or feature behavior changes.

## Review Triggers

Revisit the layout after Phase 05 or earlier when measured coupling, package navigation, contract loading, deployment, or test maintenance costs justify a migration.
