# Phase 04 Completion Evidence

> **Record status:** Open evidence inventory; this document does not declare Phase 04 complete.
>
> **Revision audited:** `c4ad200411c7a0efb5d54c18de5285cd55626470` (`c4ad200`)
>
> **Scope:** Current evidence for the five remaining Phase 04 Definition-of-Done criteria in the [phase plan](../implementation-plan/04-quality-contracts-observability.md), following the [closure ADS](../implementation-plan/ads/phase-04-closure-verification.md).

This is the canonical evidence record for the current closure audit. It separates automated, repository-inspection, manual-collection, and external evidence. It intentionally does not change the Phase 04 checklist or repeat the accepted security-sequencing and project-structure decisions.

## Evidence classification

| Class | Meaning in this record |
|---|---|
| Automated | A repository test, build, verifier, or generated test report produced a result. |
| Repository inspection | Source, workflow, OpenAPI, documentation, or collection contents were inspected locally. |
| Manual | A request collection or runtime action requires a manually executed check; file presence alone is not a passing runtime result. |
| External | Evidence depends on GitHub configuration or maintainer access unavailable in the local repository. |
| Open / blocked | The available evidence is insufficient to close the criterion. It is recorded explicitly rather than inferred as complete. |

## Current contract inventory

### Business operations

The three OpenAPI YAML/JSON pairs define 31 business operation IDs, and `HttpServerVerticle` registers 31 corresponding `getRoute` handlers:

| Contract pair | Domain coverage | Operation IDs | Local HTTP suite |
|---|---|---:|---|
| [`product-catalog.yaml`](../../api_collections/open_api_spec/product-catalog.yaml) / [`product-catalog.json`](../../api_collections/open_api_spec/product-catalog.json) | Unit of Measure, Product, Product Variant | 15 | [`MasterDataHttpIntegrationTest`](../../src/test/kotlin/com/literp/verticle/MasterDataHttpIntegrationTest.kt) |
| [`locations.yaml`](../../api_collections/open_api_spec/locations.yaml) / [`locations.json`](../../api_collections/open_api_spec/locations.json) | Location | 6 | [`MasterDataHttpIntegrationTest`](../../src/test/kotlin/com/literp/verticle/MasterDataHttpIntegrationTest.kt) |
| [`order-process.yaml`](../../api_collections/open_api_spec/order-process.yaml) / [`order-process.json`](../../api_collections/open_api_spec/order-process.json) | Orders and stock | 10 | [`OrderProcessHttpIntegrationTest`](../../src/test/kotlin/com/literp/verticle/OrderProcessHttpIntegrationTest.kt) |
| **Total** |  | **31** |  |

The earlier 29-operation wording is stale inventory text, not the current OpenAPI or production registration count.

### Utility routes

The production router in [`HttpServerVerticle.kt`](../../src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt) registers five utility routes:

- `GET /`
- `GET /metrics`
- `GET /health/live`
- `GET /health/ready`
- `GET /health/db`

### Response and correlation contract

[`BaseHandler.kt`](../../src/main/kotlin/com/literp/verticle/handler/BaseHandler.kt) produces the error fields `error`, `errorCode`, `status`, and `errorId`, and propagates or creates `X-Request-ID` for handler responses. [`HttpTestSupport.kt`](../../src/test/kotlin/com/literp/test/HttpTestSupport.kt) asserts those error fields and rejects the legacy `code` and `message` fields. The response shape is documented in [`API_IMPLEMENTATION.md`](../API_IMPLEMENTATION.md).

The production router has a global failure handler in `HttpServerVerticle`. `MasterDataHttpIntegrationTest` now deploys the production verticle and verifies utility routes, unmatched-route status, normalized error fields, and request-ID propagation.

## Automated evidence

| Evidence | Result | Scope and source |
|---|---|---|
| `JAVA_TOOL_OPTIONS='-Djdk.lang.Process.launchMechanism=FORK' rtk ./gradlew --no-daemon test --rerun-tasks` | Passed: 36 tests, 0 skipped, 0 failures, 0 errors | Local Gradle baseline. JUnit reports are under [`build/test-results/test`](../../build/test-results/test). |
| `JAVA_TOOL_OPTIONS='-Djdk.lang.Process.launchMechanism=FORK' rtk ./gradlew --no-daemon build` | Passed | Local build corresponding to the baseline. |
| `source ~/Documents/PyEnv/myEnv/bin/activate && rtk python scripts/verify_openapi_assets.py` | Passed: all three YAML/JSON pairs matched; version `0.0.1` matched [`build.gradle.kts`](../../build.gradle.kts) | OpenAPI asset and version drift verification. |

The recorded 36-test baseline consists of the current reports for `MasterDataHttpIntegrationTest` (5), `OrderProcessHttpIntegrationTest` (4), `OpenApiOperationIdRegistrationTest` (1), `HttpMetricsTest` (1), `MasterDataRepositoryTest` (4), and `OrderProcessRepositoryTransactionTest` (21).

## HTTP coverage evidence

- [`MasterDataHttpIntegrationTest`](../../src/test/kotlin/com/literp/verticle/MasterDataHttpIntegrationTest.kt) exercises the 21 catalog/location operations through an ephemeral HTTP server and a router assembled in the test's `createRouter()` method. It also checks liveness, readiness, database health, metrics, request-ID propagation, error envelopes, and list-query validation.
- [`OrderProcessHttpIntegrationTest`](../../src/test/kotlin/com/literp/verticle/OrderProcessHttpIntegrationTest.kt) exercises the 10 order/stock operations through an ephemeral HTTP server and a router assembled in the test's `createRouter()` method. It also checks lifecycle guardrails and normalized success/error responses.
- `MasterDataHttpIntegrationTest.productionRouterExposesUtilityAndFailureContracts` deploys the actual `HttpServerVerticle` and verifies `/`, `/metrics`, `/health/live`, `/health/ready`, `/health/db`, unmatched-route `404`, the normalized global error envelope, and `X-Request-ID` propagation.
- The evidence therefore covers all 31 business operations through HTTP integration suites plus direct production-router utility and global-failure behavior.

## Error and logging evidence

### Confirmed

- Handler-level error responses contain `error`, `errorCode`, `status`, and `errorId`.
- Handler-level responses contain `X-Request-ID`, preserving a supplied value or generating one.
- HTTP test helpers assert the normalized error envelope, status, error code where requested, and absence of legacy error fields.
- The order-process suite exercises test-router internal-error and timeout failure responses.
- The production-router smoke test proves the global failure handler emits the normalized error envelope and request-correlation behavior.

### Not proven

- [`BaseRepository.kt`](../../src/main/kotlin/com/literp/repository/BaseRepository.kt) logs startup database connection activity only. The audit found no request-correlated repository logging, so the criterion requiring a trace through handler and repository work remains unproven.

## Bruno collection status

The collection contains all five production utility requests:

- [`Index.bru`](../../api_collections/Literp/Index.bru) — `/`
- [`Metrics.bru`](../../api_collections/Literp/Metrics.bru) — `/metrics`
- [`Live-Health.bru`](../../api_collections/Literp/Live-Health.bru) — `/health/live`
- [`Ready-Health.bru`](../../api_collections/Literp/Ready-Health.bru) — `/health/ready`
- [`DB-Health.bru`](../../api_collections/Literp/DB-Health.bru) — `/health/db`

The three added utility requests follow the existing collection variables, inherited-auth, timeout, encoding, and sequence conventions. Bruno linting remains deferred because no stable repository command is selected.

## CI and governance evidence

The [`Foundation Verification` workflow](../../.github/workflows/foundation-verification.yml) is configured for pull requests and pushes to `main` or `master`. Its displayed jobs are:

- `Build`
- `Test Baseline`
- `OpenAPI Verification`
- `Migration Verification`

The workflow proves that these jobs are defined and scheduled. It does **not** prove that GitHub branch protection or a ruleset requires all four checks before merge. No remote GitHub configuration was inspected or changed. Required-check enforcement therefore remains external and blocked.

## Documentation inventory and parity

The audited documentation now matches the current contract inventory: 31 business operations, five utility routes, and the two stock routes included in the order/stock domain.

| File | Current parity evidence |
|---|---|
| [`README.md`](../../README.md) | Lists 31 API endpoints, five utility routes, ten order/stock endpoints, and current/available stock support. |
| [`ENDPOINTS_OVERVIEW.md`](../ENDPOINTS_OVERVIEW.md) | Lists all business and utility routes and totals five utility/31 API endpoints. |
| [`API_IMPLEMENTATION.md`](../API_IMPLEMENTATION.md) | Documents all five utility routes, stock routes, the 31-endpoint total, and plain-JSON utility responses. |
| [`PROJECT_SUMMARY.md`](PROJECT_SUMMARY.md) | Describes the five utility capabilities and 31 API endpoints. |
| [`VERIFICATION_CHECKLIST.md`](../VERIFICATION_CHECKLIST.md) | Covers utility routes, stock routes, 31 API endpoints, and the synchronized Bruno inventory. |

The normalized error envelope remains documented in `API_IMPLEMENTATION.md`, and the production-router smoke test now exercises the global error response.

## Criterion status

These statuses are evidence states, and the corresponding Definition-of-Done checkboxes are reconciled in the phase plan.

| Phase 04 criterion | Evidence state | Reason |
|---|---|---|
| CI blocks broken build, broken tests, and invalid OpenAPI contracts | **Open — external evidence required** | Workflow jobs and local checks are present; merge-blocking rules are not locally verifiable. |
| Public API behavior is covered by HTTP integration tests | **Evidenced** | All 31 business operation IDs map to HTTP integration suites, and the production router has direct utility/global-failure smoke coverage. |
| Error responses are stable and documented | **Evidenced** | Handler/test-router contracts, production global-failure behavior, request IDs, and documentation are covered. |
| Logs are useful for tracing a single request through handler and repository work | **Open — implementation/evidence gap** | Handler request correlation exists; repository logs do not currently show request correlation. |
| Documentation updates accompany each API behavior change | **Evidenced for the audited change set** | The audited route/utility inventory and response documentation now match the current contract. |

## Remaining work

1. **Repository traceability:** Add or evidence request-correlated repository logging before closing the logging criterion.
2. **CI governance status:** Required-check enforcement remains externally blocked; no local repository change can prove merge blocking.
3. **Optional Bruno linting:** Select a stable repository-native lint command only if manual collection linting is required.

No credentials, tokens, passwords, or personal data are included in this evidence record.
