## Architectural Design Specification: Bill Of Material Definition And Lifecycle API

**Source:** [Phase 05, task 05.4](../05-pos-manufacturing-expansion.md#054-bom-api), following Task 05.3 receipt/refund work.

**Status:** Proposed; design only. Task 05.4 behavior is not implemented by this document. The operation matrix, lifecycle rules, nested-BOM policy, and migration decisions below require approval. No deployment authorization.

**Evidence revision:** `f3c7e15`, branch `phase-05-task-3`, tracking `origin/phase-05-task-3`. The pre-existing untracked `phase-05-task-3.md` ADS was preserved. No fetch was performed.

**Goal:** Publish and implement an authenticated BOM API that creates versioned draft definitions, manages component lines, safely activates one immutable version per finished product, and deprecates definitions without breaking work-order history.

---

### I. Overview and Contract

Task 05.4 establishes manufacturing definitions only. It does not plan or execute work orders, create production runs, consume components, produce inventory, calculate actual yield, or change sales/POS behavior. Task 05.5 consumes the stable BOM identity and Task 05.6 owns inventory movement effects.

A BOM is an organization-wide product definition rather than a location-owned resource. Authentication, organization membership and explicit capability are mandatory, but location grants do not filter BOMs. Every response uses existing `{data: ...}` and list `{data: [...], pagination: ...}` conventions and preserves `X-Request-ID`.

#### Proposed operation matrix requiring approval

All paths are relative to `/api/v1` and belong to a proposed `manufacturing-bom.yaml`/`.json` OpenAPI bundle.

| Method/path | operationId (proposed) | Capability (proposed) | Behavior |
|---|---|---|---|
| GET `/manufacturing/boms` | `listBillsOfMaterial` | `manufacturing.bom.read` | Paginated list with product/status filters |
| POST `/manufacturing/boms` | `createBillOfMaterial` | `manufacturing.bom.write` | Create a DRAFT BOM |
| GET `/manufacturing/boms/{bomId}` | `getBillOfMaterial` | `manufacturing.bom.read` | Return BOM with ordered lines |
| PUT `/manufacturing/boms/{bomId}` | `updateBillOfMaterial` | `manufacturing.bom.write` | Replace draft product/version identity |
| POST `/manufacturing/boms/{bomId}/activate` | `activateBillOfMaterial` | `manufacturing.bom.write` | Validate and activate definition |
| POST `/manufacturing/boms/{bomId}/deprecate` | `deprecateBillOfMaterial` | `manufacturing.bom.write` | Retire draft or active definition |
| POST `/manufacturing/boms/{bomId}/lines` | `addBillOfMaterialLine` | `manufacturing.bom.write` | Add a draft component line |
| PUT `/manufacturing/boms/{bomId}/lines/{bomLineId}` | `updateBillOfMaterialLine` | `manufacturing.bom.write` | Replace editable line values |
| DELETE `/manufacturing/boms/{bomId}/lines/{bomLineId}` | `removeBillOfMaterialLine` | `manufacturing.bom.write` | Remove a draft line |

List/get operations are included because users cannot safely update, activate, or manage lines without retrieving BOM identity and current state. A separate list-lines operation is unnecessary because get returns lines in sequence order.

#### Proposed payload and lifecycle decisions

1. **Create:** request requires `productId` and positive integer `bomVersion`; the server creates UUID, `DRAFT`, timestamps and verified actor fields. `Idempotency-Key` is required so retry cannot create a second definition.
2. **Update:** full replacement requires `productId` and `bomVersion`. Only an unreferenced `DRAFT` may change. Status, IDs, actors and timestamps are server-owned. Product/version become immutable after activation or deprecation.
3. **Version uniqueness:** `(product_id, bom_version)` is unique. Version numbers need not be contiguous, but must be positive. The API does not infer `MAX + 1`; callers choose the business version explicitly.
4. **One active version:** at most one `ACTIVE` BOM exists per finished product. Activating a draft atomically deprecates the previously active version for that product and activates the target. Existing work orders remain pinned to their original BOM even after deprecation.
5. **Lifecycle:** `DRAFT → ACTIVE → DEPRECATED` is the normal path. `DRAFT → DEPRECATED` abandons a draft. `DEPRECATED` is terminal; no reactivation, deletion, or direct status update is exposed. Repeating activation of the currently active target or deprecation of an already deprecated target returns the original/current success safely; attempting to activate a target already superseded and deprecated conflicts.
6. **Edit boundary:** header and line mutations are allowed only while `DRAFT` and while no `work_order` references that BOM. `ACTIVE` and `DEPRECATED` definitions are immutable historical configuration.
7. **Finished product:** create/update/activation require an existing active `STOCK` product. The current catalog has only `STOCK` and `SERVICE`; no invented manufactured-product type is introduced.
8. **Line payload:** add requires `componentProductId`, positive `quantityPerUnit` with at most three decimals, `scrapPercentage` from 0 through 100 with at most two decimals, and positive `sequence`. Update replaces quantity/scrap/sequence; component identity is immutable. Remove is repeatable after the parent BOM is authorized.
9. **Component snapshot:** `componentSku` is derived from the referenced product and stored by the server; clients cannot submit or override it. Component products must exist, be active and `STOCK` when a line is added/updated and again when the BOM activates.
10. **Line uniqueness:** a component product occurs once per BOM and each sequence occurs once per BOM. Reordering multiple lines is not a bulk operation in this task; clients use temporary free sequence values or serial updates. A future bulk-reorder contract may improve that workflow.
11. **Unit semantics:** `quantityPerUnit` is measured in the component product's base UOM per one base-UOM unit of the finished product. No UOM conversion table exists. Catalog currently allows base-UOM/type edits; immutable BOM rows alone do not freeze quantity meaning. Before activation is accepted, approve either compatible catalog reference guards or immutable definition-level unit snapshots with explicit drift rejection. Align with 05.5 execution snapshots and 05.6 catalog guards; do not defer a required recipe-stability guarantee until after execution depends on it.
12. **Nested BOMs:** STOCK components may themselves have an active BOM. Activation rejects direct self-reference and indirect cycles under an approved graph-mutation protocol. Task 05.5 snapshots direct lines; Task 05.6 consumes stocked subassemblies as direct components. Recursive explosion, raw-descendant consumption and automatic child work orders are deferred to a separate design, not promised by either task.
13. **Activation readiness:** a draft must have at least one valid line. Activation revalidates product activity/type, component activity/type, uniqueness, numeric bounds, SKU consistency and acyclic graph under transaction locks.
14. **Idempotency/audit:** create and add-line require bounded `Idempotency-Key`; lifecycle commands are transactionally repeatable and also record their key when supplied. Proposed actor columns and append-only lifecycle events preserve who created, changed, activated or deprecated a definition. No client actor override is accepted.
15. **No hard delete:** BOM deletion is intentionally absent. Draft line deletion is allowed because the parent is not executable; BOM history referenced by work orders is retained.

**Function Signature Contract (Concrete):**

- `BaseRepository.inTransaction(work: (SqlConnection) -> Single<T>): Single<T>` is the existing repository transaction boundary.
- `BaseHandler.putSuccessResponse`, `putSuccessEnvelopeResponse`, `putMappedErrorResponse`, and list-query helpers provide current HTTP response conventions.
- Existing Vert.x Java services use `Future<JsonObject>`, static `createProxy(Vertx)`/`register(Vertx, service)`, and a domain `@ModuleGen` package.
- `HttpServerVerticle.loadApiContracts` currently loads four YAML bundles and registers one subrouter per bundle.
- `ExplicitSecurityPolicy.businessOperationIds` must equal the OpenAPI and registered operation-ID sets.

**Function Signature Contract (Conceptual):**

- Proposed `ManufacturingBomRepository` methods mirror the nine operation IDs and return `Single<JsonObject>` except remove-line, which may return `Completable`/`Single` according to confirmed service-proxy compatibility.
- Proposed `ManufacturingBomService` methods return `Future<JsonObject>` or `Future<Void>` and carry only proxy-compatible scalars/`JsonObject`; trusted `actorSubject` and `organizationId` are separate server-derived arguments on writes.
- Proposed `ManufacturingBomHandler` methods accept the existing RxJava `RoutingContext` and map validated OpenAPI input to the service.
- Proposed internal lifecycle/validation helpers lock, validate and map rows; exact names/signatures remain subject to Chunk 0 confirmation.

Before repository/service behavior exists, every proposed handler method returns an explicit authenticated `501 NOT_IMPLEMENTED`. Stubs must not return empty success, reserve command keys, or mutate persistence. Create handler symbols before or with route call sites.

### II. Observed Evidence and Assumptions

#### Observed evidence

| Evidence read | Design implication |
|---|---|
| `docs/implementation-plan/05-pos-manufacturing-expansion.md`, Task 05.4 | Requires a BOM spec, create/update/activate/deprecate, line management, Bruno and integration tests |
| `docs/knowledge/MODEL_DESIGN.md` | BOM is a versioned finished-product recipe; lines carry component, SKU snapshot, quantity, scrap and sequence; work orders reference BOM directly |
| Initial migration `314b57a8dd0f_00_initial_migration.py` | Tables/enums exist, but no unique product/version, one-active rule, numeric checks, actor/audit fields or lifecycle command ledger exist |
| Same migration, product schema | Product type is only `STOCK`/`SERVICE`; products have immutable SKU behavior and base UOM references |
| Seed migration | One ACTIVE latte BOM has four ordered component lines; completed and planned work orders reference it |
| Existing migration set search | No later revision strengthens `bill_of_material` or `bom_line` |
| `PROJECT_STRUCTURE_DECISION.md` | Manufacturing belongs in current handler/repository layers plus `service/manufacturing` only when a concrete proxy is introduced; assets stay under current OpenAPI/Bruno roots |
| `HttpServerVerticle.kt` | A fifth contract requires loader data, registration, subrouter and service/repository/handler wiring changes |
| `verify_openapi_assets.py`, operation registration and Bruno tests | Bundle inventories are hardcoded; YAML/JSON parity alone is not semantic/runtime validation |
| `SecurityPolicy.kt` | Explicit deny-by-default operation policy exists; there is no manufacturing capability or resource scope yet |
| `BaseRepository.kt` and POS/order repositories | PostgreSQL transaction and unique/FK error helpers exist; command idempotency patterns exist but are domain-specific |
| Current test inventory/docs | Registered-operation counts and authentication/documentation inventories are explicit and must change only with evidence |

#### Assumptions and proposed decisions

- BOMs are shared across locations within the configured organization. Location grants do not constrain read/write access.
- Human and service principals may manage BOMs when they have exact capabilities. If interactive human-only governance is required, approve it before contract publication.
- Activating a new version deprecates the old active version atomically rather than requiring a two-call gap.
- API-managed work orders remain pinned and executable under Task 05.5 rules even if their BOM is later deprecated. Pre-existing legacy work orders remain readable but cannot be mutated/adopted automatically under 05.5; deprecation does not authorize legacy execution or stock replay.
- Nested BOM definitions are allowed, but this task validates acyclicity only; explosion, rounding and material demand are deferred.
- No tenant column exists in BOM tables; the current configured organization boundary remains the deployment boundary, consistent with existing data tables.

#### Open confirmations for Chunk 0

- Approve all nine paths/operation IDs, capabilities, authenticated principal eligibility and organization-wide scope.
- Approve client-selected positive versions, DRAFT-only header mutation and no BOM delete/reactivation.
- Approve atomic old-version deprecation, existing-work-order pinning, nested BOM support and cycle policy.
- Approve numeric bounds, UOM semantics, server-derived component SKU, line uniqueness and repeatable line removal.
- Approve one graph-mutation serialization strategy and cross-domain lock order before behavior integration. A shared transaction-scoped graph guard for all graph writers, or serializable transactions with bounded whole-command retries, are alternatives requiring design confirmation, not implemented helpers. Test transitive cross-product cycles and competing versions of the same product; direct-component product locks alone are insufficient.
- Resolve recipe unit stability with catalog writers before activation/05.5 integration; any additional prerequisite slice must be explicit in the approved chunk plan.
- Approve command-idempotency and actor/lifecycle-audit persistence, and decide whether all mutations require `Idempotency-Key`.
- Confirm Task 05.3 completion or explicit permission to implement 05.4 independently, current migration head, proxy codegen conventions, disposable database and Python environment.

### III. Required Technical Dependencies and Imports

Reuse PostgreSQL constraints, transactions, recursive CTEs and row locks; Alembic; Vert.x OpenAPI router/service proxy/codegen; RxJava `Single`/`Completable`; `JsonObject`; SQL client `Pool`/`SqlConnection`/`Tuple`; Java `BigDecimal`, UUID and digest APIs where durable fingerprints are approved; JUnit and existing HTTP/security fixtures. No new runtime dependency or external manufacturing system is proposed.

Expected new paths, all proposed under the accepted structure:

- `src/main/kotlin/com/literp/repository/ManufacturingBomRepository.kt`
- `src/main/kotlin/com/literp/verticle/handler/ManufacturingBomHandler.kt`
- `src/main/java/com/literp/service/manufacturing/ManufacturingBomService.java`
- `src/main/java/com/literp/service/manufacturing/package-info.java`
- `src/main/kotlin/com/literp/service/manufacturing/impl/ManufacturingBomServiceImpl.kt`
- focused repository, contract and HTTP integration tests under existing test roots
- `api_collections/open_api_spec/manufacturing-bom.yaml`, synchronized JSON and README
- one Bruno request per operation under `api_collections/Literp`.

A proposed additive Alembic revision after the then-current head adds safe uniqueness/check constraints and, if approved, nullable legacy-compatible actor fields, lifecycle events and a manufacturing command ledger. Do not modify the initial or seed migrations. Existing ACTIVE seed data and referenced work orders must survive upgrade unchanged.

### IV. Step-by-Step Procedure / Execution Flow

#### Shared request and persistence discipline

1. Authenticate, enforce configured organization and exact `manufacturing.bom.read`/`manufacturing.bom.write` capability. No location-scope handler is used because BOMs are organization-wide.
2. Validate UUIDs, enum/filter allowlists, positive bounded numbers, request fields and idempotency headers before dispatch. Reject server-owned/unknown fields.
3. Pass trusted actor and organization separately from request JSON. Never trust client SKU, status, actor, timestamps or lifecycle event fields.
4. Use parameterized SQL and allowlisted sort columns. List count and data predicates must match; order by product, version and BOM ID deterministically.
5. Use the approved common graph-mutation protocol before BOM/product locks on every path that can affect graph validity. Within that protocol, lock BOM/line and product rows in one documented order compatible with catalog, planning and posting writers. The original target-BOM → product → other-active-BOM sequence can deadlock between competing versions; sorted direct-product locks also fail to cover transitive concurrent cycles. Do not implement that sequence as a proven safety protocol. The final lock map is a Chunk 0 blocker, not an invented helper contract.
6. Store mutation response/idempotency state and lifecycle event in the same transaction as domain changes. Roll back everything on validation, conflict or persistence failure.

#### Create, read and draft update

- Create validates active STOCK product and version uniqueness, claims the command key, inserts DRAFT with trusted actor, stores response, and commits atomically.
- List supports bounded `page`, `size`, sort, optional `productId`, and `status`. Get returns header plus lines ordered by `sequence, bom_line_id`; missing IDs return 404.
- Update locks the BOM, requires DRAFT/no work-order reference, validates the replacement active STOCK product and unique product/version, and rechecks all existing lines for direct self-reference. If changing product could create a future cycle, activation remains the final authoritative graph check.

#### Draft line management

- Add locks the BOM, verifies editable state/no work-order reference, claims key, loads active STOCK component, rejects direct self-reference, and inserts server SKU plus exact decimal values.
- Update locks BOM then line, preserves component identity/SKU, replaces quantity/scrap/sequence and maps uniqueness conflicts safely.
- Remove first locks/authorizes the parent BOM. If the parent exists and is editable, deleting a missing line is a repeatable 204; a missing BOM remains 404. No active/deprecated line can be removed.

#### Activation and deprecation

1. Enter the approved graph-mutation serialization boundary before locking candidate/active definitions; collect and lock affected rows in the agreed cross-domain order. Include deprecation and any other participating graph writer in the protocol.
2. Recheck activity/type/SKU, stable unit interpretation and all line constraints after locks are held. Require at least one line.
3. Run recursive cycle detection against the candidate edges plus currently ACTIVE BOM edges, excluding the active version that this candidate will replace for the same product. Any path returning to the finished product conflicts.
4. Under the same approved lock protocol, deprecate the prior active version, append events, activate target and append its event in one transaction. Database partial uniqueness guards one active version; it does not enforce graph acyclicity.
5. Deprecation locks the target. DRAFT or ACTIVE becomes DEPRECATED with an event; already DEPRECATED returns current data. It never deletes lines or changes referenced work orders.

### V. Failure Modes and Resilience

| Stage | Failure Mode | Agent/System Action | Next State/Error Report |
|---|---|---|---|
| Task gate | Task 05.3 incomplete and no independent 05.4 authorization | Stop implementation | ADS only; no feature edits |
| Contract publication | YAML/JSON drift, invalid reference, missing policy/route/Bruno | Fail verification/startup tests | Bundle not accepted |
| Authentication | Missing/invalid bearer or wrong organization | Reject before data access | 401 `UNAUTHENTICATED` or 403 `FORBIDDEN` |
| Authorization | Missing exact read/write capability | Reject before lookup/replay | 403 `FORBIDDEN` |
| Input | Invalid UUID, status, decimal precision/range, version, sequence or fields | Reject before transaction | 400 `VALIDATION_ERROR` |
| Lookup | BOM/line/product missing | Roll back/no mutation | 404 `RESOURCE_NOT_FOUND` |
| Draft mutation | BOM is ACTIVE/DEPRECATED or referenced by a work order | Preserve definition/history | 409 `CONFLICT` |
| Product validation | Finished/component product inactive or SERVICE | Reject definition/change | 409 `CONFLICT` |
| Uniqueness | Duplicate product/version, component or sequence | Map database guard safely | 409 `CONFLICT` |
| Activation | No lines, stale SKU, invalid component or direct/indirect cycle | Leave draft unchanged | 409 `CONFLICT` |
| Concurrent activation | Two versions/products or transitive graph edits race | Require approved graph serialization and cross-domain lock order; unique active index alone is insufficient | Block activation until protocol is implemented/tested; then recheck or retry safely |
| Retry | Same key changed payload | Return no prior response | 409 `CONFLICT` |
| Persistence | Timeout/deadlock/write failure | Roll back definition, command and event; bounded whole-transaction retry only with durable key | 503 `DB_TIMEOUT` or sanitized 500 |
| Partial delivery | Behavior not implemented | Keep authenticated handler stub | 501 `NOT_IMPLEMENTED`, no mutation |

Use existing public error codes. Proposed typed manufacturing validation/scope/conflict exceptions must be mapped explicitly; SQL text, product metadata and command payloads must not leak.

### VI. Security, Integrity, Idempotency, and Cleanup

- **Security:** all operations remain bearer-authenticated and deny-by-default. Organization-wide does not mean anonymous or cross-organization. Actor/organization come from the principal, and read does not imply write.
- **Integrity:** database checks enforce positive versions/sequences/quantities, bounded scrap, unique product/version, unique line component/sequence and one active BOM per product. Application activation revalidates cross-table and graph invariants under locks.
- **Historical stability:** ACTIVE/DEPRECATED recipe content is immutable; the explicit ACTIVE→DEPRECATED lifecycle transition is permitted and audited. Stable unit interpretation needs the approved catalog/snapshot prerequisite. Existing work orders keep exact BOM references; no cascade or replacement rewrites them. Legacy work orders remain read-only under 05.5.
- **Idempotency:** approved command ledger keys include organization, actor, operation, target and normalized fingerprint. Authorization runs before replay. Create/add response and mutation commit together; deterministic PUT and repeatable DELETE semantics remain documented.
- **Concurrency:** no deadlock-freedom or graph-serializability claim is made until the common protocol is approved and tested against activation, deprecation, catalog changes and work-order planning. Use independent connections/barriers, including cycles through pre-existing transitive paths and two target versions waiting on the same product. Database uniqueness alone cannot enforce an acyclic graph.
- **Audit/privacy:** lifecycle events record safe actor, transition, target, key and timestamp. Do not log bearer tokens, request bodies, product metadata, SQL parameter dumps or full command responses.
- **Cleanup/migration:** preflight legacy duplicates/invalid values and stop for explicit remediation; never delete seed/history automatically. Test cleanup removes only generated DRAFT records in FK-safe order. Once lifecycle data exists, prefer forward repair over destructive downgrade.
- **Out of scope:** work-order execution, production runs, inventory movement, costing, substitutions, location-specific BOMs, effective dates, alternates, batch/lot rules, UOM conversion, bulk reorder, delete/restore and Task 05.5/05.6 behavior.

### VII. Validation Strategy

Use Java 25 and an explicitly approved disposable PostgreSQL target. Obtain and activate the user's approved Python virtual environment before running migration/OpenAPI Python commands. Database-test skips do not count as acceptance.

- **Syntax/codegen:** `rtk proxy ./gradlew compileKotlin compileJava`; verify generated-proxy inputs and every service implementation/fake with `rtk grep -Rni 'ManufacturingBomService' src/main src/test`.
- **Migration:** `rtk python -m py_compile <confirmed-new-revision>` and `rtk python scripts/verify_migrations.py` in the approved environment. Test fresh/seed upgrade, duplicate preflight, numeric checks, active uniqueness, FK behavior and one head.
- **Repository tests:** proposed `rtk proxy ./gradlew test --tests com.literp.repository.ManufacturingBomRepositoryTest`; cover list/get, command replay, DRAFT edits, constraints, actor fields, lifecycle and rollback.
- **HTTP tests:** proposed `rtk proxy ./gradlew test --tests com.literp.verticle.ManufacturingBomHttpIntegrationTest`; cover all nine operations, envelopes/request IDs, invalid payloads and state transitions.
- **Security/parity:** `rtk proxy ./gradlew test --tests com.literp.security.SecurityPolicyTest --tests com.literp.verticle.AuthenticationHttpIntegrationTest --tests com.literp.contract.OpenApiOperationIdRegistrationTest --tests com.literp.contract.BrunoCollectionContractTest`.
- **Bundle contract:** proposed `rtk proxy ./gradlew test --tests com.literp.contract.ManufacturingBomContractTest`; parse the production bundle/router and verify operation, schema, response, 501-availability and Bruno parity.
- **OpenAPI pair:** after approved environment activation, `rtk python scripts/verify_openapi_assets.py`; add the fifth bundle to all authoritative inventories.
- **Adversarial cases:** duplicate version/component/sequence; zero/negative/overflow quantities; scrap below 0/above 100/extra precision; inactive/SERVICE product; self-reference; indirect cycles; empty activation; immutable active/deprecated edits; referenced draft anomaly; changed-payload replay.
- **Concurrency:** simultaneous create same version, add same component/sequence, activate two versions for one product, and cross-product nested activation capable of forming a cycle. Assert one active BOM and no cyclic committed graph.
- **Regression:** existing product, order, POS, migration and auth tests remain green; seed work orders still reference the same BOM after upgrade. Run final `rtk proxy ./gradlew test` and `rtk proxy ./gradlew build` because loader, codegen, security and public operation inventories change.
- **Bruno smoke:** requests inherit auth and contain no credentials. Execute native smoke only if `bru` and an approved non-production token are locally available; otherwise document the pending evidence accurately.
- **Formatting/diff:** confirm repository formatter availability in Chunk 0; none is assumed. Preserve existing style, run `rtk git diff --check`, `rtk git status --short`, and review `rtk git diff -- <changed-files>` after every chunk.

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full feature in one pass.

All new names/paths are proposed until Chunk 0 approval. Each chunk must remain compile-safe and include direct validation. Multi-file exceptions are limited to inseparable OpenAPI publication and first Vert.x proxy wiring.

#### Chunk 0: Discovery and Integration Confirmation
- **Goal:** Confirm sequencing authorization and settle API/lifecycle/concurrency decisions.
- **Files to read:** Phase 05 Task 05.4; this ADS; Task 05.3 status; structure/auth references; migration graph/schema/seed; loader/verifier/parity tests; product and transaction patterns.
- **Commands:** `rtk git status --short --branch`; `rtk git log -1 --oneline`; `rtk grep -Rni 'bill_of_material\|bom_line\|work_order\|product_type' python src docs api_collections`.
- **Evidence to confirm:** Explicit implementation authorization; operation matrix; lifecycle/version/nesting/UOM/idempotency decisions; migration head; codegen callers; approved database/Python environment.
- **Stop condition:** Read-only report. Unresolved lifecycle, graph serialization, cross-domain lock order or unit-stability contracts block Chunk 1.

#### Chunk 1: Contracts and Compile-Safe Authenticated Stubs
- **Goal:** Publish the nine-operation BOM contract without claiming behavior.
- **Files to change:** New manufacturing BOM YAML/JSON/README; new stub handler and contract test; `HttpServerVerticle.kt`; `SecurityPolicy.kt`; verifier/operation/Bruno inventories; nine Bruno skeletons. This publication slice is atomically required for bundle/route/policy parity.
- **Symbols to add/change:** Proposed operation IDs/schemas/errors, `ManufacturingBomHandler` methods returning 501, fifth loader/subrouter, exact read/write policies and inventory assertions.
- **Implementation shape:** Authenticate/capability-check then explicit 501. No repository/service/schema or writes. Stubs use no fake success data.
- **Validation:** Compile, production-router parse/startup, bundle pair verifier, operation/security/auth/Bruno contract tests, credential scan.
- **Stop condition:** All nine operations are discoverable authenticated placeholders; existing APIs unchanged.

#### Chunk 2: Persistence Invariants And Audit Foundation
- **Goal:** Make existing BOM tables safe for API writes without enabling behavior.
- **Files to change:** One additive Alembic revision after confirmed head; migration verifier assertions only where established.
- **Symbols to add/change:** BOM/line checks and unique indexes; partial active uniqueness; approved actor/event/command-ledger storage.
- **Implementation shape:** Preflight legacy rows; preserve seed BOM/lines/work orders; nullable legacy actor fields and nonnullable API writes; restrictive audit FKs.
- **Validation:** Python syntax; migration fresh/legacy upgrade and downgrade-safety checks on approved disposable DB; one-head verification.
- **Stop condition:** Schema constraints pass with seeds; all endpoints remain 501.

#### Chunk 3: BOM List And Detail Read Slice
- **Goal:** Deliver read-only BOM discovery with ordered lines.
- **Files to change:** Proposed repository, Java service/package contract, Kotlin service implementation, handler, `HttpServerVerticle.kt`, focused repository/HTTP tests, OpenAPI/Bruno availability notes. Multi-file exception creates the first concrete proxy atomically.
- **Symbols to add/change:** `listBillsOfMaterial`, `getBillOfMaterial`, mappers, service registration/proxy and handler delegation.
- **Implementation shape:** Parameterized filters and allowlisted sort; matching count/data; detail lines ordered stably. Other seven handlers remain 501.
- **Validation:** Compile/codegen, repository/HTTP/contract/security tests, pair verifier.
- **Stop condition:** Authorized reads return 200/404 and no longer advertise 501; every mutation remains a stub.

#### Chunk 4: Draft Create And Header Update Slice
- **Goal:** Create idempotent draft BOMs and update only safe draft identity.
- **Files to change:** Manufacturing repository/service/handler, focused tests and corresponding OpenAPI/Bruno availability notes.
- **Symbols to add/change:** `createBillOfMaterial`, `updateBillOfMaterial`, product/version validation, command claim/store, actor mapping.
- **Implementation shape:** Transactional create/replay; DRAFT/no-work-order locked PUT; exact product/version uniqueness and active STOCK checks. No line or lifecycle behavior.
- **Validation:** Compile, duplicate/replay/changed-payload/concurrent-create and rollback tests; HTTP/security/contract pair checks.
- **Stop condition:** Create/update implemented and safe; line/lifecycle endpoints remain 501.

#### Chunk 5: Draft Line Management Slice
- **Goal:** Add, replace and remove component lines without direct DB edits.
- **Files to change:** Manufacturing repository/service/handler, focused tests and three line OpenAPI/Bruno availability notes.
- **Symbols to add/change:** `addBillOfMaterialLine`, `updateBillOfMaterialLine`, `removeBillOfMaterialLine`, server SKU/numeric/uniqueness helpers.
- **Implementation shape:** BOM→line locking, DRAFT/no-work-order guard, active STOCK component lookup, immutable component identity, idempotent add/repeatable remove. No activation yet.
- **Validation:** Compile; repository/HTTP tests for all numeric/product/state/uniqueness cases and concurrent add; contract/pair checks.
- **Stop condition:** Draft lines are fully manageable; active/deprecate remain 501.

#### Chunk 6: Activation And Deprecation Lifecycle Slice
- **Goal:** Safely activate one acyclic immutable BOM version and preserve history.
- **Files to change:** Manufacturing repository/service/handler, lifecycle/concurrency tests and activation/deprecation OpenAPI/Bruno availability notes.
- **Symbols to add/change:** `activateBillOfMaterial`, `deprecateBillOfMaterial`, approved graph-serialization/lock contract, readiness/cycle query, atomic previous-version deprecation and events. Exact helper names are confirmed in Chunk 0.
- **Implementation shape:** Implement the approved graph and unit-stability prerequisites before enabling activation; split an additional compile-safe prerequisite slice if needed. Revalidate under the common protocol; one transaction for old/new statuses, audit and response. Repeat-safe transitions; no reactivation.
- **Validation:** Compile; direct/indirect/concurrent-cycle tests, simultaneous version activation, work-order-reference preservation, security/contract/pair regressions.
- **Stop condition:** No BOM endpoint remains 501; database and application invariants agree.

#### Chunk 7: Integrated Acceptance And Documentation
- **Goal:** Prove Task 05.4 end to end and update only evidence justified by results.
- **Files to change:** Manufacturing/OpenAPI READMEs, API inventories/counts, verification/authentication docs, Phase 05 Task 05.4 evidence/checklist and integrated tests; CI only if current jobs omit the fifth bundle/tests.
- **Symbols to add/change:** Lifecycle acceptance coverage and accurate documentation, not new business behavior.
- **Implementation shape:** Create draft/version, add/update/reorder/remove lines, activate, query, create/activate replacement, observe prior deprecation and reject immutable/cyclic changes.
- **Validation:** All Section VII targeted checks, then full Gradle test/build, migration/OpenAPI verification, XML skip inspection, final diff/security review and approved Bruno smoke if available.
- **Stop condition:** Task 05.4 done criteria are evidenced; external/deployment acceptance remains explicit. Stop before Task 05.5.

### IX. Handoff to `chunked-implementation`

Recommended agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, safe-python-edit, and post-edit-discipline if available.

Task:
Phase 05 Task 05.4, docs/implementation-plan/ads/phase-05-task-4.md.

Mode:
Execute Chunk 0 only. Do not edit files. Confirm sequencing authorization,
operation and lifecycle contracts, version/line/UOM/nested-cycle semantics,
migration head, proxy conventions and validation environment. Report blockers
and stop.
```

After Chunk 0 and all contract/lifecycle decisions are accepted:

```text
Use the chunked-implementation skill.
Execute Chunk 1 only from docs/implementation-plan/ads/phase-05-task-4.md.
Do not continue to Chunk 2. Keep every BOM operation as an authenticated 501
stub. After editing, run targeted validation and show git diff. Report risks,
skips and stop.
```

### X. Conclusion and Next Steps

This design treats BOMs as versioned, immutable production definitions rather than editable rows used in place. Draft-only edits, product/version and line constraints, atomic active-version replacement, actor/lifecycle evidence and cycle-safe activation give Task 05.5 a stable BOM identity without implementing work orders early.

Approve sequencing, the operation matrix, lifecycle/version policy, nested-BOM/UOM semantics and idempotency/audit persistence; then execute Chunk 0. Implementation must stop after Task 05.4 acceptance. Work orders, production runs and manufacturing inventory movements remain Tasks 05.5 and 05.6 and require separate authorization.
