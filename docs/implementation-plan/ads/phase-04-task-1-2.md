## Architectural Design Specification: Phase 04 Test Foundation and API Contract Verification

**Source:** `docs/implementation-plan/04-quality-contracts-observability.md`, tasks `04.1` and `04.2`

**Goal:** Build a thin, automated verification baseline for repository behavior, PostgreSQL-backed flows, HTTP endpoint behavior, OpenAPI handler registration, normalized response envelopes, and stable error contracts before later CI, observability, security, or structure work begins.

---

### I. Overview and Contract

This ADS covers Phase 04 tasks only:

- `04.1 Test Foundation`
- `04.2 API And Contract Verification`

It does **not** cover Phase 04.3 CI wiring, Phase 04.4 logging/metrics/health expansion, Phase 04.5 security planning, or Phase 04.6 project restructuring.

The implementation should primarily add tests, fixtures, and test-running documentation. Production code changes should be limited to small testability seams or error-contract fixes that are directly required by failing contract tests.

#### Primary verification contracts

1. Repository behavior has focused coverage for query-building and validation edge cases.
2. PostgreSQL integration tests run against the isolated `literp_test` database, not local development data.
3. Test fixtures create and clean up data deterministically, using unique suffixes and explicit cleanup.
4. HTTP tests cover the implemented public API surface.
5. End-to-end order lifecycle tests cover draft creation, line insertion, confirmation, payment capture, fulfillment, cancellation, and invalid transitions.
6. OpenAPI `operationId` values cannot drift silently from handler registration.
7. Success responses and error responses are schema-checked against the current documented envelope shapes.
8. Error response tests cover validation, not found, conflict, database timeout, and internal failure behavior.

#### Existing concrete contracts

**Function Signature Contract (Concrete):** `OrderProcessService.listSalesOrders(int page, int size, String sort, String status, String salesChannel, String locationId): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.createSalesOrderDraft(String salesChannel, String locationId, String customerId, String currency, String notes): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.getSalesOrder(String salesOrderId): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.addSalesOrderLine(String salesOrderId, String productId, String sku, String quantityOrdered, String unitPrice): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.getCurrentStock(String productId, String locationId): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.getAvailableStock(String productId, String locationId): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.confirmSalesOrder(String salesOrderId, String idempotencyKey): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.capturePayment(String salesOrderId, String paymentMethod, String amount, String transactionRef, String idempotencyKey): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.fulfillSalesOrder(String salesOrderId, String createdBy, String notes, String idempotencyKey): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.cancelSalesOrder(String salesOrderId, String reason, String idempotencyKey): Future<JsonObject>`

**Function Signature Contract (Concrete):** `BaseRepository.inTransaction(work: (SqlConnection) -> Single<T>): Single<T>` exists for transactional repository flows.

**Route Registration Contract (Concrete):** `HttpServerVerticle.registerProductCatalogHandlers`, `registerLocationHandlers`, and `registerOrderProcessHandlers` register OpenAPI `operationId` values with `RouterBuilder.getRoute("...").addHandler(...)`.

**Error Envelope Contract (Concrete):** handler errors return JSON with `error`, `errorCode`, `status`, and `errorId`.

**Success Envelope Contract (Concrete):** most single-resource and command responses return top-level `data`; master-data list responses return top-level `data` and `pagination`; current docs note order-process list responses still use an older outer `data` wrapper.

#### Proposed test support contracts

**Function Signature Contract (Conceptual):** `HttpTestSupport.expect(method, path, expectedStatus, body?, headers?): HttpResult`

- Input: HTTP method, API-relative path, expected status, optional JSON body, optional headers.
- Output: test result containing status, raw body, and optional parsed `JsonObject`.
- Stub behavior if introduced first: compile-safe helper may delegate to existing Java `HttpClient` calls and initially assert only status and JSON parseability.
- Safety: helper lives under `src/test`; it cannot affect production behavior.

**Function Signature Contract (Conceptual):** `ApiEnvelopeAssertions.assertErrorEnvelope(json, status, expectedErrorCode?)`

- Input: parsed JSON response, expected HTTP status, optional expected `ErrorCodes` value.
- Output: assertion-only side effects.
- Stub behavior if introduced first: assert required keys only; later chunks add legacy-field exclusions and exact error code checks.
- Safety: fails tests without changing runtime behavior.

**Function Signature Contract (Conceptual):** `OrderHttpFixture.createFulfillableOrder(...)`

- Input: test suffix and optional quantities/prices.
- Output: IDs and response payloads needed for order lifecycle tests.
- Stub behavior if introduced first: may create only product/location/order skeleton and return clear fixture IDs; lifecycle chunks fill in line/payment/stock setup.
- Safety: all data uses unique suffixes and cleanup hooks.

**Contract Test Shape (Conceptual):** a test extracts OpenAPI `operationId` values from `api_collections/open_api_spec/*.yaml` and compares them to the operation IDs registered by the current router-registration source or a small central registration seam.

- If no production seam is introduced, source-text extraction from `HttpServerVerticle.kt` is acceptable as the first thin slice because it catches silent drift with minimal risk.
- If source-text tests become brittle, a later small refactor may centralize route-registration metadata.

### II. Observed Evidence and Assumptions

Observed evidence:

- `docs/implementation-plan/04-quality-contracts-observability.md` defines `04.1` as test foundation: repository query builder tests, PostgreSQL integration tests, seeded/new-data fixtures, and local test documentation.
- The same plan defines `04.2` as API/contract verification: HTTP tests for all implemented endpoints, end-to-end order lifecycle tests, OpenAPI operation ID registration tests, response envelope tests, and error tests for validation/not found/conflict/database timeout/internal failures.
- `src/test/kotlin/com/literp/verticle/MasterDataHttpIntegrationTest.kt` already provides an HTTP integration-test baseline using Java `HttpClient`, Vert.x `Router`, `RouterBuilder`, OpenAPI contracts, `TestDatabase`, and envelope assertions.
- `src/test/kotlin/com/literp/test/TestDatabase.kt` creates a PostgreSQL pool from `LITERP_TEST_PG_*` variables with defaults for `127.0.0.1:55432/literp_test` and skips tests through `assumeAvailable(pool)` when the database is unavailable.
- `src/test/kotlin/com/literp/repository/OrderProcessRepositoryTransactionTest.kt` already verifies many order repository behaviors, including rollback, idempotency, audit events, valid cancellation/fulfillment paths, split captured payments, inventory side effects, and invalid transitions.
- Repository list methods currently build sort fields and WHERE clauses inline in `UnitOfMeasureRepository`, `ProductRepository`, `ProductVariantRepository`, `LocationRepository`, and `OrderProcessRepository`.
- `BaseHandler` centralizes master-data list validation helpers: `parseListQuery`, `parseBooleanQueryParam`, `parseBoundedIntQueryParam`, and `parseSortQueryParam`.
- `HttpServerVerticle` loads three OpenAPI contracts and registers handlers by `operationId`: `product-catalog.yaml`, `locations.yaml`, and `order-process.yaml`.
- `api_collections/open_api_spec/*.yaml` currently contain 31 `operationId` entries: 15 product-catalog, 6 location, and 10 order-process operations.
- `README.md` and `docs/ENDPOINTS_OVERVIEW.md` still describe 29 API endpoints and order-process as 8 endpoints. This is drift from the currently observed OpenAPI YAML count, likely because stock endpoints were added after the docs count was written.
- `api_collections/Literp` contains Bruno files for the observed public API requests, including stock and utility endpoints.
- `ErrorCodes.kt` defines `VALIDATION_ERROR`, `RESOURCE_NOT_FOUND`, `CONFLICT`, `DB_TIMEOUT`, and `INTERNAL_ERROR`.
- `BaseHandler.putMappedErrorResponse` maps not-found, validation, conflict, and fallback internal errors, but no general API handler DB-timeout classifier was observed.
- `HttpServerVerticle.getDatabaseHealth` maps `TimeoutException` to `DB_TIMEOUT` for the database health endpoint only.
- `docs/CI_VERIFICATION.md` documents `./gradlew build` and `python scripts/verify_migrations.py`; local migration verification uses `python/requirements.txt`, `python/database/envrc.test`, and the isolated test database.

Assumptions to confirm during Chunk 0:

- Phase 04.1 may extend existing repository integration tests instead of forcing pure unit tests before query-builder extraction, unless the team explicitly wants extracted pure query-builder functions.
- The endpoint count should be updated to the observed OpenAPI/handler count before claiming “all endpoints” coverage; the plan’s “29 implemented endpoints” should not be copied blindly into tests.
- Tests may use isolated PostgreSQL and JUnit assumptions rather than requiring every developer to have PostgreSQL running for unit-only test commands.
- Response snapshot tests can be schema/assertion tests rather than serialized golden snapshots unless the team wants committed snapshot files.
- DB timeout and internal failure tests may require fake service/repository implementations or a small shared error-classification seam to avoid slow or flaky real timeout tests.

### III. Required Technical Dependencies and Imports

Existing dependencies and integration points:

- Kotlin `2.4.0`, Java `25`, Gradle wrapper, JUnit Jupiter, Vert.x `5.1.3`, RxJava3, PostgreSQL client.
- OpenAPI router support through `io.vertx.rxjava3.openapi.contract.OpenAPIContract` and `io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder`.
- HTTP test client pattern using `java.net.http.HttpClient`, `HttpRequest`, and `HttpResponse`.
- PostgreSQL test pool through `src/test/kotlin/com/literp/test/TestDatabase.kt`.
- Migration verification through `scripts/verify_migrations.py` and `python/requirements.txt`.

Likely files to read or change during implementation:

- `src/test/kotlin/com/literp/test/TestDatabase.kt`
- `src/test/kotlin/com/literp/test/HttpTestSupport.kt` (proposed new test helper)
- `src/test/kotlin/com/literp/test/OrderHttpFixture.kt` (proposed new test fixture helper)
- `src/test/kotlin/com/literp/repository/MasterDataRepositoryTest.kt`
- `src/test/kotlin/com/literp/repository/OrderProcessRepositoryTransactionTest.kt`
- `src/test/kotlin/com/literp/verticle/MasterDataHttpIntegrationTest.kt`
- `src/test/kotlin/com/literp/verticle/OrderProcessHttpIntegrationTest.kt` (proposed)
- `src/test/kotlin/com/literp/contract/OpenApiOperationIdRegistrationTest.kt` (proposed)
- `src/test/kotlin/com/literp/contract/ResponseEnvelopeContractTest.kt` (proposed, or folded into HTTP tests)
- `src/main/kotlin/com/literp/verticle/handler/BaseHandler.kt` only if contract tests expose a missing error-mapping seam
- `src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt` only if a route-registration testability seam is introduced
- `docs/API_TESTING_GUIDE.md` or `docs/CI_VERIFICATION.md` for local test-running instructions

No new production dependency should be introduced for YAML parsing unless the implementation team explicitly accepts it. Initial operationId extraction can be line-based because current YAML uses simple `operationId:` lines.

### IV. Step-by-Step Procedure / Execution Flow

1. Confirm current endpoint inventory.
   - Extract `operationId` values from the three OpenAPI YAML files.
   - Extract registered route names from `HttpServerVerticle` or a central registration seam if introduced.
   - Resolve whether Phase 04 tests should cover 31 observed API endpoints or the plan’s stale 29-endpoint count.

2. Consolidate test helpers.
   - Move repeated HTTP request, JSON parse, and envelope assertion logic out of `MasterDataHttpIntegrationTest` into test-only support files.
   - Keep helpers small and assertion-focused.
   - Preserve existing master-data tests while migrating them to the shared helper if it reduces duplication.

3. Build repository/query coverage.
   - Add focused tests for list sorting, fallback sort behavior, filter parameterization, pagination shape, blank/invalid validation inputs, and not-found/conflict repository behavior.
   - Prefer pure unit tests only after query-building logic is safely extracted. Otherwise use targeted PostgreSQL integration tests against `literp_test`.

4. Build deterministic fixtures.
   - Use unique suffixes per test run.
   - Reuse seeded UOM ID where appropriate (`TestDatabase.SEED_UOM_UNIT`).
   - Create products and locations through repositories or HTTP APIs.
   - Track created IDs and delete or deactivate records in `finally` blocks.
   - Avoid mutating deterministic seed rows except through isolated records created for the test.

5. Add order HTTP lifecycle coverage.
   - Start an in-process HTTP server on a random port, like `MasterDataHttpIntegrationTest`.
   - Register product, location, and order routers required by the lifecycle.
   - Create a location and product, create a draft order, add lines, confirm with `Idempotency-Key`, capture payment, fulfill, then fetch order/stock and assert side effects.
   - Add cancellation happy path and invalid transition/payment guardrail HTTP tests.

6. Add contract tests.
   - Compare OpenAPI operation IDs to handler registrations.
   - Assert that every registered route has a corresponding operationId.
   - Assert that every operationId in OpenAPI is registered exactly once.
   - Confirm docs/test naming reflects the resolved endpoint count.

7. Add envelope and error tests.
   - Assert success envelopes for list, single-resource, command, stock, and utility endpoints.
   - Assert error envelope required fields: `error`, `errorCode`, `status`, `errorId`.
   - Assert legacy fields such as `code` and `message` are absent from handler errors.
   - Cover validation `400`, not found `404`, conflict `409`, DB timeout `DB_TIMEOUT`, and internal `500` behavior.
   - If DB timeout is only implemented for `/health/db`, test that behavior explicitly; if public API timeout behavior is required, add a tiny error-classifier change and test it with a fake service/repository.

8. Document local execution.
   - Add local commands for unit-only tests, PostgreSQL-backed tests, and full build.
   - Include isolated database setup from `docs/CI_VERIFICATION.md`.
   - State which tests are skipped when PostgreSQL is unavailable.

### V. Failure Modes and Resilience

| Stage | Failure Mode | Agent/System Action | Next State/Error Report |
|---|---|---|---|
| Discovery | Plan says 29 endpoints but OpenAPI exposes 31 operationIds | Treat as repo/plan drift; confirm count from YAML and handler registrations before writing tests | ADS/Chunk 0 report names count mismatch and chosen coverage target |
| Test setup | PostgreSQL test DB unavailable | Use `TestDatabase.assumeAvailable(pool)` for integration tests; document setup command | JUnit skipped assumption with clear database availability message |
| Fixtures | Test data collides with seed or previous test data | Use unique suffixes and cleanup in `finally`; avoid shared mutable seed rows | Test remains isolated; cleanup failures reported with created IDs |
| Fixtures | Cleanup fails after partial test failure | Prefer idempotent cleanup by ID; delete order graph before product/location cleanup | Test failure includes cleanup context; no silent data corruption |
| Repository tests | Inline query builders are hard to unit-test without DB | Start with focused PostgreSQL integration coverage or extract a small pure helper in a separate chunk | No broad repository rewrite; query behavior still covered |
| HTTP tests | Router setup duplicates production route registrations and drifts | Introduce shared test helper or contract test comparing operation IDs to `HttpServerVerticle` registrations | Drift fails a targeted contract test |
| Contract tests | YAML parser dependency absent | Use line-based extraction for `operationId:` as a first slice | Test remains dependency-free; later parser optional |
| Error tests | Real DB timeout is slow or flaky | Use fake service/repository failure or health endpoint with controlled failure where possible | Deterministic `DB_TIMEOUT` assertion or explicit gap documented |
| Error mapping | API DB timeout maps to `INTERNAL_ERROR` | Add small classifier only after test proves gap; keep behavior localized | `DB_TIMEOUT` returned where contract requires it |
| Response schema | Current order list envelope differs from desired normalized envelope | Test current documented behavior first; open a separate implementation task for normalization if desired | Contract test records current shape without hidden behavior change |
| Validation | Full Gradle test suite is slow | Run targeted class tests per chunk, then final `./gradlew test` or `./gradlew build` | Fast feedback during chunks; full validation before done |

### VI. Security, Integrity, Idempotency, and Cleanup

- Tests must not embed credentials beyond existing local defaults already documented for the isolated test database.
- Test logs and assertion messages must not expose passwords from environment variables.
- HTTP lifecycle tests for confirm, payment capture, fulfill, and cancel must send explicit `Idempotency-Key` headers and should include at least one retry/idempotency assertion at HTTP level.
- PostgreSQL-backed tests must use `literp_test` defaults or environment overrides; they must not target the development database silently.
- Fixtures must use unique suffixes and must clean up order graphs before deleting referenced products or locations.
- Contract tests should avoid network calls to external services.
- OperationId parsing must read repository-local OpenAPI files only.
- Any production error-classification change must preserve the normalized error envelope and avoid leaking raw stack traces in HTTP responses.
- No authentication/security behavior is introduced in these tasks; Phase 04.5 owns security sequencing.

### VII. Validation Strategy

Validation must be chunk-aware and targeted first.

Syntax/compile validation:

```bash
./gradlew compileKotlin compileTestKotlin
```

Targeted repository tests:

```bash
./gradlew test --tests com.literp.repository.MasterDataRepositoryTest
./gradlew test --tests com.literp.repository.OrderProcessRepositoryTransactionTest
```

Targeted HTTP/contract tests after new classes exist:

```bash
./gradlew test --tests com.literp.verticle.MasterDataHttpIntegrationTest
./gradlew test --tests com.literp.verticle.OrderProcessHttpIntegrationTest
./gradlew test --tests com.literp.contract.OpenApiOperationIdRegistrationTest
./gradlew test --tests com.literp.contract.ResponseEnvelopeContractTest
```

Full local test/build validation before marking tasks complete:

```bash
./gradlew test
./gradlew build
```

Isolated PostgreSQL setup and migration validation, when database-backed tests are required:

```bash
cd docker
source envrc
make network
DIR=pgsql-test make env-up
cd ..
python3 -m pip install -r python/requirements.txt
source python/database/envrc.test
python scripts/verify_migrations.py
```

Contract inventory checks:

```bash
grep -R "operationId:" api_collections/open_api_spec/*.yaml
grep -R "getRoute(\"" src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt
```

Final review requirement:

```bash
git diff --stat
git diff -- src/test docs src/main/kotlin/com/literp/verticle src/main/kotlin/com/literp/common
```

Do not require `./gradlew test` for every small chunk if a targeted class proves the slice. Do require full `./gradlew test` or `./gradlew build` before claiming Phase 04.1–04.2 implementation complete.

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full feature in one pass.

#### Chunk 0: Discovery and Integration Confirmation
- **Goal:** Confirm endpoint count, current test coverage, test database setup, and exact files to change before editing.
- **Files to read:**
  - `docs/implementation-plan/04-quality-contracts-observability.md`
  - `README.md`
  - `docs/API_TESTING_GUIDE.md`
  - `docs/CI_VERIFICATION.md`
  - `src/test/kotlin/com/literp/verticle/MasterDataHttpIntegrationTest.kt`
  - `src/test/kotlin/com/literp/repository/OrderProcessRepositoryTransactionTest.kt`
  - `src/test/kotlin/com/literp/test/TestDatabase.kt`
  - `src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt`
  - `api_collections/open_api_spec/*.yaml`
- **Commands:**
  - `find src/test -type f | sort`
  - `grep -R "operationId:" api_collections/open_api_spec/*.yaml`
  - `grep -R "getRoute(\"" src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt`
  - `./gradlew test --tests com.literp.verticle.MasterDataHttpIntegrationTest`
- **Evidence to confirm:**
  - Whether tests should cover 31 observed OpenAPI operations or a corrected 29-endpoint subset.
  - Which existing tests already satisfy parts of 04.1 and 04.2.
  - Whether PostgreSQL-backed tests skip cleanly when `literp_test` is unavailable.
- **Stop condition:** Discovery notes identify the exact chunk-1 files and unresolved endpoint-count decision. No edits made.

#### Chunk 1: Test Support Contracts and Compile-Safe Helpers
- **Goal:** Introduce shared test-only HTTP/envelope support without changing production behavior.
- **Files to change:**
  - `src/test/kotlin/com/literp/test/HttpTestSupport.kt` (new, proposed)
  - Optionally `src/test/kotlin/com/literp/verticle/MasterDataHttpIntegrationTest.kt` if migrating existing helpers is small
- **Symbols to add/change:**
  - `HttpTestSupport` or equivalent test helper
  - `HttpResult` test data class
  - `assertErrorEnvelope`, `assertListEnvelope`, and basic success-envelope assertions
- **Implementation shape:**
  - Move or duplicate the existing request/expect/envelope pattern from `MasterDataHttpIntegrationTest` into a reusable test helper.
  - Keep the helper dependency-free and based on existing `HttpClient`/`JsonObject` usage.
  - If migrating the existing test is too broad, add the helper and leave existing tests unchanged until a later chunk.
- **Validation:**
  - `./gradlew compileTestKotlin`
  - `./gradlew test --tests com.literp.verticle.MasterDataHttpIntegrationTest`
- **Stop condition:** Test support compiles, existing master-data HTTP test still passes or skips only due to unavailable PostgreSQL.

#### Chunk 2: Repository Query and Fixture Foundation Slice
- **Goal:** Add focused repository/query coverage and deterministic fixture helpers needed by later HTTP order tests.
- **Files to change:**
  - `src/test/kotlin/com/literp/repository/MasterDataRepositoryTest.kt` or new `src/test/kotlin/com/literp/repository/RepositoryQueryBehaviorTest.kt`
  - `src/test/kotlin/com/literp/test/RepositoryFixtureSupport.kt` (new, proposed) if fixture duplication grows
- **Symbols to add/change:**
  - Tests for list sort fallback/allowed fields, filters, pagination envelope shape, and validation/not-found/conflict repository behavior
  - Fixture helper methods for creating/deleting product, location, UOM, and order test data
- **Implementation shape:**
  - Prefer adding narrow tests around existing repository methods before extracting query builder code.
  - If pure unit testing is required, extract only one small query-spec helper for one repository first, then test it; do not refactor all repositories in one chunk.
  - Use unique suffixes and cleanup blocks.
- **Validation:**
  - `./gradlew test --tests com.literp.repository.MasterDataRepositoryTest`
  - `./gradlew test --tests com.literp.repository.RepositoryQueryBehaviorTest` if a new class is created
- **Stop condition:** Repository behavior has additional focused coverage, fixtures are reusable, and no production behavior changed except any explicitly reviewed query-helper extraction.

#### Chunk 3: OpenAPI OperationId Registration Contract Slice
- **Goal:** Add a contract test that fails when OpenAPI operation IDs and handler registrations drift.
- **Files to change:**
  - `src/test/kotlin/com/literp/contract/OpenApiOperationIdRegistrationTest.kt` (new, proposed)
  - Optionally `src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt` only if introducing a central registration seam is chosen
- **Symbols to add/change:**
  - `OpenApiOperationIdRegistrationTest`
  - `openApiOperationIds()` test helper
  - `registeredOperationIds()` test helper, either source-text based or seam-based
- **Implementation shape:**
  - First thin slice may parse `operationId:` from YAML and `getRoute("...")` from `HttpServerVerticle.kt` with simple regexes.
  - Assert exact set equality and no duplicates.
  - Record the observed count in the assertion message rather than hard-coding stale docs counts.
  - If using a production seam, add called functions/types before switching `HttpServerVerticle` to them.
- **Validation:**
  - `./gradlew test --tests com.literp.contract.OpenApiOperationIdRegistrationTest`
  - `grep -R "operationId:" api_collections/open_api_spec/*.yaml`
- **Stop condition:** Contract test passes and clearly reports all registered/OpenAPI operation IDs.

#### Chunk 4: Order HTTP Lifecycle Thin Slice
- **Goal:** Add HTTP-level happy-path and invalid-transition coverage for order lifecycle endpoints.
- **Files to change:**
  - `src/test/kotlin/com/literp/verticle/OrderProcessHttpIntegrationTest.kt` (new, proposed)
  - `src/test/kotlin/com/literp/test/OrderHttpFixture.kt` (new, proposed) if needed
- **Symbols to add/change:**
  - `OrderProcessHttpIntegrationTest`
  - in-process order router setup using `OrderProcessRepository`, `OrderProcessServiceImpl`, and `OrderProcessHandler`
  - tests for draft → add line → confirm → capture payment → fulfill → get order/stock
  - tests for cancellation and invalid transition/payment guardrails
- **Implementation shape:**
  - Reuse `MasterDataHttpIntegrationTest` server pattern.
  - Register only the OpenAPI routers needed for the tested order path, plus product/location setup if created through HTTP.
  - Send `Idempotency-Key` headers for confirm, payment, fulfill, and cancel.
  - Assert response status, envelope shape, final order state, payment count, reservation/movement side effects, and stock quantity changes.
- **Validation:**
  - `./gradlew test --tests com.literp.verticle.OrderProcessHttpIntegrationTest`
  - `./gradlew test --tests com.literp.repository.OrderProcessRepositoryTransactionTest`
- **Stop condition:** HTTP order lifecycle tests pass or skip only when PostgreSQL test database is unavailable.

#### Chunk 5: Response and Error Contract Slice
- **Goal:** Verify normalized response envelopes and required error cases across representative endpoints.
- **Files to change:**
  - `src/test/kotlin/com/literp/contract/ResponseEnvelopeContractTest.kt` (new, proposed) or extend HTTP integration tests if smaller
  - `src/main/kotlin/com/literp/verticle/handler/BaseHandler.kt` only if tests expose missing DB timeout/internal mapping behavior that must be fixed now
- **Symbols to add/change:**
  - Tests for success envelope categories: list, single resource, command, stock, utility endpoint
  - Tests for error envelope categories: validation `400`, not found `404`, conflict `409`, timeout `DB_TIMEOUT`, internal `500`
  - Optional small error classifier if `TimeoutException` handling must be shared outside `/health/db`
- **Implementation shape:**
  - Start with schema assertions against existing real endpoints.
  - Use fake services/handlers for deterministic internal failure or timeout if real database failure would be flaky.
  - Do not normalize order list response in this chunk unless the test intentionally documents and drives that behavior change as a separate mini-slice.
- **Validation:**
  - `./gradlew test --tests com.literp.contract.ResponseEnvelopeContractTest`
  - `./gradlew test --tests com.literp.verticle.MasterDataHttpIntegrationTest`
  - `./gradlew test --tests com.literp.verticle.OrderProcessHttpIntegrationTest`
- **Stop condition:** Envelope/error contract tests pass and any production error-mapping change is minimal and covered.

#### Chunk 6: Local Test Documentation and Final Phase 04.1–04.2 Verification
- **Goal:** Document how to run the new tests locally and validate the complete Phase 04.1–04.2 test baseline.
- **Files to change:**
  - `docs/API_TESTING_GUIDE.md` and/or `docs/CI_VERIFICATION.md`
  - Optionally `README.md` or `docs/ENDPOINTS_OVERVIEW.md` if endpoint-count drift is corrected as part of the accepted scope
  - `docs/implementation-plan/04-quality-contracts-observability.md` only if marking tasks complete after implementation and validation
- **Symbols to add/change:**
  - Not applicable; documentation only.
- **Implementation shape:**
  - Add commands for targeted test classes, full test run, PostgreSQL test DB startup, and migration verification.
  - Explain skipped integration tests when PostgreSQL is unavailable.
  - Update endpoint-count wording only after the count decision is confirmed.
- **Validation:**
  - `./gradlew test`
  - `./gradlew build`
  - `python scripts/verify_migrations.py` after sourcing `python/database/envrc.test` if migration verification is in scope for the session
  - `git diff --stat`
  - `git diff -- docs src/test src/main/kotlin/com/literp/verticle src/main/kotlin/com/literp/common`
- **Stop condition:** Phase 04.1–04.2 implementation evidence is ready for review, with remaining Phase 04.3–04.6 work explicitly untouched.

### IX. Handoff to `chunked-implementation`

Recommended agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, safe-python-edit, and post-edit-discipline if available.

Task:
Implement Phase 04 tasks 04.1–04.2 from docs/implementation-plan/04-quality-contracts-observability.md using docs/implementation-plan/ads/phase-04-task-1-2.md.

Mode:
Execute Chunk 0 only. Do not edit files. Confirm repository evidence, endpoint count, existing test coverage, and exact files for Chunk 1. Stop after reporting findings.
```

After Chunk 0 is accepted:

```text
Use the chunked-implementation skill.
Execute Chunk 1 only from docs/implementation-plan/ads/phase-04-task-1-2.md.
Do not continue to Chunk 2.
After editing, run targeted validation and show git diff.
```

For later chunks, repeat the same pattern:

```text
Use the chunked-implementation skill.
Execute Chunk <N> only from docs/implementation-plan/ads/phase-04-task-1-2.md.
Do not continue to the next chunk.
Run the chunk-specific validation command, review the diff, and stop.
```

### X. Conclusion and Next Steps

Phase 04.1–04.2 should be implemented as a verification-first vertical slice, not as a broad refactor. The safest path is to add reusable test support, improve repository/query coverage, add an operationId drift test, add HTTP order lifecycle tests, then lock down response/error envelopes.

The first implementation session should execute Chunk 0 only because the repository currently shows an endpoint-count drift: implementation docs mention 29 API endpoints, while OpenAPI YAML currently exposes 31 operation IDs. That count must be resolved before “all endpoint” coverage is claimed.
