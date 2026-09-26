## Architectural Design Specification: POS Terminal And Shift API

**Source:** [Phase 05, task 05.2](../05-pos-manufacturing-expansion.md#052-pos-terminal-and-shift-api), following [05.1 POS Operations Contract](phase-05-task-1.md).

**Status:** Proposed; design only. Requires approval of the decisions below and explicit confirmation that development may advance beyond 05.1. No deployment authorization.

**Evidence revision:** `e0b7fc5`, branch `phase-05-task-0`; clean and synchronized with the locally recorded upstream before ADS drafting. No fetch was performed.

**Goal:** Implement scoped terminal administration, shift open/current/close, trusted POS order attribution and auditable cash reconciliation without duplicating order/payment/fulfillment semantics.

---

### I. Overview and Contract

Use the eight terminal/shift operation IDs, paths, capabilities, schemas and response envelopes proposed in [05.1, Section I](phase-05-task-1.md#i-overview-and-contract). The two receipt operations remain safe placeholders until 05.3. This task neither generates receipts nor implements refunds/manufacturing/accounting.

#### Proposed behavior decisions requiring approval

1. **Terminal lifecycle:** Create active terminals at authorized existing locations. IDs/location/creation time are immutable. Update code/name only; code remains globally unique as in the existing schema. Deactivation is repeatable, never deletes history, and conflicts while an open shift exists. Opening and deactivation lock the same terminal row. No reactivation or relocation in this slice.
2. **Shift lifecycle:** One `OPEN` shift per terminal, enforced in PostgreSQL as well as application logic. Opening requires an active terminal, exact location grant, `pos.shift.open`, and `PrincipalKind.HUMAN`. Ownership is the verified subject; never infer it from `literp_operator`, a body field or a customer ID. Only the owner with `pos.shift.close` may close; supervisor/service overrides are out of scope. Read/admin operations use their exact capabilities and location grants, without implicit ownership grants.
3. **Currency/time:** Open requires a three-uppercase-letter currency, persisted for the shift; attributed orders must use the same currency. No conversion. UTC defines shift date and timestamps; allocate monotonically increasing `shiftNumber` per terminal/date under a terminal-row lock. Money fits numeric(14,2), nonnegative balances, positive captures, at most two fractional digits; reject overflow/extra precision instead of silently rounding.
4. **Optional explicit attribution:** Extend the existing `POST /orders` request with optional `posContext: {shiftId}`. No new POS draft/confirm/payment/fulfill routes. Without context, existing requests—including legacy orders defaulting to POS—retain behavior and return no invented attribution. With context, require `salesChannel=POS`, existing `order.write` and a proposed explicit `pos.order.use` capability, a human owner of an open shift, and matching order/terminal location and currency. A shift ID is a selector, never an authorization grant. Expose nullable read-only `posContext` on order responses; generated identity fields come only from the authenticated principal.
5. **Immutable snapshots:** Persist order→shift attribution and draft actor atomically with order creation. Record the verified payment actor and linked shift for captures on attributed orders, in the same transaction as payment/idempotency persistence. One payment belongs to at most one shift; never infer attribution from receipt existence or timestamp windows. Fulfillment continues using the existing authenticated `createdBy` behavior. Do not retrofit guessed attribution onto historical orders/payments.
6. **Attributed workflow commands:** Line editing, confirmation, payment capture and cancellation on an attributed order require the shift owner, open shift, matching grants and `pos.order.use` in addition to the original operation capability. Fulfillment may occur after close using existing order authorization and verified fulfillment actor; it does not change drawer cash. This avoids holding drawers open for noncash fulfillment while preventing later capture or cancellation from silently changing a reconciled shift. Non-attributed orders retain existing behavior. Explicitly approve this distinction before changing core command guards.
7. **Reconciliation:** `expectedCash = openingBalance + sum(attributed CAPTURED CASH payment amounts)`; card/digital/gift/other payments do not count. `cashVariance = closingBalance - expectedCash`; persist both totals plus counted closing balance, closed time and verified closer atomically. Accept nonzero variance and return it; no invented manager threshold. No cash movement, refund, paid-out, change-given or foreign-currency model is introduced. CASH capture amount means net drawer inflow, not tender offered; reject attributed CASH overpayment beyond the remaining order balance because change-given is not represented. Existing non-attributed capture semantics remain unchanged.
8. **Historical shifts:** Existing open shifts lack currency and trustworthy order/payment attribution. Do not silently reconcile them as zero-sales shifts. An approved migration/operator reconciliation procedure must establish trusted opening state or mark them legacy/non-reconcilable. Queries can expose legacy state; API close on non-reconcilable shifts returns 409 until remediated. Never fabricate currency or audited payment mappings from seed examples.

#### Persistence changes (proposed, additive)

Create a new Alembic revision after the confirmed current head; do not modify the initial/seed migrations. Exact revision ID and file name are selected in Chunk 0.

| Change | Contract and invariant |
|---|---|
| Widen `pos_shift.operator_id` to 255 | Matches verified `sub` bound; no truncation or forced UUID conversion |
| Extend `pos_shift` | Nullable legacy-compatible currency/reconciliation metadata, expected cash, variance, closed actor; distinguish new reconciliable shifts explicitly; new API-opened shifts always have currency |
| Shift constraints/indexes | Unique open shift per terminal; unique terminal/date/number; valid new-row amounts/state/timestamps; preflight duplicate/invalid legacy rows before adding constraints |
| Proposed `pos_order_context` | Unique sales-order FK, shift FK, verified draft operator (255); immutable mapping; order location/currency must match shift/terminal inside transaction |
| Proposed `pos_payment_context` | Unique payment FK, shift FK and verified capture operator; inserted only with attributed payments; indexed shift lookup for reconciliation |
| Proposed POS command ledger | Durable keys for terminal-create/shift-open/shift-close, operation/target/actor/organization scope, normalized fingerprint and original status/data, unique scope+key; completed response and mutation commit together |

Keep actor identity within the current single-issuer/deployment boundary. An issuer migration requires an explicit historical-identity migration, not automatic reinterpretation of subjects. New POS tables use explicit FKs with restrictive deletion; do not introduce cascading loss of cash/audit history. Do not add broad IAM/user tables.

**Function Signature Contract (Concrete):**

- `OrderProcessService.createSalesOrderDraft(String salesChannel, String locationId, String customerId, String currency, String notes): Future<JsonObject>` is the existing Java proxy contract.
- `OrderProcessService.capturePayment(String salesOrderId, String paymentMethod, String amount, String transactionRef, String idempotencyKey): Future<JsonObject>` carries no actor/shift today.
- `OrderProcessRepository.capturePayment(orderId: String, paymentMethod: String, amount: BigDecimal, transactionRef: String?, idempotencyKey: String): Single<JsonObject>` writes a captured payment and command response in `inTransaction`.
- `AuthenticatedActorAdapter.fulfillmentActor(principal: AuthenticatedPrincipal): String` returns verified subject. `OrderScopeHandler.authorize(operationId: String): Handler<RoutingContext>` binds scope checks to the authorized operation.

**Function Signature Contract (Conceptual):** proposed `PosOperationsRepository` methods mirror the eight operation IDs with validated IDs/payloads and trusted actor/grants; repository outputs are `Single<JsonObject>` consistent with existing repositories. Proposed `PosScopeHandler` resolves terminal/shift locations and scoped lists, but transactions recheck mutable business state. Proposed POS service interfaces use `Future<JsonObject>` and proxy-compatible scalar/JsonObject values, not a serialized Kotlin principal; handler-derived trusted context must not be overridable from JSON bodies.

Introduce only the minimal signatures needed by the current behavior slice. Unwired stubs return failed asynchronous results with a fixed temporary unavailable error; no empty success for a mutation. While dependencies are stubbed, retain the 05.1 HTTP 501 path. Create new service methods before or with callers; preserve existing Java methods through delegating overloads or distinctly named context-aware methods until all callers are migrated. Exact names/signatures require codegen confirmation in Chunk 0.

### II. Observed Evidence and Assumptions

| Evidence read | Design implication |
|---|---|
| Phase 05 plan, 05.2 Tasks/Done when | Requires terminal CRUD-like operations, current shift, attribution and cash reconciliation—not receipts/refunds |
| Initial migration `314b57a8dd0f_00_initial_migration.py`, lines 140–242 and enum declarations | Orders have currency/location/channel; payments have method/amount/status but no shift; shifts have OPEN/CLOSED and balances but no currency/snapshot; operator length is 36 |
| Same migration, receipt table | Receipt's nullable shift link cannot identify all shift payments |
| `OrderProcessRepository.kt`, lines 471–563 | Payment capture allows CONFIRMED/FULFILLED and sums all captured amounts; no drawer/actor linkage or overpayment guard |
| `OrderProcessHandler.kt`, draft/capture/fulfillment methods | Draft defaults to POS/USD; capture receives amount/method/ref/key; fulfillment uses authenticated actor adapter |
| `OrderProcessService.java`; `service/order/package-info.java` | Java Future proxy with separate ModuleGen package; new POS proxy requires its own confirmed module contract |
| `SecurityPolicy.kt`, principal and operation map | Bounded verified subject, exact capabilities, human/service distinction; new POS eligibility/scopes cannot be assumed |
| `OrderScopeHandler.kt`, scope dispatch/list behavior | Existing scope checks and hidden order IDs must remain intact; empty grants deny, lists must scope counts |
| `BaseHandler.kt`, success/error/query helpers | Existing envelope, request ID and pagination conventions; generic error mapper uses message heuristics, so POS failures need deliberate safe mappings |
| `build.gradle.kts` | Java 25, Kotlin 2.4.20, Vert.x 5.1.8, JUnit, RxJava and PostgreSQL client already available; no configured formatter task |
| `TestDatabase.kt` availability check; CI workflow test/migration steps; `verify_migrations.py` | DB tests may skip when unavailable; migration checker expects one head and seed evidence, not all POS invariants |
| Authentication/structure documents cited in 05.1 | No relocation, token issuance, untrusted exposure or automatic closure of external security gates |

Open confirmations: approval to implement 05.2 under the development exception; ownership/legacy/money decisions; current migration graph and real legacy anomalies; exact proxy signatures; shared connection/locking helpers and all command call sites; test DB/tooling availability. These are Chunk 0 gates, not implementation guesses.

### III. Required Technical Dependencies and Imports

Reuse PostgreSQL transactions/constraints, Alembic, Vert.x `Future`, RxJava `Single`, `JsonObject`, `SqlConnection`/prepared queries, Java `BigDecimal`, UUID/time APIs and JUnit fixtures. No new runtime library or external cash service is proposed.

Placement follows [PROJECT_STRUCTURE_DECISION.md](../../knowledge/PROJECT_STRUCTURE_DECISION.md): proposed Kotlin `repository/PosOperationsRepository.kt`, `verticle/handler/PosScopeHandler.kt`, existing/proposed `PosOperationsHandler.kt`, and `service/pos/impl/PosOperationsServiceImpl.kt`; Java `service/pos/PosOperationsService.java` plus `package-info.java` only when the first concrete service is introduced. Paths are relative to the existing `com/literp` source roots. Do not edit generated proxies or create empty package reservations.

Core order integration necessarily touches handler, Java proxy, Kotlin implementation and repository together when signatures change. Confirm every caller, test fake, registration and generated output through compilation; no unrelated layer refactoring.

### IV. Step-by-Step Procedure / Execution Flow

#### Shared ingress and locking discipline

1. Authenticate, check organization and exact operation capability, validate bounded input and resolve resource location from persistence. Missing/out-of-scope IDs have the same 404; explicit unauthorized location filters yield 403. Reject ineligible principals before business dispatch.
2. Pass only handler-derived subject/grants as trusted context. For attributed order commands, resolve immutable context and enforce the extra owner/open-shift rules. Reauthorize before any replay lookup; scope failure must not return a cached payload.
3. Proposed universal lock order for POS-linked writes: terminal → shift → order → command-ledger record. Resolve immutable IDs first, then lock and recheck relationships. Terminal-create uses the unique command-key claim and terminal-code constraint; no preexisting terminal row is available. No caller may acquire an order/ledger lock first and then wait for a terminal/shift lock. Audit all existing command transaction helpers before adopting this order.
4. Locking and unique constraints jointly serialize duplicate opens, deactivate/open, capture/close and concurrent retry attempts. Keep all writes, snapshots and replay payloads on the same connection/transaction. Roll back on errors; retry whole transactions only for bounded retryable database errors with a safe durable key, never an unkeyed draft mutation.

#### Terminal and shift operations

- List/get reads apply grants in SQL; list count uses identical predicates. Parameterize filters and allowlist sort columns.
- Create claims its idempotency key and inserts terminal atomically. Update locks the terminal, validates allowed fields and maps unique-code conflicts. Deactivate checks no open shift before marking inactive.
- Open locks terminal, verifies active/grants, authorizes actor, resolves same-key replay before attempting a second creation, ensures no open shift, allocates numbering, inserts shift and stores response atomically.
- Current-shift queries one open shift through its terminal location. Legacy currency/snapshot fields may be null, not guessed. No open shift is 404.
- Close locks terminal and shift, verifies scope/owner, and checks replay. A matching successful close replay may succeed even though state is now CLOSED; a new key on CLOSED conflicts. For a new close, require trusted reconciliation metadata, sum uniquely attributed CASH captures, persist counted/expected/variance/closer and CLOSED state with replay response. No receipt generation or stock movement.

#### Core order integration

- Context-bearing draft locks terminal/shift, checks ownership/open state/location/currency, inserts normal sales order and immutable POS context atomically. Context-less draft uses the existing path. Draft creation remains non-idempotent as currently contracted; warn callers not to blindly retry ambiguous failures. Do not introduce a hidden POS draft retry policy.
- Context-aware command guards cover add-line, confirm, capture and cancel. For capture, acquire the shared lock order before the existing order idempotency operation; include trusted attribution in its normalized fingerprint without exposing it as client input. Insert payment context exactly once in the payment transaction, even for noncash captures. Replays reauthorize and return original data without duplicate payment/context.
- After a shift closes, matching existing command replays may return their original result after owner/capability/scope reauthorization; reject new cash-affecting commands. This is a replay exception, not permission to reopen or recalculate a shift. Preserve existing replay behavior for non-attributed orders.
- Enrich authorized order reads/list rows with nullable read-only context without changing counts, pagination or lifecycle states. Later fulfillment retains the existing actor behavior and inventory ledger semantics.

### V. Failure Modes and Resilience

| Stage | Failure Mode | Agent/System Action | Next State/Error Report |
|---|---|---|---|
| Migration preflight | Duplicate open shifts/numbering or invalid legacy balances | Stop; report sanitized remediation requirement; never delete history automatically | Migration blocked |
| Authentication | Missing/invalid bearer | Reject before resource resolution | 401 `UNAUTHENTICATED` |
| Authorization | Wrong capability/org/kind or non-owner | Reject, no replay/data mutation | 403 `FORBIDDEN` |
| Scope | Missing/hidden terminal, shift or order | Same envelope for both | 404 `RESOURCE_NOT_FOUND` |
| Input | Invalid decimal, currency, read-only actor fields or mismatched context | Reject before writes | 400 `VALIDATION_ERROR`; relationship/state mismatch 409 `CONFLICT` |
| Terminal/open | Duplicate code, inactive terminal, open shift already exists | Unique constraint/locked state check; roll back | 409 `CONFLICT` |
| Retry | Same key, changed normalized payload | Reject; preserve original result | 409 `CONFLICT` |
| Capture/close | Race or capture into closed shift | Serialize through shared locks; new capture after close rejected | One consistent result; 409 for losing command |
| Close | Legacy untrusted state or already closed with new key | No fabricated totals or repeated close | 409 `CONFLICT` |
| Reconciliation | Nonzero variance | Persist counted and expected values, not an automatic failure | CLOSED with signed variance |
| Persistence | Timeout/deadlock/write failure | Roll back whole transaction, bounded safe retry where applicable | 503 `DB_TIMEOUT` for timeout; sanitized 500 `INTERNAL_ERROR` otherwise |
| Partial delivery | Dependency stub remains | Keep endpoint on explicit placeholder | 501 `NOT_IMPLEMENTED` (proposed), no mutation |

Use existing public error codes where possible. POS typed/domain failures are proposed internal symbols; map explicitly rather than adding sensitive SQL/exception text to `BaseHandler`'s generic message heuristics.

### VI. Security, Integrity, Idempotency, and Cleanup

- Grant filtering precedes mutation/replay; repository transaction checks eliminate time-of-check/time-of-use races on activity/shift state. Terminal locations and attribution are immutable through these APIs. Any direct writer/import must obey the same invariant or block acceptance.
- Use exact monetary arithmetic and checked database bounds. Persist reconciliation snapshot, not a mutable live aggregation on later close reads. Future refunds/adjustments must design a separate cash ledger or explicit adjustment semantics in 05.3; do not mutate closed snapshots implicitly.
- Preserve existing core order idempotency table/history. The proposed POS ledger handles terminal/shift commands only; do not reuse its sales-order-scoped FK for terminal creation. Unique scope includes operation, target (location for terminal create), organization and actor; keys/fingerprints do not contain credentials. Authorization is checked afresh on every replay.
- Never log bearer tokens, raw payment/receipt bodies, SQL parameter dumps or unbounded actor/client strings. Audit safe actor/operation/resource/request ID and outcomes; deployment audit ownership remains pending.
- Migration constraints preflight real/seed data; rollback must not truncate widened subjects or erase new financial history. Prefer disabling the new business routes and a forward repair over destructive downgrade. Test cleanup deletes only isolated test records in FK-safe order.
- No public networking changes, auth-disable flag, supervisor bypass, package migration or unrelated refactor.

### VII. Validation Strategy

Use a confirmed disposable PostgreSQL database and Java 25. Ask for/activate the approved Python environment before migration/verification commands; the prior handoff's environment path is evidence, not fresh permission to mutate a database.

- Syntax/codegen: `rtk proxy ./gradlew compileKotlin compileJava` on every source chunk. Search all signature callers with `rtk rg -n 'createSalesOrderDraft|capturePayment|PosOperations' src`.
- Proposed focused tests: `rtk proxy ./gradlew test --tests com.literp.repository.PosOperationsRepositoryTest`; `rtk proxy ./gradlew test --tests com.literp.verticle.PosOperationsHttpIntegrationTest`. Introduce these test classes in the corresponding slices before invoking them.
- Core regression: `rtk proxy ./gradlew test --tests com.literp.repository.OrderProcessRepositoryTransactionTest --tests com.literp.verticle.OrderProcessHttpIntegrationTest --tests com.literp.verticle.AuthenticationHttpIntegrationTest --tests com.literp.security.SecurityPolicyTest --tests com.literp.contract.OpenApiOperationIdRegistrationTest`.
- Migration: approved environment and explicitly disposable `DB_URL`, then `rtk python scripts/verify_migrations.py`; add POS migration assertions covering fresh and legacy upgrade, widening, unique open shift, duplicate preflight, nullable legacy state and single-head graph. Syntax-check the new revision with `rtk python -m py_compile <confirmed-new-revision-path>`.
- Contracts: `rtk python scripts/verify_openapi_assets.py`; validate both POS and order YAML/JSON schemas through the production router. Update Bruno context-bearing draft/capture examples without credentials. Authenticated external Bruno acceptance cannot be claimed from unit tests.
- Required adversarial/concurrency cases: missing token/capability/location, service open/close denial, spoofed actor, owner mismatch, hidden IDs, scoped counts, long valid subject, two simultaneous opens, open/deactivate, capture/close, duplicate same-key open/close/capture, changed-payload replay, transaction rollback, legacy close rejection, currency mismatch, decimal overflow/precision, cash overpayment, mixed payment methods and positive/negative/zero variance. Use independent DB connections/barriers, not timing-only sleeps.
- Verify old context-less POS/ONLINE/B2B/OTHER flows retain lifecycle, payment, reservation and inventory behavior; new context persists through reads and cannot be forged/reassigned. After close, new cash commands fail while authorized fulfillment and approved replays behave as specified.
- Formatting: follow existing Kotlin/Java/YAML/Python conventions; inspect available tooling at Chunk 0. No Gradle formatter is currently configured; do not claim a nonexistent format task. Record manual formatting checks if no approved tool exists.
- Per chunk: `rtk git status --short`, `rtk git diff --check`, `rtk git diff -- <changed-files>` plus new-file review. Report exact failures, skips, scope/risk and rollback note. DB tests that skip are not completion evidence. A final full Gradle test run is warranted here because core command/proxy contracts change, after targeted checks pass.

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full feature in one pass.

All new paths/symbols are proposed. Paths below use existing source-root conventions from Section III. Each chunk includes implementation and its direct tests, not a later all-tests phase. Multi-file behavior slices below are necessary to carry one operation through existing Java proxy/Kotlin layers; confirm the smallest exact file list in Chunk 0. If a slice becomes too large, split into unmounted contract/stub then one wired behavior subchunk with fresh approval; never leave broken callers for later work.

#### Chunk 0: Discovery and Integration Confirmation
- **Goal:** Approve task entry and resolve business/schema/transaction decisions.
- **Files to read:** Both POS ADSs; Phase 05 plan; authentication/structure references; migrations including current head; order handler/proxy/implementation/repository and transaction helpers; POS placeholders and tests from completed 05.1.
- **Commands:** `rtk git status -sb`; `rtk rg -n 'revision|down_revision' python/database/migration/alembic/versions`; `rtk rg -n 'inTransaction|loadCommandIdempotency|createSalesOrderDraft|capturePayment|cancelSalesOrder|confirmSalesOrder' src`.
- **Evidence to confirm:** 05.1 accepted, 05.2 development authorized, exact caller/lock graph, proxy codegen conventions, legacy remediation, approved database/venv and concrete validation commands.
- **Stop condition:** Read-only approval/blocker report; no schema/source edits while decisions remain open.

#### Chunk 1: Contracts and Compile-Safe Stubs
- **Goal:** Establish first terminal-read repository boundary without enabling behavior.
- **Files to change:** Proposed `repository/PosOperationsRepository.kt`; proposed `repository/PosOperationsRepositoryTest.kt` under test root.
- **Symbols to add/change:** Minimal list/get contracts and explicit unavailable failure.
- **Implementation shape:** Async failed-result stubs, constructor dependencies confirmed from repository conventions. Keep 05.1 routes unchanged; no Java POS proxy yet.
- **Validation:** Compile tasks and proposed repository test.
- **Stop condition:** Safe unavailable behavior compiles/tests, no runtime business route enabled.

#### Chunk 2: Scoped Terminal Read Slice
- **Goal:** Deliver list/get against existing terminal schema with grant-correct counts.
- **Files to change:** POS repository/handler, proposed POS scope handler, proposed concrete POS Java service and `package-info.java`, proposed implementation, `HttpServerVerticle.kt`, focused repository/HTTP tests. Multi-file exception creates the first real proxy and caller atomically.
- **Symbols to add/change:** `listPosTerminals`, `getPosTerminal`, proxy register/create methods, scoped terminal queries.
- **Implementation shape:** Implement only terminal read methods end-to-end; retain other placeholders. Do not serialize a principal through generated proxies without a confirmed compatible contract.
- **Validation:** Compile/codegen, proposed repository/HTTP tests, registration/security regression.
- **Stop condition:** Scoped list/get work, wrong grants reveal no rows/counts, other operations still 501.

#### Chunk 3: Terminal Administration Slice
- **Goal:** Create/update/deactivate safely with durable creation retries.
- **Files to change:** New additive Alembic revision (exact path chosen at Chunk 0), POS repository/service/handler files from Chunk 2, migration verifier assertions and targeted tests. Multi-file exception is the terminal mutation path plus its required ledger schema.
- **Symbols to add/change:** POS command ledger; three terminal command methods; sanitized conflict/error mappings.
- **Implementation shape:** Introduce terminal command ledger only; locked deactivate/open-shift check, immutable location, bounded updates and code uniqueness. Do not implement shift state transitions yet.
- **Validation:** Migration verification on disposable DB, compile, terminal mutation/retry/rollback HTTP and repository tests.
- **Stop condition:** Terminal commands complete; existing open shifts prevent deactivation; shift operations remain placeholders.

#### Chunk 4: Shift Opening and Current-Shift Slice
- **Goal:** Open/query one trusted new shift per active terminal.
- **Files to change:** New additive shift-invariant Alembic revision, POS repository/service/scope/handler, targeted migration/repository/HTTP tests. Multi-file exception joins constraints and actor-safe runtime behavior.
- **Symbols to add/change:** Shift actor widening, currency/trust/reconciliation fields and unique indexes; `openPosShift`, `getCurrentPosShift`.
- **Implementation shape:** Preflight legacy records, no guessed repair; human actor from trusted context; terminal lock, UTC numbering and durable open replay. Define storage for later close fields now but do not return fabricated totals.
- **Validation:** Migration checks; compile; concurrent-open/deactivate, human/service, owner, currency/precision and current-shift tests.
- **Stop condition:** New shifts open safely and can be queried; close remains 501.

#### Chunk 5: Trusted POS Draft Attribution Slice
- **Goal:** Attach an explicit shift to a normal order without duplicating the order workflow.
- **Files to change:** New additive attribution-table Alembic revision, order handler/Java service/implementation/repository, order scope/policy as needed, `order-process.yaml`/`.json`, draft Bruno example, targeted order/POS tests. This is the required atomic cross-layer/context contract slice.
- **Symbols to add/change:** `pos_order_context`, `pos_payment_context` storage, context-aware draft method and response mapping, `pos.order.use` checks.
- **Implementation shape:** Add optional context and immutable server actor in the same draft transaction; retain existing service method compatibility. Context-less orders unaffected. No payment context rows are guessed or backfilled.
- **Validation:** Migration/codegen/pair checks; core draft/read/list regression; spoofed/closed/foreign-shift/currency tests.
- **Stop condition:** Attributed draft/read path works; close remains unavailable until all cash writers are guarded.

#### Chunk 6: Attributed Order Commands and Cash Capture Slice
- **Goal:** Make cash sources trustworthy before exposing reconciliation.
- **Files to change:** Order handler/proxy/implementation/repository, shared POS transaction guard where confirmed necessary, order contract pair/Bruno docs for attribution rules, core/POS transaction and HTTP tests. Multi-file exception preserves signatures and transactional integrity of one attributed command path.
- **Symbols to add/change:** Context-aware command guards, payment actor/context persistence, attributed CASH remaining-balance guard and replay fingerprint.
- **Implementation shape:** Apply shared lock order to add/confirm/capture/cancel; authorize before replay, insert payment context with existing payment/response transaction. No close endpoint yet. Preserve context-less behavior and fulfillment semantics.
- **Validation:** Compile, core regressions, independent-connection concurrent captures, replay/no-duplicate context, rollback and legacy behavior tests; pair verifier.
- **Stop condition:** Every writer affecting attributed drawer totals obeys the guard; no late unguarded writer exists. Unconfirmed imports/direct writers block close implementation.

#### Chunk 7: Shift Close and Cash Reconciliation Slice
- **Goal:** Enable auditable close only after trusted capture is complete.
- **Files to change:** POS repository/service/handler, targeted POS/core concurrency tests; POS contract pair only for approved corrections, never silent scope expansion.
- **Symbols to add/change:** `closePosShift`, cash aggregation/snapshot mapping and close replay.
- **Implementation shape:** Locked authorized owner close, reject legacy untrusted state, exact sum and counted variance, atomic CLOSED snapshot plus original response. Test concurrent capture/close and same-key repeat.
- **Validation:** Compile, POS tests, core/auth/registration regression, contract verifier; explicitly verify zero skipped DB tests.
- **Stop condition:** New late captures fail; snapshots stable, close retries safe, nonzero variance recorded; receipts/refunds still unimplemented.

#### Chunk 8: Integrated Acceptance and Documentation
- **Goal:** Prove the complete eight-operation workflow without crossing into 05.3.
- **Files to change:** POS/order API READMEs, affected Bruno requests, POS HTTP/contract tests, Phase 05 completion evidence/checklists only after results justify them. Inspect CI workflow and change only if existing invocations omit new tests/migrations.
- **Symbols to add/change:** Lifecycle smoke coverage and verified evidence, not new business symbols.
- **Implementation shape:** Authenticated local terminal-create/open → attributed draft/line/confirm/payment → close and later fulfillment where authorized; verify drawer totals and actor linkage. Document legacy remediation and no-change-given limitations.
- **Validation:** All Section VII targeted checks then `rtk proxy ./gradlew test`; inspect test reports for skips; migration/pair verification, approved local Bruno smoke and final diff/security review.
- **Stop condition:** 05.2 criteria evidenced, no unsafe stubs among its eight operations, receipt placeholders documented, external security acceptance rows pending. Stop before 05.3.

### IX. Handoff to `chunked-implementation`

Recommended agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, safe-python-edit, and post-edit-discipline if available.
Task: Phase 05 task 05.2, docs/implementation-plan/ads/phase-05-task-2.md.
Mode: Execute Chunk 0 only. Do not edit files. Confirm accepted 05.1 contracts,
explicit 05.2 development authorization, legacy-data decisions, trusted actor
propagation and the transaction/lock graph. Report blockers and stop.
```

After Chunk 0 and the design decisions are accepted:

```text
Use the chunked-implementation skill.
Execute Chunk 1 only from docs/implementation-plan/ads/phase-05-task-2.md.
Do not continue to Chunk 2. Run targeted validation and show git diff.
Report risks/skips. Ask before writing a handoff or proceeding.
```

### X. Conclusion and Next Steps

This design keeps POS attribution explicit, cash totals tied to persisted payments, and sales semantics in the existing order pipeline. Approve the proposed ownership, currency, legacy-data, retry and cash limitations before implementation. Complete 05.1 and confirm 05.2 development authorization, then execute Chunk 0. Receipt generation/lookup/refunds remain task 05.3; deferred provider and deployment acceptance remains unchanged.
