## Architectural Design Specification: Work Order And Production Run API

**Source:** [Phase 05, Task 05.5](../05-pos-manufacturing-expansion.md#055-work-order-and-production-run-api).

**Status:** Proposed; design only. API decisions below require approval before implementation. This document does not authorize implementation, migrations, deployment, or continuation of unfinished Task 05.3/05.4 work.

**Evidence revision:** `1ed2617`, branch `phase-05-task-3`; clean worktree observed before creating this document. No fetch performed.

**Goal:** Let authorized users plan, start, complete, and cancel location-scoped work orders, and start/complete production runs with trustworthy operator attribution and explicit output, scrap, and yield semantics.

---

### I. Overview and Contract

Task 05.5 owns production execution records, not stock effects. Task 05.4 must supply implemented, accepted immutable executable BOM and unit-interpretation contracts; its ADS alone is not that dependency. Task 05.6 owns material consumption, finished-goods movements, made-to-stock, and later made-to-order integration. Completing a run here must not imply that stock was received or consumed.

**Aligned cross-task proposal:** 05.5 initially supports execution-only API work orders. If the separate-posting design in [05.6](phase-05-task-6.md) is approved and enabled, new policy-1 orders expose independent run-completion and inventory-posting states and cannot close until every completed run is POSTED. Existing policy-null execution-only orders retain their original closure semantics; legacy records remain read-only. This is a proposed compatibility contract, not approval to enable posting. Atomic complete-and-post would require revising both ADSs before implementation.

#### Proposed operation matrix

Paths are relative to `/api/v1`. Add a proposed `manufacturing-work-orders.yaml`/`.json` bundle. All operations require bearer authentication, configured-organization membership, explicit capability, and location scope.

| Method/path | operationId (proposed) | Capability (proposed) | Success |
|---|---|---|---|
| GET `/manufacturing/work-orders` | `listWorkOrders` | `manufacturing.work-order.read` | 200 paginated scoped list |
| POST `/manufacturing/work-orders` | `planWorkOrder` | `manufacturing.work-order.plan` | 201 new PLANNED order |
| GET `/manufacturing/work-orders/{workOrderId}` | `getWorkOrder` | `manufacturing.work-order.read` | 200 header and yield summary |
| POST `/manufacturing/work-orders/{workOrderId}/start` | `startWorkOrder` | `manufacturing.work-order.start` | 200 IN_PROGRESS order |
| POST `/manufacturing/work-orders/{workOrderId}/complete` | `completeWorkOrder` | `manufacturing.work-order.complete` | 200 COMPLETED order |
| POST `/manufacturing/work-orders/{workOrderId}/cancel` | `cancelWorkOrder` | `manufacturing.work-order.cancel` | 200 CANCELLED order |
| GET `/manufacturing/work-orders/{workOrderId}/runs` | `listProductionRuns` | `manufacturing.production-run.read` | 200 paginated runs |
| GET `/manufacturing/work-orders/{workOrderId}/runs/{runId}` | `getProductionRun` | `manufacturing.production-run.read` | 200 one run |
| POST `/manufacturing/work-orders/{workOrderId}/runs` | `startProductionRun` | `manufacturing.production-run.start` | 201 IN_PROGRESS run |
| POST `/manufacturing/work-orders/{workOrderId}/runs/{runId}/complete` | `completeProductionRun` | `manufacturing.production-run.complete` | 200 COMPLETED run |

Read endpoints are a supporting scope addition: clients need IDs/current state and a recovery path after ambiguous responses. No generic update/delete or client-selected status endpoint is proposed. Use existing `{data: ...}`, list `{data: [...], pagination: ...}`, error envelope, and `X-Request-ID` conventions.

#### Proposed input/output contracts

- **Plan:** required `bomId`, `locationId`, `plannedQuantity`, `plannedStart`, `plannedEnd`; optional bounded `notes` (proposed maximum 2,000 characters). Derive finished `productId` from the selected BOM rather than accept contradictory product identity. Server generates UUID and unique work-order number from a database sequence, e.g. `WO-<sequence>`; gaps are allowed and numbering is not a date/reset sequence.
- **Start work order / complete work order / start run:** no business payload. Unknown fields are rejected; omitted body or an empty object has one canonical fingerprint.
- **Cancel:** required nonblank `reason`, proposed maximum 500 characters, stored in the event record rather than overwriting planning notes.
- **Complete run:** required `outputQuantity` (good finished output) and `scrapQuantity` (rejected finished output). No client-supplied total, yield, actor, operator, date, status, BOM, material usage, timestamps, or inventory fields.
- **Numbers:** exact decimal JSON values; maximum three fractional digits and `NUMERIC(12,3)` range, through `999999999.999`. Planned quantity must be positive; output and scrap nonnegative with a strictly positive sum. Reject nonfinite, overflow and excess precision rather than silently round. Use decimal-safe parsing/BigDecimal and proxy-compatible decimal strings internally, not floating-point arithmetic.
- **Times:** planning timestamps are RFC 3339 with explicit offset, normalized to UTC; `plannedEnd >= plannedStart`. Past plans are allowed. Actual start/end/run date are server-derived UTC values. Existing timestamp-without-time-zone columns are interpreted as UTC, subject to legacy confirmation in Chunk 0.
- **Read work order:** IDs, number, pinned BOM/version/product/base-UOM snapshot, location, quantities, lifecycle, planned/actual timestamps, notes, nullable legacy attribution, `recordOrigin`, and aggregate output/scrap/yield summary. Do not embed an unbounded runs array.
- **Read run:** `runId`, parent ID, status, run date, operator subject/issuer, start/completion attribution and times, good output, scrap, and yield. New IN_PROGRESS rows store zero output/scrap as required by existing NOT NULL columns; return null yield until completion. When 05.6 is enabled, work-order/run reads add its policy/posting state and compact summary. COMPLETED never means POSTED; excluded history is HISTORICAL_UNMANAGED, not an automatic UNPOSTED backlog.
- **Lists:** `page` default 0, `size` default 20/max 100; work-order filters `locationId`, `productId`, `status`; run filter `status`. Allowlist sorting and add ID tie-breakers. Count and row queries use identical location/filter predicates.

#### Proposed lifecycle and yield rules

| Entity/current state | Command | Preconditions | Result |
|---|---|---|---|
| New work order | Plan | ACTIVE BOM, active STOCK finished product, active PRODUCTION location | PLANNED; pin immutable recipe identity and base-UOM snapshot |
| PLANNED work order | Start | Pinned BOM intact; product/location still eligible | IN_PROGRESS; actual start set once |
| PLANNED work order | Cancel | No runs | CANCELLED; end time/event recorded, no fabricated start |
| IN_PROGRESS work order | Cancel | No runs, including no completed runs | CANCELLED; preserve start and record end/event |
| IN_PROGRESS work order | Start run | No open run; product/location still eligible | One IN_PROGRESS run; parent unchanged |
| IN_PROGRESS run | Complete run | Parent IN_PROGRESS; positive total attempted output | COMPLETED; immutable good/scrap results |
| IN_PROGRESS work order | Complete | At least one completed run and no open run; under 05.6 stock policy, all completed runs must also be POSTED | COMPLETED; actual quantity = sum of completed good output |
| COMPLETED/CANCELLED work order | Any new transition | Terminal state | Conflict; durable replay and identical no-op repeats described below remain allowed |

- At most one IN_PROGRESS run per work order; multiple sequential completed runs are allowed.
- Repeating work-order start with a new key while already IN_PROGRESS returns current state without another start event. Repeating cancel on CANCELLED or complete on COMPLETED similarly returns current state only when its semantic input matches the recorded command; a changed cancellation reason conflicts. A start after terminal closure conflicts.
- Repeating completed-run completion with a new key and identical quantities returns its immutable result; changed quantities conflict. A new start-run key after a run completes means a new batch, not a replay.
- Partial and over-plan output are permitted, including all-scrap completion. Planning is a target, not a stock reservation or production cap. This avoids misclassifying rejects as good output or forcing falsified quantities to close an order. Report variance explicitly.
- `goodOutput = SUM(completed output_quantity)`; `scrapOutput = SUM(completed scrap_quantity)`; `attemptedOutput = goodOutput + scrapOutput`; `yieldPercentage = 100 * goodOutput / attemptedOutput`, null when the denominator is zero. Report yield to two decimal places, HALF_UP. Aggregate yield is weighted from totals, not an average of run percentages.
- `planVariance = goodOutput - plannedQuantity`. This is distinct from quality yield. Actual quantity stays null before terminal closure; on cancellation with no runs it is zero. Reject aggregate good output beyond the work-order numeric column's capacity before committing a run.
- Actual scrap is in the finished product's base UOM. It is not a component quantity or the BOM's planned component `scrap_percentage`. Do not infer actual material consumption from either.
- BOM deprecation prevents new plans, but does not invalidate previously pinned orders. No automatic repinning to the latest active version. Nested BOM components remain component references: recursive explosion and sub-work-order orchestration are deferred to a separately approved inventory design.
- Proposed run start/completion eligibility is HUMAN plus `operator=true` and exact capability. Other work-order/read operations permit authorized HUMAN or SERVICE principals. The completing operator need not equal the starter; retain both identities for handover. No arbitrary operator impersonation field is accepted.
- Cancellation after any run exists, run abortion, correction/reversal, reopening, substitutions, and schedule edits are intentionally unsupported. In particular, an abandoned open run requires a separately approved recovery procedure; do not fabricate output to close it.

**Function Signature Contract (Concrete):**

- `BaseRepository.inTransaction(work: (SqlConnection) -> Single<T>): Single<T>` owns SQL transaction scope.
- `PosOperationsService` demonstrates `Future<JsonObject>`, `JsonArray` authorized-location arguments, scalar actor/organization inputs, and static `createProxy(Vertx)`/`register(Vertx, service)`.
- `PosScopeHandler.authorize(operationId: String): Handler<RoutingContext>` validates policy context and passes trusted grants; resource SQL enforces the actual location predicate.
- `BaseHandler.putSuccessResponse`, `putSuccessEnvelopeResponse`, `parseListQuery`, and `putMappedErrorResponse` are existing handler helpers. Manufacturing errors require explicit safe mappings; current generic error-message forwarding is not a safe new-domain contract.
- `ExplicitSecurityPolicy.businessOperationIds` participates in operation/route parity checks.

**Function Signature Contract (Conceptual):**

- Proposed `ManufacturingWorkOrderRepository` exposes methods matching the ten operation IDs, returning `Single<JsonObject>`; read inputs include IDs, bounded list arguments, and trusted authorized-location sets. Commands additionally receive normalized payload, idempotency key, and trusted actor subject/issuer/organization.
- Proposed `ManufacturingWorkOrderService` mirrors those methods with `Future<JsonObject>` and codegen-compatible `JsonObject`/`JsonArray`/scalars; no custom Kotlin data classes cross the event bus.
- Proposed `ManufacturingWorkOrderHandler` has ten routing-context methods; `ManufacturingScopeHandler.authorize(operationId)` verifies manufacturing-specific scope decisions and forwards immutable grant sets. Proposed resource scopes cover requested location, allowed-location list, and work-order location; nested runs always scope through their parent.
- Proposed repository helpers claim/store durable command results, select a scoped parent for update, append lifecycle events, and calculate decimal summaries. Exact signatures are confirmed in Chunk 0, not invented as existing APIs.

Stub-first behavior: publish authenticated/capability-checked/scope-checked handler methods returning explicit `501 NOT_IMPLEMENTED`, without database mutation or command-key reservation. Later proxy wiring creates called methods and explicit failure stubs in the same chunk; no success-shaped empty object may represent an unimplemented transition.

### II. Observed Evidence and Assumptions

| Exact evidence inspected | Implication |
|---|---|
| Phase 05 plan, lines 181–218 | Task 05.5 owns lifecycle/run/yield/Bruno; Task 05.6 owns stock movements |
| `docs/knowledge/MODEL_DESIGN.md`, manufacturing section, lines 203–263 | Work orders reference product/BOM/location; runs carry operator, output, scrap and status |
| `python/database/migration/alembic/versions/314b57a8dd0f_00_initial_migration.py`, lines 41–70, 108–120, 245–300 | Existing enums and tables; PRODUCTION location type; decimal precision; no open-run uniqueness, command ledger, actor history or explicit run start/end fields |
| Initial migration versus model document | `material_consumed` is actually SQL JSON, despite the model describing JSONB; do not silently change or reinterpret it |
| Seed revision `acf82479ef78_99_populate_seed_data.py`, lines 792–921 | Completed/planned orders and completed runs exist; operator labels are not authenticated subjects; output plus scrap may exceed plan |
| `docs/implementation-plan/ads/phase-05-task-4.md`, Sections I–IV | Proposed BOM pinning/immutability and domain service placement; design evidence, not an implemented dependency |
| `docs/knowledge/PROJECT_STRUCTURE_DECISION.md`, accepted placement/API policy | Retain repository/handler layers, manufacturing Java service group, existing asset roots |
| `HttpServerVerticle.kt`, lines 168–255 | Four current bundles, explicit loaders/registration/subrouters; new bundle must be wired deliberately |
| `SecurityPolicy.kt`, principal/scope definitions and policy map; `PosScopeHandler.kt` | Explicit organization/capability/eligibility policy; trusted grants; no manufacturing policies currently exist |
| `BaseRepository.kt`; `PosOperationsRepository.kt`, lines 811–929 | Rx SQL transactions and domain-specific durable fingerprint/replay pattern; do not reuse a POS ledger for manufacturing |
| `PosOperationsService.java` and POS `package-info.java` | Java proxy annotations, Future/JSON/scalar signatures and ModuleGen convention |
| `BaseHandler.kt`, lines 145–229; `ErrorCodes.kt` | Existing errors/list bounds; typed manufacturing exceptions and proxy error transport need deliberate mapping |
| `scripts/verify_openapi_assets.py`, bundle inventory; operation registration test | Explicit asset/policy/route inventories must expand together |
| `build.gradle.kts`; `TestDatabase.kt` | Java 25, Kotlin 2.4.20, Vert.x 5.2.0, JUnit; unavailable database can skip tests; no formatter task declared in this build file |
| Targeted source/asset/migration searches | Manufacturing tables/designs exist, but no work-order/run service, handler, repository or OpenAPI bundle was found |

#### Assumptions requiring approval in Chunk 0

1. Approve all ten operations, capabilities, location restrictions, human-operator eligibility, and operator handover rules.
2. Approve one open run, no cancellation after runs, partial/overproduction/all-scrap completion, yield formula, repeat semantics, numeric/time bounds, and explicit lack of a run-abort endpoint.
3. Approve sequence numbering, required command keys on all six write operations, durable actor/event storage, and the legacy policy below.
4. Confirm Task 05.4's implementation provides immutable BOMs and compatible lock order. Do not implement planning against a merely proposed guarantee. Task 05.3 remains unfinished at the observed revision; retain existing task sequencing unless explicitly authorized otherwise.
5. Confirm no cross-organization shared-table tenancy is expected: current tables have no organization column and rely on the configured deployment organization. Shared-tenant support requires a separate design.
6. Confirm UTC interpretation of historical timestamps and whether PRODUCTION-only locations meet operational requirements.

### III. Required Technical Dependencies and Imports

Reuse PostgreSQL transactions/row locks/check constraints/partial indexes/sequences, Alembic, Vert.x OpenAPI and Java service proxy codegen, RxJava Single, SQL Pool/SqlConnection/Tuple, JsonObject/JsonArray, Java BigDecimal/RoundingMode/UUID/time/digest APIs, JUnit and existing HTTP/security fixtures. No new runtime library, external MES, broker, provider or deployment dependency is proposed.

Proposed new files under confirmed placement roots:

- `src/main/kotlin/com/literp/repository/ManufacturingWorkOrderRepository.kt`
- `src/main/kotlin/com/literp/verticle/handler/ManufacturingWorkOrderHandler.kt`
- `src/main/kotlin/com/literp/verticle/handler/ManufacturingScopeHandler.kt`
- `src/main/java/com/literp/service/manufacturing/ManufacturingWorkOrderService.java`
- `src/main/kotlin/com/literp/service/manufacturing/impl/ManufacturingWorkOrderServiceImpl.kt`
- Manufacturing `package-info.java` only if Task 05.4 has not already supplied the domain ModuleGen; never duplicate its module declaration.
- Proposed repository, HTTP and contract tests named `ManufacturingWorkOrderRepositoryTest`, `ManufacturingWorkOrderHttpIntegrationTest`, and `ManufacturingWorkOrderContractTest` under their corresponding existing test roots.
- `api_collections/open_api_spec/manufacturing-work-orders.yaml`, synchronized `.json`, and `manufacturing-work-orders-README.md`; ten Bruno requests under `api_collections/Literp`.

#### Proposed additive persistence contract

Create a revision after the then-current single Alembic head, not necessarily the presently observed `c7a8e2f4d6b1`. Never edit initial/seed migrations.

- Work-order checks: positive plan, nonnegative nullable actual, valid planned/actual time order. Run checks: nonnegative decimals; API-managed completed runs require positive total. Partial unique index on `production_run(work_order_id)` where status is IN_PROGRESS; index run lookup by parent/date/ID.
- Add unique sequence-backed new-order numbering without modifying existing business numbers. Preflight collisions against the chosen prefix/sequence initialization, rather than assume existing IDs follow one format.
- Add nullable legacy-compatible trusted actor subject/issuer fields, run start/completion timestamps, and a work-order execution snapshot containing pinned BOM ID/version, finished product/base UOM and direct component identities/base UOMs/recipe quantities/scrap allowances. Snapshot is not an exploded requirement or consumption record. Validate its schema in the repository.
- Add proposed `api_contract_version` marker to work orders and runs, null on untouched legacy rows and set to 1 on new API records. The run-local marker permits conditional database checks without cross-table CHECK expressions; repository writes enforce parent/run marker consistency. Public `recordOrigin` is derived as `LEGACY` or `API`. Do not invent historical actors or actual timestamps during migration.
- Add append-only manufacturing execution events with target IDs, transition, server timestamp, subject/issuer/organization, command reference and safe cancellation reason. Use database append-only protection following established migration conventions; no public event mutation route.
- Add a manufacturing execution command ledger with unique organization/issuer/subject/operation/target/key scope, canonical request fingerprint, persisted response status/body and completion timestamps. If Task 05.4 already introduced a compatible manufacturing ledger, reuse it only after verifying its identity/locking/schema contract; otherwise keep an execution-specific ledger, not a second contradictory definition of the same table.
- Preserve `operator_id` legacy labels. Store authenticated subjects separately without truncating them to the existing 36-character column; new-run attribution must never depend on that legacy label.
- Keep legacy `material_consumed` unchanged and null for new 05.5 runs. It does not prove inventory posting. Completed result quantities, recipe snapshot and execution attribution remain immutable; do not implement a whole-row guard that accidentally prevents the proposed 05.6 posting-owned enrichment. Under that separately approved task only, allow one transactional null-to-versioned `material_consumed` projection linked to immutable posting lines, without changing execution facts. No movement, reservation, order linkage or stock-rollup write belongs in this migration/API.

**Legacy safety proposal:** expose historical work orders/runs read-only with nullable provenance. Reject mutations of `LEGACY` orders with 409 and a documented remediation requirement; no silent adoption. Validate all rows before adding global checks/indexes, report offending IDs/counts without configuration, and stop on inconsistent history. Adoption of existing planned/in-progress orders requires a separately approved audited procedure. Existing completed seed records, material JSON, references and movement history must survive unchanged.

### IV. Step-by-Step Procedure / Execution Flow

#### Shared authorization and transaction boundary

1. Authenticate and enforce organization, exact operation policy and principal eligibility. Start/complete-run also require verified human operator status. Do not weaken global OPERATOR_OR_SERVICE semantics to achieve this: add a manufacturing-specific eligibility rule or explicit tested handler check.
2. Validate request shape/UUIDs/bounds and requested location against trusted grants. Empty grants or explicitly forbidden location filters produce 403. Parameterize SQL; never trust client actor or allowed-location inputs.
3. Resource queries join/filter the work-order location within SQL, including nested `(workOrderId, runId)` matching. Missing and out-of-scope resources both return 404 (proposed anti-enumeration policy). Reads of inactive locations remain possible when authorized.
4. Normalize decimals, timestamps and empty bodies for a stable fingerprint; require a bounded nonblank `Idempotency-Key` (proposed 1–128 characters). Authorize current resource scope before looking up any saved result.
5. Mutations run through `inTransaction`. Existing-resource operations lock the scoped work-order row first, then claim the command key, then lock the target run if present. Every run writer locks its parent, so aggregate checks and closure serialize.
6. Planning has no parent lock: validate requested grants, claim a creation key scoped to the collection (not a generated UUID), then validate/lock definition dependencies in the exact order agreed with Task 05.4. Replays do not reapply mutable ACTIVE-state preconditions; they do recheck current authorization.
7. Write entity mutation, event, and complete command result in one transaction. Commit before responding. On failure roll back all three; never retain a committed pending command with no outcome. Scope-check before replay, then return saved success/status for identical key/fingerprint even if later lifecycle state changed.

#### Plan and start

- Lock/recheck selected BOM and applicable product/location rows against concurrent deprecation/deactivation. Require ACTIVE BOM with valid immutable lines, STOCK product and active PRODUCTION location; confirm finished-product/BOM consistency.
- Pin version and UOM/recipe snapshot; create PLANNED with null actual quantity/times and server-generated ID/number. Product and location become immutable on this order.
- Start locks/rechecks the parent and reference eligibility, permits a pinned now-DEPRECATED BOM, rejects missing/corrupt/DRAFT definitions and legacy records, sets actual start once, appends event.
- At run start, require parent IN_PROGRESS and no open run, revalidate operational product/location eligibility and snapshot availability, record human starter identity and UTC start/run date, insert zero quantities. No inventory availability query or deduction occurs.

#### Complete run and summarize

- Lock scoped parent, claim command, then lock run. After authorization, handle saved replay or identical completed-result no-op before requiring an IN_PROGRESS parent/run; this permits safe completion retries after parent closure. For a new transition enforce parent ownership, current state, exact decimals and total > 0. Allow recording/closing already-started work even if product/location was later deactivated; otherwise deactivation could strand history. New starts remain blocked.
- Calculate prospective completed good total under the parent lock; reject overflow before writing. Persist immutable output/scrap, completion actor/time, event and command response atomically.
- Scoped read summaries aggregate completed runs only in one consistent SQL statement/snapshot. A live unfinished run contributes neither zero-quality output nor an undefined percentage disguised as zero.
- Work-order completion separately requires at least one completed run and none open, recomputes totals under the same lock, stores actual quantity and actual end, and records closure. Do not automatically complete when the plan is reached. Once 05.6 enables policy-1 orders, recheck all completed-run postings under this parent lock before closure; a shortage leaves the order IN_PROGRESS and the physical run COMPLETED/UNPOSTED. Execution-only policy-null closure is unchanged. Posting has its own actor/event/command and must not rewrite completion events or cached completion responses.

#### Cancel and retry

- Cancel is allowed only before any run exists. Record reason, zero actual output and end timestamp without deleting records or changing recipe history. A concurrent start-run and cancel serialize on the parent; only one valid outcome commits.
- Same key/same canonical payload replays saved status/body; same key/different payload returns 409. Keys belonging to another actor cannot retrieve their cached response.
- A transport timeout after commit is resolved by retry with the same key or scoped GET, not a compensating write. Bound lock/statement timeouts. Do not retry partial SQL sequences or blindly repeat a non-idempotent command.

### V. Failure Modes and Resilience

| Stage | Failure Mode | Agent/System Action | Next State/Error Report |
|---|---|---|---|
| Dependency gate | BOM guarantees absent or sequencing unapproved | Stop implementation | Design only; explicit blocker |
| Contract/codegen | Missing operation/policy/proxy or YAML/JSON drift | Fail targeted checks/startup | Chunk not accepted |
| Authentication | Invalid/missing credential | Reject before lookup | 401 `UNAUTHENTICATED` |
| Authorization | Wrong organization/capability/eligibility or empty grants | Reject before replay | 403 `FORBIDDEN` |
| Scope | Missing/out-of-scope parent or wrong parent/run pair | Use scoped SQL; reveal no foreign metadata | 404 `RESOURCE_NOT_FOUND` |
| Input | Invalid UUID, schedule, precision/range, key or server-owned fields | Reject without mutation | 400 `VALIDATION_ERROR` |
| Plan/start | Inactive/wrong-type location/product, unusable BOM | Roll back | 409 `CONFLICT` (missing authorized reference: 404) |
| History | Attempted mutation of legacy record | Preserve history | 409 `CONFLICT`; audited adoption required |
| Lifecycle | Open-run duplication, premature close, cancel after run, terminal mutation | Serialize/recheck under parent lock | 409 `CONFLICT` |
| Results | Zero attempted total or aggregate storage overflow | Reject without recording results | 400 for invalid payload; 409 for cumulative capacity |
| Replay | Same key with changed request | Return no saved foreign/different result | 409 `CONFLICT` |
| Concurrency | Start/complete/cancel races | Parent lock plus open-run unique index | One consistent commit; loser rechecks |
| Persistence | Timeout/deadlock/FK/event/ledger failure | Roll back entire command; sanitized mapping | 503 `DB_TIMEOUT` for timeout; otherwise safe 500 `INTERNAL_ERROR` or classified conflict |
| Delivery | Response lost after successful commit | Retry same authorized command key | Original persisted result; no duplicate event/run |
| Migration | Bad legacy values, duplicate open runs, multiple heads | Abort with redacted actionable report | No automatic deletion/backfill fabrication |
| Partial implementation | Handler/repository path not implemented | Explicit failure, no writes | 501 `NOT_IMPLEMENTED` |

Use existing public codes; `NOT_IMPLEMENTED` is the existing placeholder convention, not a member currently observed in `ErrorCodes`. Proposed typed manufacturing exceptions must survive service-proxy transport via explicit failure-code mapping and HTTP tests; do not leak SQL/exception text through the generic fallback.

### VI. Security, Integrity, Idempotency, and Cleanup

- **Security:** enforce grants in repository SQL as well as middleware; no unscoped lookup or cached-response bypass. Keep principal identity separate from request JSON. Test wrong issuer/organization, missing capability, service/non-operator run attempts, and guessed parent/run combinations.
- **Integrity:** immutable pinned recipe and finished/base-UOM interpretation require actual 05.4/catalog safeguards, not just immutable BOM rows. Completed result quantities and execution provenance are immutable; the only proposed later enrichment is 05.6's posting-owned material projection. Parent-serialized lifecycle, database open-run uniqueness, and transactional event/command writes remain mandatory. Summary arithmetic uses exact completed quantities.
- **Lock order:** agree cross-domain BOM/product/location ordering with actual Task 05.4 code before planning implementation. Use work-order → command → run for existing execution records; avoid a command-first/parent-first inversion between endpoints. Parentless plan locking must not introduce a reverse dependency into BOM activation.
- **Idempotency:** all six commands require durable keys; scope includes issuer as well as subject/organization. Normalize semantic payload; don't include request ID, generated timestamp or authorization grants in fingerprints. Recheck current grants before replay. Preserve original body/status, but emit the current HTTP request ID.
- **Audit/privacy:** track starter and completer independently; do not fabricate historical identities or truncate subjects. Log safe IDs/outcomes, never credentials, command response bodies, operator metadata dumps or raw notes.
- **Migration/cleanup:** use an approved disposable test database and user-approved Python environment. Never reset a previously migrated target. Cleanup only test-created records in FK-safe order using approved fixture conventions; append-only event cleanup may require transaction rollback or an explicitly isolated database, not disabled production protections.
- **Rollback:** prefer forward corrective migrations after real execution history exists. Do not delete production history to roll back a feature. Disabling routes does not undo completed runs.
- **Task 05.6 boundary:** stable run/parent IDs, immutable execution results and recipe snapshot are integration inputs only. Its proposed separate posting ledger, policy marker, closure gate and posting-owned material projection are the aligned extension, subject to approval and a common lock order. Only that immutable posting/line linkage proves managed posting. Never replay completed history or reinterpret old completion retries as stock commands.
- **Deferred:** costing, lots/serial allocation, scheduling resources, reservations, quality disposition, abort/correct/reopen, recursive BOM explosion, UOM conversion, order linkage and deployment acceptance.

### VII. Validation Strategy

Commands below are implementation-time checks, not checks executed by writing this ADS. Proposed test classes must exist in the same slice that first invokes them. Use Java 25; obtain/activate the user's Python virtual environment before executing Python. Confirm database target explicitly; `TestDatabase.assumeAvailable` skips do not count as acceptance.

- **Compile/codegen:** `rtk proxy ./gradlew compileKotlin compileJava` after every Kotlin/Java integration change. Never edit generated proxy files directly.
- **Formatter:** no formatter plugin was found in the inspected build file. Confirm repository tooling in Chunk 0, run its established formatter if available, otherwise preserve surrounding style and report the absence; do not invent a formatter task. Run `rtk git diff --check` every slice.
- **Repository:** proposed `rtk proxy ./gradlew test --tests com.literp.repository.ManufacturingWorkOrderRepositoryTest` for scoped reads, plan/pinning, transitions, decimal summaries, atomic event/command writes and replay.
- **HTTP:** proposed `rtk proxy ./gradlew test --tests com.literp.verticle.ManufacturingWorkOrderHttpIntegrationTest` for all ten operations, production-router/service-proxy error propagation, envelopes/request IDs, exact decimals, auth and state transitions.
- **Contract/security:** `rtk proxy ./gradlew test --tests com.literp.contract.OpenApiOperationIdRegistrationTest --tests com.literp.contract.BrunoCollectionContractTest --tests com.literp.security.SecurityPolicyTest --tests com.literp.verticle.AuthenticationHttpIntegrationTest`; add proposed `--tests com.literp.contract.ManufacturingWorkOrderContractTest` once created.
- **Assets:** `rtk python scripts/verify_openapi_assets.py` after extending its actual bundle inventory. Also parse the bundle through the production Vert.x router; pair equality alone is insufficient.
- **Migration:** `rtk python -m py_compile <confirmed-new-revision>` and `rtk python scripts/verify_migrations.py` only after environment/target approval. Validate fresh install and seeded upgrade separately; one head, checks/indexes, command uniqueness, preserved legacy quantities/material JSON/IDs/movements, and invalid-data preflight refusal.
- **Symbols:** `rtk rg -n 'ManufacturingWorkOrder|ManufacturingScope' src/main src/test`; confirm all proxy callers/fakes, scope-enum consumers and policy inventories compile together.
- **Concurrency:** independent connections/barriers for duplicate plan key, duplicate start-run, two run completions, cancel-versus-start-run, complete-order-versus-start-run, same/different-payload replay, and plan-versus-BOM-deprecation. Assert results, one event per mutation, no orphan command and no stale aggregate.
- **Adversarial arithmetic:** fractional output/scrap, zero good/all scrap, zero attempted rejection, maximum/excess precision, nonfinite/negative, aggregate overflow, under/over-plan, weighted multi-run yield and null zero-denominator yield. Assert no stock/reservation/payment movement in every write path.
- **Rollback failures:** inject failures between domain/event/ledger writes and verify all-or-nothing; exercise lost-response retry after commit and revoked-grant replay denial.
- **Integration smoke:** plan → start → start run → complete run → second run → complete order; separate cancel-before-run path; foreign-location and wrong-parent denial. Seed data unchanged; historical records remain readable.
- **Final regression:** `rtk proxy ./gradlew test` and `rtk proxy ./gradlew build` because bundle loading, proxy codegen and shared security inventories change. Preserve BOM/POS/order behavior.
- **Bruno:** one credential-free request per operation with inherited auth, reusable IDs/keys and documented expectations. Run external smoke only when local CLI/token/target are explicitly available/approved; otherwise report it pending.
- **Diff:** after each slice, `rtk git status --short`, `rtk git diff --check`, and `rtk git diff -- <changed-files>`; inspect untracked files separately. Verify scope and record skipped checks.

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full feature in one pass.

All new paths/symbols remain proposed. Names abbreviated below refer to the exact roots in Section III. Most logic slices touch repository + focused repository test; if observed proxy/handler contracts must change, identify the minimal additional files before editing. The contract-publication and first read-wiring exceptions necessarily span layers to keep operation/security/proxy inventories consistent.

Every chunk requires formatter/style checks, targeted validation, symbol checks, diff review and a risk report. Execute one authorized chunk and stop. Ask before creating a single-chunk handoff; do not automatically commit or proceed.

#### Chunk 0: Discovery and Integration Confirmation
- **Goal:** Reconfirm repository state, BOM readiness, sequencing and all proposed business/security/migration decisions.
- **Files to read:** Task 05.5/05.6 plan; this ADS; Task 05.4 actual implementation/design; structure decision; initial/seed/latest migrations; loader/security/proxy/transaction/test/asset inventories.
- **Commands:** `rtk git status --short --branch`; `rtk git log -1 --oneline`; `rtk rg -n 'work_order|production_run|manufacturing' src python/database/migration docs/implementation-plan api_collections`.
- **Evidence to confirm:** Explicit approval of Section II decisions; compatible definition locks; actual migration head; legacy/time policy; formatter; Python environment and disposable database; no dependence on assumed implemented BOM behavior.
- **Stop condition:** Read-only report. Unresolved cancellation, actor, snapshot, BOM or migration decisions block implementation.

#### Chunk 1: Contracts and Compile-Safe Stubs
- **Goal:** Publish all ten operations behind authenticated explicit placeholders.
- **Files to change:** Proposed YAML/JSON/README, stub `ManufacturingWorkOrderHandler` and `ManufacturingScopeHandler`, contract test, ten Bruno assets; existing `HttpServerVerticle.kt`, `SecurityPolicy.kt`, verifier and operation/security/auth/Bruno inventories. Multi-file exception is required for atomic contract/route/policy publication.
- **Symbols to add/change:** Ten operation methods/IDs, bundle loader/subrouter, manufacturing scope rules and human-operator eligibility enforcement.
- **Implementation shape:** Validate principal/capability/grant context, then 501 without persistence. Resource-specific ownership is enforced in SQL when behavior is wired; placeholders expose no resource data. No new proxy or schema yet. Document every route as a placeholder.
- **Validation:** Compile/codegen command; contract/security commands and asset verifier from Section VII; production-router parse and authenticated/non-operator/no-grants placeholder tests.
- **Stop condition:** Route/spec/policy/Bruno parity, no fake successes or writes; existing endpoints unaffected.

#### Chunk 2: Execution Persistence And Legacy Safety
- **Goal:** Install minimum constraints, snapshot/provenance, command/event storage and numbering needed by the approved execution path.
- **Files to change:** One additive Alembic revision; `scripts/verify_migrations.py` for focused checks. Two-file persistence exception to a user-visible slice, needed before any API write.
- **Symbols to add/change:** Section III approved checks/indexes/sequence/actor fields/ledger/events; legacy preflight and single-head verifier assertions.
- **Implementation shape:** Additive upgrade, no invented history; preflight invalid data before constraints; preserve material JSON and movements. API stays 501 and existing code compiles unchanged.
- **Validation:** Approved-environment migration commands in Section VII; fresh/seeded upgrade, duplicate-open-run and decimal constraint probes, append-only protections, legacy unchanged checks.
- **Stop condition:** New invariants verified on approved targets; no lifecycle endpoint enabled and no deployment migration executed.

#### Chunk 3: Scoped Read Vertical Slice
- **Goal:** Enable four read endpoints from HTTP through proxy to scoped SQL, including legacy visibility and summaries.
- **Files to change:** Proposed repository/test, Java service and manufacturing ModuleGen only if absent, service implementation, handler, focused HTTP tests, `HttpServerVerticle.kt`; availability documentation/contract expectations. First proxy wiring is an inseparable multi-file exception.
- **Symbols to add/change:** Four reads; ten minimal service/repository contracts with explicit error stubs for commands; safe manufacturing error transport/mapping and row/summary mapping.
- **Implementation shape:** Define called methods with their callers, parameterize location/parent predicates, bounded lists and decimal aggregates. Command HTTP routes remain explicit 501. Test non-empty legacy and API-shaped run summaries.
- **Validation:** Compile/codegen; proposed repository/HTTP/contract tests from Section VII; registration/security tests; direct and proxy scope/error checks.
- **Stop condition:** Four reads work without cross-location leakage; six writes still fail safely. Unimplemented calls never return success.

#### Chunk 4: Idempotent Work Order Planning
- **Goal:** Enable one plan command end-to-end with active-definition checks and durable pinning.
- **Files to change:** Repository + repository test; handler/HTTP test and contract/README availability only as needed to replace plan's 501 with its already-defined proxy call.
- **Symbols to add/change:** `planWorkOrder`, plan fingerprint/command helpers, sequence allocation, snapshot validation and event append helpers.
- **Implementation shape:** Claim collection-scoped key, validate dependencies with agreed locks, create PLANNED/API record plus result/event in one transaction. No inventory writes; other commands stay stubs.
- **Validation:** Compile and focused repository/HTTP plan tests; repeated/changed keys, invalid BOM/location, deprecated BOM race, snapshot identity, lost-response replay, failure rollback and no stock effects.
- **Stop condition:** One authorized plan creates exactly one stable order/event; bad or duplicate commands leave no partial state.

#### Chunk 5: Work Order Start And Pre-Run Cancellation
- **Goal:** Make planned work actionable or safely cancellable before any run exists.
- **Files to change:** Repository + repository test; corresponding handler/HTTP/availability deltas only where required.
- **Symbols to add/change:** `startWorkOrder`, `cancelWorkOrder`, parent locking and transition/replay checks.
- **Implementation shape:** Small cohesive parent-state transitions sharing lock/event/command helpers; retain pinned deprecated BOMs, reject legacy mutation, store cancellation reason and timestamps. Completion/run writes remain stubs.
- **Validation:** Compile and targeted repository/HTTP lifecycle tests; start twice, cancel planned/started-without-run, conflicting reason, terminal rejection, legacy refusal, granted reads of inactive location.
- **Stop condition:** Start/cancel transitions are auditable and repeatable; no production results are manufactured by cancellation.

#### Chunk 6: Operator-Attributed Run Start
- **Goal:** Enable exactly one active execution run under an authorized IN_PROGRESS parent.
- **Files to change:** Repository + repository test; start-run handler/HTTP/availability deltas only where required.
- **Symbols to add/change:** `startProductionRun`, trusted starter mapping, open-run guard and timestamp handling.
- **Implementation shape:** Parent → command locking, current eligibility checks, zero-quantity IN_PROGRESS insert, starter identity and event/result transaction. Existing complete-run stub remains explicit.
- **Validation:** Compile and focused repository/HTTP tests; human operator versus service/non-operator; two concurrent starts, same-key replay, cancel/start race, wrong location, no stock changes.
- **Stop condition:** One open run maximum with trustworthy attribution; clearly report completion is still unavailable and use disposable fixtures only for acceptance.

#### Chunk 7: Run Results And Work Order Closure
- **Goal:** Complete the smallest finished production loop while keeping results and parent totals consistent.
- **Files to change:** Repository + repository test; completion handler/HTTP/contract availability deltas. Both closures share the same parent serialization and completed-total invariants; no unrelated feature is included.
- **Symbols to add/change:** `completeProductionRun`, `completeWorkOrder`, exact result validation and prospective aggregate checks.
- **Implementation shape:** Record immutable run quantities/completer, then allow explicit work-order closure only with completed runs/no open run. Reuse read summary arithmetic; preserve no-inventory boundary. All newly called helpers exist in this slice.
- **Validation:** Compile; focused arithmetic/state/replay/rollback repository and HTTP tests; weighted yield, all-scrap/partial/over-plan, duplicate/different result, overflow, complete/start races and inactive-after-start closure.
- **Stop condition:** Entire execution-only loop works with consistent history and no stock posting; all six writes leave no 501 business placeholders. No stock-policy marker is enabled here. When 05.6 is integrated, retain tests distinguishing policy-null closure from policy-1 POSTED-before-close, and immutable execution facts from posting-owned enrichment.

#### Chunk 8: Integrated Acceptance And Documentation
- **Goal:** Prove the Task 05.5 done-when criteria and explicitly record deferred inventory/external acceptance.
- **Files to change:** Focused HTTP/contract tests and Bruno/README assets; `docs/README_API.md`, Phase 05 plan and relevant model documentation only for verified contract/evidence updates. This acceptance slice necessarily spans published inventories, not production logic.
- **Symbols to add/change:** End-to-end and security/concurrency regression cases, operation availability and acceptance evidence; no new runtime feature.
- **Implementation shape:** Exercise two-run completion/cancel/scoping, legacy reads, immutable results and no movement effects; reconcile docs and synchronized OpenAPI/Bruno. Do not mark external acceptance complete without actual smoke evidence.
- **Validation:** Section VII final test/build, full asset/registration/Bruno parity, migration/legacy evidence and explicitly authorized Bruno smoke; diff/credential review.
- **Stop condition:** Task 05.5 locally accepted with concrete no-skip results or named blockers. Task 05.6 design/implementation and deployment remain separately authorized work.

### IX. Handoff to `chunked-implementation`

Recommended agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, pre-edit-discipline, safe-python-edit, and post-edit-discipline if available.

Task:
Task 05.5 Work Order And Production Run API.
Use docs/implementation-plan/ads/phase-05-task-5.md.

Mode:
Execute Chunk 0 only. Do not edit files. Confirm repository evidence and stop.
Confirm Task 05.4 BOM guarantees are implemented or report that dependency as blocked.
Resolve the proposed lifecycle, operator, legacy, idempotency and inventory-boundary decisions.
Do not resume Task 05.3/05.4 implementation or deploy.
```

After Chunk 0 and its decisions are accepted:

```text
Use the chunked-implementation skill.
Use docs/implementation-plan/ads/phase-05-task-5.md.
Execute Chunk 1 only. Do not continue to Chunk 2.
After editing, run targeted validation, review git diff and report remaining risks.
Ask before creating a handoff. Do not commit or deploy without explicit authorization.
```

### X. Conclusion and Next Steps

This proposed API separates production execution history from stock posting, pins recipe identity, attributes real operators, serializes lifecycle changes and makes yield/scrap arithmetic explicit. It preserves legacy data rather than inventing attribution or replaying old stock effects.

Next: approve or revise the Section II decisions, especially PRODUCTION-only locations, operator eligibility, legacy read-only policy and cancellation/run-abort limitations. Then authorize read-only Chunk 0. This ADS does not complete Task 05.5, bypass unfinished earlier tasks, or authorize Task 05.6.
