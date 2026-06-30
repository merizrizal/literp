## Architectural Design Specification: CI Artifact Synchronization and Operational Observability

**Source:** `docs/implementation-plan/04-quality-contracts-observability.md`, tasks `04.3` and `04.4`

**Goal:** Add CI checks for build/test/OpenAPI/artifact drift, then add request tracing, health semantics, and basic runtime metrics without breaking existing API contracts.

---

### I. Overview and Contract

This ADS covers:

- **04.3 CI And Artifact Synchronization**
  - Extend existing GitHub Actions workflow.
  - Add OpenAPI validation.
  - Detect/prevent YAML ↔ JSON OpenAPI drift.
  - Keep version references synchronized with `build.gradle.kts`.
  - Add Bruno collection validation only if practical.

- **04.4 Logging, Health, And Metrics**
  - Standardize request log fields.
  - Propagate incoming `X-Request-ID` to logs and response headers.
  - Add liveness/readiness semantics beyond `/health/db`.
  - Add basic in-process metrics for request count, latency, errors, and DB failures.

**Concrete existing contracts:**

- CI workflow exists at `.github/workflows/foundation-verification.yml`.
- Build/version source exists in `build.gradle.kts`.
- OpenAPI YAML/JSON assets exist under `api_collections/open_api_spec/`.
- Bruno collection exists under `api_collections/Literp/`.
- Current health endpoint: `GET /health/db`.
- Current HTTP server/router: `src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt`.
- Current handler logging/error response helpers: `src/main/kotlin/com/literp/verticle/handler/BaseHandler.kt`.

**Conceptual new contracts:**

- `scripts/verify_openapi_assets.py`
  - validates OpenAPI files
  - enforces YAML → JSON sync
  - enforces `info.version` matches `build.gradle.kts`
- `GET /health/live`
- `GET /health/ready`
- `GET /metrics`
- response header `X-Request-ID`

---

### II. Observed Evidence and Assumptions

Observed:

- `04.3` requires CI, OpenAPI validation, Bruno linting if practical, generated JSON source-of-truth, and version sync.
- `04.4` requires structured logs, `X-Request-ID`, health semantics, and metrics.
- `.github/workflows/foundation-verification.yml` already has:
  - Build job using Java 25 and `./gradlew build`
  - Migration verification job using Python 3.13 and PostgreSQL 18
- `docs/CI_VERIFICATION.md` documents build, test baseline, and migration verification.
- `build.gradle.kts` has `version = "0.0.1"`.
- OpenAPI files currently contain `info.version: 1.0.0`.
- `HttpServerVerticle.getIndex()` currently returns `"version": "1.0.0"`.
- `BaseHandler` logs `requestId` from `X-Request-ID`, but does not propagate it to response headers.
- `/health/db` exists; `/health/live`, `/health/ready`, and `/metrics` do not appear to exist.
- No `package.json` or pinned Node tooling was observed, so Bruno CLI linting must be confirmed before being made mandatory.

Assumptions:

- `build.gradle.kts` should become the single source of truth for application version.
- YAML OpenAPI files should be the source of truth; JSON files should be generated/verified from YAML.
- Metrics can start as dependency-free in-memory JSON metrics, not Prometheus, unless the project chooses a metrics library later.
- Request ID propagation should be via `X-Request-ID` response header to avoid changing stable JSON response envelopes.

---

### III. Required Technical Dependencies and Imports

Likely dependencies:

- Existing:
  - Kotlin/JVM
  - Vert.x Web/OpenAPI
  - JUnit 5
  - GitHub Actions
  - Python scripts under `scripts/`
- Proposed:
  - `python/requirements-dev.txt` with OpenAPI verification dependencies, e.g. YAML/OpenAPI validation libraries.
  - `scripts/verify_openapi_assets.py`
  - Optional Bruno check only after confirming a stable CLI/lint command.

Kotlin imports likely needed conceptually:

- `java.util.UUID`
- `java.util.concurrent.atomic.AtomicLong`
- `io.vertx.core.json.JsonObject`
- `io.vertx.rxjava3.ext.web.RoutingContext`

Do not finalize imports until Chunk 0 confirms implementation shape.

---

### IV. Step-by-Step Procedure / Execution Flow

1. Confirm current CI workflow behavior.
2. Add explicit CI job or step for test baseline.
3. Add OpenAPI asset verification script:
   - parse `build.gradle.kts` version
   - load OpenAPI YAML files
   - validate OpenAPI structure
   - compare generated normalized JSON to tracked JSON
   - fail if JSON drift exists
4. Wire OpenAPI verification into CI.
5. Document YAML as source of truth for generated OpenAPI JSON.
6. Synchronize version references with `build.gradle.kts`.
7. Add request ID resolver:
   - use incoming `X-Request-ID` when present
   - generate UUID when absent
   - store on routing context
   - return `X-Request-ID` response header
8. Add health endpoints:
   - `/health/live`
   - `/health/ready`
   - keep `/health/db`
9. Add metrics:
   - request count
   - latency
   - error count
   - database failure count
10. Add targeted tests and update docs.

---

### V. Failure Modes and Resilience

| Stage | Failure Mode | Agent/System Action | Next State/Error Report |
|---|---|---|---|
| CI build | Gradle build fails | CI blocks merge | Failed Build job |
| CI test | DB-backed tests skipped unexpectedly | Prefer CI PostgreSQL service for integration baseline | Failed or incomplete Test Baseline |
| OpenAPI validation | YAML is invalid | Verification script exits non-zero | CI reports invalid OpenAPI |
| OpenAPI sync | JSON differs from YAML | Script prints affected file and regeneration instruction | CI reports artifact drift |
| Version sync | OpenAPI or runtime version differs from `build.gradle.kts` | Script exits non-zero | CI reports version drift |
| Bruno lint | No stable Bruno lint tool exists | Do not make mandatory; document skipped/optional | No false CI blocker |
| Request ID | Header absent | Generate UUID | Response includes generated `X-Request-ID` |
| Request ID | Header malformed/too long | Proposed: reject or truncate after policy decision | `400 VALIDATION_ERROR` proposed, or generated replacement |
| Health readiness | DB unavailable | `/health/ready` returns `503`; `/health/live` remains `200` | Operators can distinguish process vs dependency failure |
| Metrics | Metrics counter update fails | Keep request path successful; metrics must not break API | Log warning if needed |
| DB health | DB query timeout | Return `503` with `DB_TIMEOUT` | Existing behavior preserved/extended |

---

### VI. Security, Integrity, Idempotency, and Cleanup

- Do not log request bodies or secrets.
- Treat `X-Request-ID` as untrusted input.
- Prefer a safe character/length policy for request IDs.
- Metrics must avoid high-cardinality labels such as raw IDs or full dynamic paths.
- OpenAPI JSON should be generated deterministically.
- CI should fail closed on invalid contracts.
- Do not remove `/health/db`; keep it for backward compatibility.
- Metrics endpoint should expose operational counters only, not environment variables or DB credentials.

---

### VII. Validation Strategy

Local validation commands should be chunk-specific.

Suggested final validation:

```bash
rtk ./gradlew test --tests com.literp.contract.OpenApiOperationIdRegistrationTest
rtk ./gradlew test --tests com.literp.verticle.MasterDataHttpIntegrationTest --tests com.literp.verticle.OrderProcessHttpIntegrationTest
rtk ./gradlew build
rtk python scripts/verify_openapi_assets.py
rtk git diff -- .github/workflows/foundation-verification.yml docs/CI_VERIFICATION.md docs/implementation-plan/04-quality-contracts-observability.md scripts src api_collections
```

CI `run:` commands should remain normal CI commands, not RTK-prefixed, unless CI has `rtk`.

---

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full feature in one pass.

#### Chunk 0: Discovery and Integration Confirmation

- **Goal:** Confirm exact CI, OpenAPI, Bruno, version, health, logging, and metrics integration points.
- **Files to read:**
  - `.github/workflows/foundation-verification.yml`
  - `build.gradle.kts`
  - `docs/CI_VERIFICATION.md`
  - `api_collections/open_api_spec/README.md`
  - `src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt`
  - `src/main/kotlin/com/literp/verticle/handler/BaseHandler.kt`
  - relevant HTTP integration tests
- **Commands:**
  - `rtk grep -Rni "health/db\\|X-Request-ID\\|metrics\\|version" src docs api_collections build.gradle.kts`
  - `rtk find .github api_collections scripts src/test -maxdepth 4 -type f`
- **Evidence to confirm:**
  - version drift locations
  - CI jobs needing changes
  - whether Bruno linting is practical
  - current test harness for utility endpoints
- **Stop condition:** Evidence summarized; no edits.

#### Chunk 1: CI Test Baseline Slice

- **Goal:** Make CI explicitly run test baseline, not only build.
- **Files to change:**
  - `.github/workflows/foundation-verification.yml`
  - `docs/CI_VERIFICATION.md`
- **Symbols to add/change:** CI job/step names only.
- **Implementation shape:**
  - Add explicit `./gradlew test` step/job.
  - Prefer PostgreSQL service if integration tests should run instead of skip.
  - Document local reproduction.
- **Validation:**
  - `rtk ./gradlew test`
  - `rtk git diff -- .github/workflows/foundation-verification.yml docs/CI_VERIFICATION.md`
- **Stop condition:** CI file is syntactically valid YAML and local tests run.

#### Chunk 2: OpenAPI Asset Verifier Contract

- **Goal:** Add compile-safe/script-safe verification entry point.
- **Files to change:**
  - `scripts/verify_openapi_assets.py`
  - optionally `python/requirements-dev.txt`
- **Symbols to add/change:**
  - **Function Signature Contract (Conceptual):** `main() -> int`
  - **Function Signature Contract (Conceptual):** `resolve_root_dir() -> Path`
  - **Function Signature Contract (Conceptual):** `read_gradle_version(root_dir: Path) -> str`
- **Implementation shape:**
  - Add script with argument-free execution.
  - Initial version may validate file existence and version extraction only.
  - If dependency is missing, print clear install instruction and exit non-zero.
- **Validation:**
  - `rtk python scripts/verify_openapi_assets.py`
- **Stop condition:** Script runs deterministically and reports current drift without modifying files.

#### Chunk 3: OpenAPI YAML/JSON Drift and Version Sync Logic

- **Goal:** Enforce YAML as source of truth and `build.gradle.kts` as version source.
- **Files to change:**
  - `scripts/verify_openapi_assets.py`
  - `api_collections/open_api_spec/*.yaml`
  - `api_collections/open_api_spec/*.json`
  - possibly `src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt`
- **Symbols to add/change:**
  - **Function Signature Contract (Conceptual):** `verify_openapi_pair(yaml_path: Path, json_path: Path, expected_version: str) -> None`
- **Implementation shape:**
  - Validate each YAML contract.
  - Generate normalized JSON from YAML in memory.
  - Compare to checked-in JSON.
  - Align `info.version` and runtime root version to `build.gradle.kts`.
- **Validation:**
  - `rtk python scripts/verify_openapi_assets.py`
  - `rtk ./gradlew test --tests com.literp.contract.OpenApiOperationIdRegistrationTest`
- **Stop condition:** Script passes and operation ID contract still passes.

#### Chunk 4: Wire OpenAPI Verification Into CI

- **Goal:** CI blocks invalid OpenAPI and JSON drift.
- **Files to change:**
  - `.github/workflows/foundation-verification.yml`
  - `docs/CI_VERIFICATION.md`
- **Symbols to add/change:** CI step/job names only.
- **Implementation shape:**
  - Install dev verification dependencies.
  - Run `python scripts/verify_openapi_assets.py`.
  - Document YAML source-of-truth and regeneration workflow.
- **Validation:**
  - `rtk python scripts/verify_openapi_assets.py`
  - `rtk git diff -- .github/workflows/foundation-verification.yml docs/CI_VERIFICATION.md`
- **Stop condition:** CI workflow contains OpenAPI verification and docs explain it.

#### Chunk 5: Request ID Propagation Slice

- **Goal:** Propagate `X-Request-ID` through success/error responses and logs.
- **Files to change:**
  - `src/main/kotlin/com/literp/verticle/handler/BaseHandler.kt`
  - one targeted HTTP integration test file
- **Symbols to add/change:**
  - **Function Signature Contract (Conceptual):** `resolveRequestId(context: RoutingContext): String`
  - **Function Signature Contract (Conceptual):** `putResponse(...): Unit` adds `X-Request-ID` header
- **Implementation shape:**
  - Use incoming header if present.
  - Generate UUID if missing.
  - Set response header on every handler response.
  - Add test for one success and one error response.
- **Validation:**
  - `rtk ./gradlew test --tests com.literp.verticle.MasterDataHttpIntegrationTest`
- **Stop condition:** Existing response envelope tests still pass and response header is asserted.

#### Chunk 6: Health Endpoint Semantics Slice

- **Goal:** Add liveness/readiness while preserving `/health/db`.
- **Files to change:**
  - `src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt`
  - targeted HTTP integration test file
- **Symbols to add/change:**
  - **Function Signature Contract (Conceptual):** `getLiveness(context: RoutingContext): Unit`
  - **Function Signature Contract (Conceptual):** `getReadiness(context: RoutingContext): Unit`
- **Implementation shape:**
  - `/health/live`: process/router alive, no DB dependency.
  - `/health/ready`: includes DB readiness.
  - `/health/db`: preserve existing behavior.
- **Validation:**
  - `rtk ./gradlew test --tests com.literp.verticle.MasterDataHttpIntegrationTest`
- **Stop condition:** health tests pass and `/health/db` remains backward compatible.

#### Chunk 7: Basic Metrics Slice

- **Goal:** Expose request/error/latency/DB failure metrics.
- **Files to change:**
  - `src/main/kotlin/com/literp/observability/HttpMetrics.kt`
  - `src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt`
- **Symbols to add/change:**
  - **Function Signature Contract (Conceptual):** `class HttpMetrics`
  - **Function Signature Contract (Conceptual):** `recordRequest(statusCode: Int, durationNanos: Long): Unit`
  - **Function Signature Contract (Conceptual):** `recordDatabaseFailure(): Unit`
  - **Function Signature Contract (Conceptual):** `snapshot(): JsonObject`
- **Implementation shape:**
  - Dependency-free atomic counters.
  - Router-level timing handler.
  - `/metrics` returns JSON snapshot.
  - DB health failure increments DB failure counter.
- **Validation:**
  - `rtk ./gradlew test --tests com.literp.verticle.MasterDataHttpIntegrationTest`
  - `rtk ./gradlew build`
- **Stop condition:** `/metrics` returns stable JSON and metrics failures cannot break request handling.

#### Chunk 8: Documentation and Final Verification

- **Goal:** Mark tasks complete and document operator usage.
- **Files to change:**
  - `docs/CI_VERIFICATION.md`
  - `docs/API_TESTING_GUIDE.md`
  - `docs/implementation-plan/04-quality-contracts-observability.md`
- **Symbols to add/change:** documentation only.
- **Implementation shape:**
  - Document CI checks.
  - Document OpenAPI source-of-truth.
  - Document `X-Request-ID`, health endpoints, and metrics endpoint.
  - Mark 04.3/04.4 checkboxes only after validation passes.
- **Validation:**
  - `rtk ./gradlew build`
  - `rtk python scripts/verify_openapi_assets.py`
  - `rtk git diff`
- **Stop condition:** Final diff reviewed; Phase 04.3/04.4 evidence is documented.

---

### IX. Handoff to `chunked-implementation`

Recommended agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, safe-python-edit, and post-edit-discipline if available.

Task:
Implement Phase 04 tasks 3 and 4 from docs/implementation-plan/04-quality-contracts-observability.md:
CI/artifact synchronization plus logging, health, and metrics.

Mode:
Execute Chunk 0 only. Do not edit files. Confirm repository evidence and stop.
```

After Chunk 0 is accepted:

```text
Use the chunked-implementation skill.
Execute Chunk 1 only.
Do not continue to Chunk 2.
After editing, run targeted validation and show git diff.
```

Continue one chunk at a time.

---

### X. Conclusion and Next Steps

Phase 04.3 should be implemented first because CI and artifact checks protect the observability changes that follow. The safest order is:

1. CI/test baseline
2. OpenAPI/version/artifact verification
3. request ID propagation
4. health semantics
5. metrics
6. documentation and final verification

This ADS does not implement the feature; it defines the compile-safe implementation ladder for the next agent.
