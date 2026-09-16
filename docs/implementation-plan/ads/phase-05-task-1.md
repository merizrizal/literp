## Architectural Design Specification: POS Operations Contract

**Source:** [Phase 05, task 05.1](../05-pos-manufacturing-expansion.md#051-pos-operations-contract).

**Status:** Locally accepted and implemented as authenticated `501 NOT_IMPLEMENTED` placeholders. Task 05.2/05.3 behavior and external acceptance remain pending.

**Evidence revision:** `e0b7fc5`, branch `phase-05-task-0`; working tree clean and aligned with the locally recorded upstream before drafting. Remote freshness was not checked by fetching.

**Completion evidence:** The POS OpenAPI YAML/JSON pair, authenticated runtime registration, ten inherited-auth Bruno placeholders, publication documentation, and contract coverage were completed. The OpenAPI asset verifier, operation-registration test, security/authentication regression tests, and POS contract test passed in recorded acceptance validation. Native Bruno import/smoke validation remains unavailable because the installed desktop application cannot start its Linux sandbox helper. Genuine-provider, deployment, timed-JWKS, audit-ownership, and external authenticated acceptance remain deferred.

**Goal:** Publish a bounded POS terminal, shift, and receipt-lookup contract with Bruno placeholders, without duplicating core order behavior.

---

### I. Overview and Contract

POS is a sales-channel adapter. Task 05.1 specifies operations and safely registers contract placeholders; task [05.2](phase-05-task-2.md) implements terminals/shifts and order attribution. Task 05.3 implements receipt lookup/generation and refunds. Receipt generation/refund endpoints are not invented in this task.

#### Proposed operation matrix

All paths below are relative to `/api/v1`. Names, paths, capabilities, and payload decisions are **proposed**, not existing contracts. Each row needs a matching Bruno request under `api_collections/Literp`, using the operation ID as its traceability identifier in request documentation.

| Method/path | operationId | Capability | Resource boundary | Implemented in |
|---|---|---|---|---|
| GET `/pos/terminals` | `listPosTerminals` | `pos.terminal.read` | Authorized location set; scoped rows and count | 05.2 |
| POST `/pos/terminals` | `createPosTerminal` | `pos.terminal.write` | Submitted location grant | 05.2 |
| GET `/pos/terminals/{terminalId}` | `getPosTerminal` | `pos.terminal.read` | Persisted terminal location | 05.2 |
| PATCH `/pos/terminals/{terminalId}` | `updatePosTerminal` | `pos.terminal.write` | Persisted terminal location | 05.2 |
| POST `/pos/terminals/{terminalId}/deactivate` | `deactivatePosTerminal` | `pos.terminal.write` | Persisted terminal location | 05.2 |
| POST `/pos/terminals/{terminalId}/shifts` | `openPosShift` | `pos.shift.open` | Persisted terminal location; human principal | 05.2 |
| GET `/pos/terminals/{terminalId}/current-shift` | `getCurrentPosShift` | `pos.shift.read` | Persisted terminal location | 05.2 |
| POST `/pos/shifts/{shiftId}/close` | `closePosShift` | `pos.shift.close` | Shift → terminal location; owning human | 05.2 |
| GET `/pos/receipts/by-number/{receiptNumber}` | `getPosReceiptByNumber` | `pos.receipt.read` | Receipt → order location | 05.3 |
| GET `/pos/orders/{salesOrderId}/receipts` | `listPosReceiptsBySalesOrder` | `pos.receipt.read` | Persisted order location; scoped receipts | 05.3 |

Use a list for receipts by order: the existing schema does not enforce one receipt per order. The number lookup returns one receipt. Do not widen access through a nullable receipt shift: the order's location is authoritative.

#### Proposed payload and response contracts

- `PosTerminal`: `terminalId`, `locationId`, `terminalCode` (1–50 characters), `deviceName` (1–255), `isActive`, `createdAt`, `updatedAt`. IDs are UUID strings. Create requires location/code/name; server generates ID, activity and timestamps. PATCH allows code/name only, rejects empty patches and server-owned fields. Location is immutable; reactivation is out of scope.
- Terminal list: `page` defaults to 0, `size` defaults to 20 and is bounded 1–100; optional `locationId`, `isActive`; sort allowlist `terminalCode`, `createdAt` with `asc|desc`, default `terminalCode,asc`, stable ID tie-breaker. Count and rows share the same grant/filter predicate.
- Open request: `openingBalance` and `currency`; server derives `operatorId` from verified subject and generates shift ID/date/number/open time/status. Never accept an actor override. Currency and reconciliation fields require the additive schema work specified in 05.2.
- `PosShift`: existing shift fields rendered camelCase, plus proposed `currency`, `expectedCash`, `cashVariance`, `closedBy`; close-only fields are null while open. `closingBalance` means counted cash, not expected cash. UTC defines shift date and emitted date-times; `(terminalId, shiftDate, shiftNumber)` is unique, and numbering is allocated while holding the terminal lock.
- Close request: `closingBalance`; expected cash is server-calculated. No client-submitted expected totals or operator. Opening/closing balances are nonnegative decimal amounts fitting numeric(14,2); reject excessive fractional precision instead of rounding. JSON uses the existing numeric money convention; implementation converts through decimal strings/BigDecimal, never binary floating-point arithmetic.
- `PosReceipt`: existing receipt columns rendered camelCase; nullable `shiftId` and `receiptData`. Treat `receiptData` as potentially sensitive historical data, not public telemetry. Number lookup bounds the decoded number to 1–50 characters; order lookup uses UUID and bounded pagination, ordered by receipt date then ID.
- Resource success uses `{data: object}`; lists use `{data: [], pagination: ...}` following order-list conventions. Create/open return 201; reads/update/deactivate/close return 200. No current open shift returns 404, distinct from returning a fabricated empty shift.
- Every response preserves `X-Request-ID`; errors use existing `error`, `errorCode`, `status`, `errorId`. Document 400 validation, 401 bearer rejection with `WWW-Authenticate: Bearer`, 403 capability/eligibility denial, 404 missing or hidden resource, 409 lifecycle/uniqueness/idempotency conflict, 500 sanitized internal failure and 503 dependency timeout where applicable.
- Require bounded `Idempotency-Key` (proposed 1–128 characters) for terminal creation, shift opening and closing. Same authorized actor/operation/target/key and normalized payload returns the original status/data; changed payload conflicts. Deactivate is naturally idempotent but still conflicts if an open shift exists. PATCH is deterministic replacement of allowed values, not an increment command.
- Task 05.1 placeholders return explicit `501 NOT_IMPLEMENTED` (proposed code), never success or writes. Authentication and capability/eligibility checks execute first. A placeholder does not resolve resources or replay keys, exposes no existence information, and must not be described as implemented resource-scope enforcement. Replace it with scoped behavior atomically in 05.2/05.3.

**Function Signature Contract (Concrete):** `HttpServerVerticle.loadApiContracts(startFuture: Promise<Void>?)` loads three contracts; `configureOpenApiSecurity(routerBuilder: RouterBuilder)` binds bearer authentication. `ExplicitSecurityPolicy.require(principal, operationId)` returns an explicit allow, resource-scope requirement, or deny. `BaseHandler.putErrorResponse` provides the error envelope.

**Function Signature Contract (Conceptual):** proposed `PosOperationsHandler` placeholder methods accept the existing RxJava `RoutingContext` and return `Unit`; each emits a sanitized 501 through `BaseHandler`. No repository or Java service proxy is needed in 05.1. Proposed `registerPosOperationsHandlers(RouterBuilder)` binds every matrix operation, capability authorization, then the placeholder. Create the handler before its call sites. Extend policy scope declarations and human eligibility deliberately; existing exhaustive scope dispatchers must continue compiling and reject unsupported scope types.

### II. Observed Evidence and Assumptions

| Evidence read | Consequence |
|---|---|
| Phase 05 plan, Ordered Tasks 05.1–05.3 | 05.1 contracts/placeholders, 05.2 terminals/shifts, 05.3 receipt/refund behavior are separate deliverables |
| [Project structure decision](../../knowledge/PROJECT_STRUCTURE_DECISION.md), placement map/API policy | Retain Kotlin layers and existing API roots; coordinate new bundle consumers |
| Initial migration `314b57a8dd0f_00_initial_migration.py`, lines 203–242 | Terminal/shift/receipt foundations exist; operator length 36; no unique open-shift constraint or reconciliation snapshot |
| `HttpServerVerticle.kt`, lines 143–230 | Three contract builders, common authentication/router lifecycle; a fourth bundle needs loader changes |
| `OpenApiOperationIdRegistrationTest.kt`, entire file | Contract IDs, literal route registrations and business policy IDs must be equal; paths are hardcoded |
| `scripts/verify_openapi_assets.py`, bundle list and verification functions | Verifies three YAML/JSON pairs against Gradle version, not full semantic OpenAPI correctness |
| `order-process.yaml`, paths `/orders`; `Create-Order-Draft.bru` | OpenAPI 3.0.3, version 0.0.1, bearer inheritance, envelope/pagination and flat Bruno conventions |
| `SecurityPolicy.kt`; `OrderScopeHandler.kt`, authorization dispatch | Explicit operation policies, location filtering, hidden-resource behavior; no existing POS scopes |
| [Authentication baseline](../../knowledge/AUTHENTICATION_BASELINE.md), Status/Access-token contract/Acceptance ledger; [05.0 ADS](phase-05-task-0.md), status | Development exception permits 05.1, not deployment; external acceptance stays pending |
| Phase 04 plan, 04.6 and gate notes | Structure gate accepted; logging and external required-check enforcement remain separate |

Assumptions requiring approval: operation matrix, human-only shift commands, owner-only close (no supervisor override), UTC numbering, explicit currency, decimal precision, retry policy and nullable reconciliation fields. The 05.2 ADS owns persistence/attribution decisions; agree them before freezing the schemas. No task completion checkbox changes merely because this ADS exists.

### III. Required Technical Dependencies and Imports

Reuse Vert.x OpenAPI router, RxJava RoutingContext, `BaseHandler`, existing authentication/policy and JUnit. No new library or service infrastructure is proposed. Keep OpenAPI 3.0.3 and application version from `build.gradle.kts` (observed 0.0.1). Proposed bundle: `api_collections/open_api_spec/pos-operations.yaml`, `.json`, and `pos-operations-README.md`.

Update `scripts/verify_openapi_assets.py`, `OpenApiOperationIdRegistrationTest`, runtime loader, API README and Bruno coverage together by the end of this task. The existing `.github/workflows/foundation-verification.yml` invokes verification and Gradle tests; prove the fourth bundle is exercised without weakening assertions or introducing a parallel unchecked manifest.

### IV. Step-by-Step Procedure / Execution Flow

1. Resolve matrix and 05.2 cross-contract decisions; record approval, not assumed acceptance.
2. Draft paired assets with shared security, errors, bounded inputs, examples and explicit implementation availability.
3. Introduce unmounted, compile-safe handlers returning only 501.
4. Atomically connect loader, explicit policies and registration-test inventory. Preserve the existing routes, probes and failure handling. Fail startup if any contract fails to load.
5. Publish Bruno placeholders for all ten operations with `auth: inherit`, local variables and no credentials; ensure unimplemented responses cannot populate successful workflow IDs.
6. Validate semantic parsing with the production router as well as pair/version parity. Review cross-task attribution changes to the order contract before accepting 05.1.

### V. Failure Modes and Resilience

| Stage | Failure Mode | Agent/System Action | Next State/Error Report |
|---|---|---|---|
| Approval | Currency/ownership/attribution unresolved | Stop contract freeze | Proposed ADS; no claimed agreement |
| Publication | YAML/JSON drift or unresolved reference | Fail verification/parser test | No publication acceptance |
| Startup | Missing contract or bearer binding | Fail startup before listen | No partially loaded API |
| Authorization | Missing credential/capability or wrong principal kind | Reject before placeholder | 401/403; no resource access |
| Placeholder | Authorized operation not implemented | Return explicit safe error | 501 `NOT_IMPLEMENTED` (proposed) |
| Runtime implementation | Missing/out-of-scope resource | Shared hidden-resource response | 404 `RESOURCE_NOT_FOUND` |
| Registration | Operation, policy or Bruno request missing | Fail parity tests | Stop current chunk |

### VI. Security, Integrity, Idempotency, and Cleanup

No login, token minting, anonymous POS exception, automatic role expansion or operational `literp_operator` reuse as a cashier identity. Read grants do not imply write grants. Human-only commands require a new explicit eligibility rule; service tokens may read/administer terminals only with exact capabilities and location grants. Resource scoping is mandatory before any implemented data path or retry lookup.

Unknown operation IDs remain denied. Placeholders make no database writes, do not reserve keys and are not deployable feature success. Receipt operations stay placeholders until 05.3. Sanitized Bruno assets use existing empty token inheritance; never log payment/receipt bodies or bearer values. Preserve pending provider, deployment/JWKS, network and audit-ownership acceptance. Task 05.2 implementation requires its own approval; this document does not extend the development exception silently.

### VII. Validation Strategy

Run validation per chunk, not just at final publication:

- Syntax/build: `rtk proxy ./gradlew compileKotlin compileJava` for handler/policy/router chunks.
- Targeted parity: `rtk proxy ./gradlew test --tests com.literp.contract.OpenApiOperationIdRegistrationTest` after registration. Before mounting, maintain the existing three-bundle parity unchanged.
- Semantic/startup/security checks: add proposed `PosOperationsContractTest` under `src/test/kotlin/com/literp/contract`; `rtk proxy ./gradlew test --tests com.literp.contract.PosOperationsContractTest --tests com.literp.security.SecurityPolicyTest --tests com.literp.verticle.AuthenticationHttpIntegrationTest`. Test all ten routes: anonymous 401, capability denial 403, eligible authorized placeholder 501, stable envelopes/request IDs, no mutations. Use existing deterministic security fixtures; DB-dependent tests must report zero skips for acceptance.
- Pair/version validation: after confirming and activating an approved Python environment, `rtk python scripts/verify_openapi_assets.py`. The prior handoff records `~/Documents/PyEnv/myEnv/`; reconfirm before executing Python. Pair equality alone is not semantic validation.
- Proposed Bruno parity test checks method/path coverage, inherited auth, placeholder documentation and no duplicate/missing operations. Manually import/smoke in approved local environment when available; external authenticated Bruno acceptance remains pending until evidenced.
- Symbol checks: `rtk rg -n 'operationId:|getRoute\("|pos\.' api_collections/open_api_spec/pos-operations.yaml src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt src/main/kotlin/com/literp/security/SecurityPolicy.kt`.
- Formatting: preserve existing YAML/JSON indentation and Kotlin style. No formatter task is configured in the inspected Gradle file; do not invent one. Use an established project formatter if confirmed in Chunk 0; otherwise record manual formatting review.
- Every chunk: `rtk git status --short`, `rtk git diff --check`, and `rtk git diff -- <changed-files>`. Include new untracked file contents in review. Report failures/skips and stop.

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full feature in one pass.

New paths and symbols below are proposed. Each chunk must run Section VII's applicable syntax/format/diff checks, record risk and stop. More than two files are allowed only for the explicitly inseparable registration/publication slices below.

#### Chunk 0: Discovery and Integration Confirmation
- **Goal:** Confirm current gate, matrix decisions and 05.2 schema dependencies.
- **Files to read:** Phase 05 plan, both POS ADSs, authentication/structure decisions, loader, policy, initial migration, existing contract/test/Bruno files cited above.
- **Commands:** `rtk git status -sb`; `rtk git log -1 --oneline`; `rtk rg -n 'pos_|operationId|OPEN_API_FILES' python/database/migration/alembic/versions api_collections/open_api_spec src/test/kotlin/com/literp/contract`.
- **Evidence to confirm:** Current upstream/worktree, ten-operation agreement, safe stub registration, approved Python environment and tool availability.
- **Stop condition:** Read-only report; unresolved contract decisions block Chunk 1.

#### Chunk 1: Contracts and Compile-Safe Stubs
- **Goal:** Establish one safe caller boundary without runtime exposure.
- **Files to change:** Proposed `src/main/kotlin/com/literp/verticle/handler/PosOperationsHandler.kt`; proposed `src/test/kotlin/com/literp/contract/PosOperationsContractTest.kt`.
- **Symbols to add/change:** Ten handler methods named after the operation matrix; explicit 501 error response helper.
- **Implementation shape:** Unmounted handler methods use `BaseHandler`, no service imports or writes. Test that placeholders cannot report success.
- **Validation:** Compile tasks; `rtk proxy ./gradlew test --tests com.literp.contract.PosOperationsContractTest`.
- **Stop condition:** Compiles, tests pass, production routes/policies unchanged.

#### Chunk 2: First Thin Contract Slice — Terminal Assets
- **Goal:** Define terminal requests and responses as a valid unmounted bundle.
- **Files to change:** Proposed `pos-operations.yaml` and `.json` in the accepted OpenAPI root.
- **Symbols to add/change:** Five terminal operation IDs and their security/schema components.
- **Implementation shape:** Matching valid pair with terminal lifecycle/error examples; no new published runtime claim. Validate this pair explicitly before the verifier's registered bundle list changes.
- **Validation:** Semantic parse using the existing Vert.x parser in a targeted test/harness; approved-environment pair check using `verify_openapi_pair` with the Gradle version. Record exact commands in Chunk 0 once harness is confirmed.
- **Stop condition:** Terminal contract valid; existing registration parity still passes.

#### Chunk 3: Shift and Receipt Lookup Contract Slice
- **Goal:** Complete the agreed ten-operation matrix.
- **Files to change:** Same YAML/JSON pair.
- **Symbols to add/change:** Three shift and two receipt operation IDs and schemas.
- **Implementation shape:** Add reconciled 05.2 fields, owner attribution, idempotency, nullable receipt shift, paginated order receipt list and 05.3 availability notes.
- **Validation:** Repeat explicit pair and semantic validation from Chunk 2.
- **Stop condition:** Pair complete; no receipt/refund implementation implied.

#### Chunk 4: Authenticated Runtime Contract Registration
- **Goal:** Mount the complete bundle as authenticated placeholders without weakening parity.
- **Files to change:** `HttpServerVerticle.kt`, `SecurityPolicy.kt`, `OrderScopeHandler.kt` if exhaustive scope dispatch requires a deny branch, `OpenApiOperationIdRegistrationTest.kt`, `scripts/verify_openapi_assets.py`, and the proposed contract test. This multi-file exception is required because loader, policy and parity inventories must agree atomically.
- **Symbols to add/change:** POS builder/registration, exact policy entries, explicit human eligibility and POS scope declarations, bundle inventories and production-router tests.
- **Implementation shape:** Bind existing stubs; no persistence. Preserve all old operation policies. Do not dispatch POS scopes through order-list logic. Unsupported scope branches deny rather than fall through.
- **Validation:** All Section VII compile/parity/security/parser/pair checks; existing authentication regression.
- **Stop condition:** Every matrix row authenticated, policy-checked and returns 501 to eligible callers; all inventory equalities pass.

#### Chunk 5: Bruno and Publication Acceptance
- **Goal:** Make the contract discoverable and reproducible.
- **Files to change:** Ten proposed `Pos-*.bru` files, existing collection/environment only for nonsecret variables, proposed bundle README, existing OpenAPI README, proposed contract test. Publication requires one request per endpoint; this is an asset-only multi-file exception.
- **Symbols to add/change:** Request method/path/operation traceability, local terminal/shift/receipt/idempotency variables, coverage assertions.
- **Implementation shape:** Inherit bearer auth; clearly document placeholders. Reuse core order requests rather than duplicate confirm/payment/fulfill under POS. Record CI verification evidence; alter workflow only if its existing verification invocations fail to cover the new assets.
- **Validation:** Pair verifier, contract/security tests, Bruno coverage/import check, complete diff and credential review.
- **Stop condition:** Contract explicitly accepted, all ten Bruno placeholders present, 05.2/05.3 availability stated and external security rows still pending. Stop; no behavior implementation.

### IX. Handoff to `chunked-implementation`

Recommended agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, safe-python-edit, and post-edit-discipline if available.
Task: Phase 05 task 05.1, docs/implementation-plan/ads/phase-05-task-1.md.
Mode: Execute Chunk 0 only. Do not edit files. Confirm repository evidence,
contract decisions and the linked 05.2 dependencies, then stop.
```

After Chunk 0 and the proposed contract are accepted:

```text
Use the chunked-implementation skill.
Execute Chunk 1 only from docs/implementation-plan/ads/phase-05-task-1.md.
Do not continue to Chunk 2. Run targeted validation and show git diff.
Report risks and skipped checks. Ask before writing a handoff or continuing.
```

### X. Completion and Next Steps

Task 05.1 is complete for local contract publication: all ten POS operations are registered as authenticated `501 NOT_IMPLEMENTED` placeholders, with synchronized OpenAPI assets, Bruno requests, documentation, and recorded acceptance validation. This completion does not authorize 05.2/05.3 behavior implementation or close deferred deployment acceptance. Before starting 05.2, approve its terminal/shift persistence, attribution, reconciliation, and resource-scoping decisions. The next behavior design is [POS Terminal And Shift API](phase-05-task-2.md).
