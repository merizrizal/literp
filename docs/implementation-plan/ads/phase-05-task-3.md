## Architectural Design Specification: POS Receipt Issuance And Refund Adjustments

**Source:** [Phase 05, task 05.3](../05-pos-manufacturing-expansion.md#053-receipt-and-refund-api), following [05.2 POS Terminal And Shift API](phase-05-task-2.md).

**Status:** Partially implemented: Chunks 1–3 supplied write contracts/stubs, receipt/refund persistence and both scoped receipt reads at `1ed2617`. Receipt issuance and refund writes remain authenticated `501 NOT_IMPLEMENTED`. This documentation review does not approve remaining financial decisions or deployment. Original proposal/evidence text below is retained as drafting history where superseded by these completed slices; confirm current code before continuation.

**Continuation boundary:** Do not recreate migration `c7a8e2f4d6b1_pos_receipt_refund_foundation.py`, duplicate registered operations, or rerun completed chunks. Reconcile remaining decisions, then authorize the next unfinished chunk separately. See [coverage and requirements review](../ADS_COVERAGE_REVIEW.md).

**Evidence revision:** `f3c7e15`, branch `phase-05-task-3`, tracking `origin/phase-05-task-3`; working tree was clean before ADS creation. No fetch was performed.

**Goal:** Issue immutable receipts from fulfilled, attributed POS orders; replace the two authenticated lookup placeholders with location-scoped reads; and record idempotent, auditable payment refunds as append-only receipt adjustments that reconcile cash against the shift where the refund occurs.

---

### I. Overview and Contract

Task 05.3 extends the existing POS adapter rather than creating a second order lifecycle. Drafting, line changes, confirmation, payment capture, fulfillment, attribution, and shift closure remain owned by the existing Order Process and POS 05.2 behavior. Receipt issuance snapshots completed sale facts. Refunds record financial adjustments; they do not cancel a fulfilled order, reverse inventory, or call an external payment processor.

#### Existing concrete operations

| Method/path | operationId | Capability | Resource boundary | Current state |
|---|---|---|---|---|
| GET `/api/v1/pos/receipts/by-number/{receiptNumber}` | `getPosReceiptByNumber` | `pos.receipt.read` | Receipt → sales-order location | Implemented scoped read |
| GET `/api/v1/pos/orders/{salesOrderId}/receipts` | `listPosReceiptsBySalesOrder` | `pos.receipt.read` | Persisted sales-order location | Implemented scoped read |

The existing `PosReceipt` contract exposes the persisted receipt columns, permits a nullable historical `shiftId` and `receiptData`, bounds receipt numbers to 50 characters, and specifies paginated deterministic order lookup. These names and paths are concrete. Implementation must remove `501` only from operations that have working scoped behavior.

#### Registered write contracts; behavior still pending

The following paths, operation IDs and capabilities were published in Chunk 1. Remaining behavior/financial decisions require confirmation before replacing their 501 responses.

| Method/path | Registered operationId | Registered capability | Resource boundary |
|---|---|---|---|
| POST `/api/v1/pos/orders/{salesOrderId}/receipts` | `generatePosReceipt` | `pos.receipt.write` | Persisted order location |
| POST `/api/v1/pos/receipts/{receiptId}/refunds` | `createPosReceiptRefund` | `pos.refund.create` | Receipt → order location; refund shift → terminal location |

1. **Receipt eligibility:** issuance requires an existing `FULFILLED` order with `sales_channel = POS` and immutable `pos_order_context`. It does not require the original shift to remain open: 05.2 deliberately permits fulfillment after close. A missing or hidden order is 404; an unfulfilled, unattributed, or non-POS order conflicts.
2. **One API-issued sale receipt:** issue at most one API sale receipt per order. Historical/seed duplicates remain readable and are not rewritten. A durable database marker and uniqueness rule distinguish a new API-issued receipt from legacy rows. A matching idempotency replay returns the original 201 response; a different key after issuance conflicts rather than printing another financial document. Reprint behavior is out of scope.
3. **Receipt number:** allocate a unique server number from a PostgreSQL sequence, not `MAX + 1`, formatted within the existing 50-character limit using a UTC date and sequence (proposed `RCPT-YYYYMMDD-<sequence>`). The UUID receipt ID remains the durable identity.
4. **Immutable snapshot:** the issued receipt snapshots order number, location, currency, lines, captured payments, original shift, draft cashier, issuing actor, and timestamps in `receipt_data`. Persisted `subtotal` equals the current sum of line totals, `tax_amount` is explicitly zero because no tax model exists, and `total_amount` equals the fulfilled order total. Reject inconsistent totals rather than silently adjusting them.
5. **Item quantity decision:** existing seed data treats `total_items` as summed quantity, while order quantities support three decimal places and the receipt column/schema are integer. Proposed resolution: migrate `receipt.total_items` to `numeric(12,3)` and change `PosReceipt.totalItems` to a nonnegative decimal with `multipleOf: 0.001`. Chunk 0 must approve this or define a truthful alternative; line count must not be mislabeled as item quantity.
6. **Currency:** add nullable legacy-compatible receipt currency storage; every API-issued receipt stores the order currency. Historical rows may expose `currency: null`; never infer currency from current configuration.
7. **Refund request:** require `paymentId`, positive two-decimal `amount`, `shiftId`, bounded nonblank `reason`, and `Idempotency-Key`. Derive actor and organization from the verified principal. The selected payment must belong to the receipt order and have been captured. The refund amount may not exceed that payment's remaining unrefunded amount.
8. **Refund operator/shift:** refunds require a human owner of an open, reconcilable shift with `pos.refund.create`, matching location and currency. This may be a later shift, not the original sale shift. This requirement makes drawer impact explicit and prevents a receipt ID or shift ID from acting as an authorization grant.
9. **Append-only adjustment:** never overwrite original receipt totals or `receipt_data`. Insert an immutable refund row linked to receipt, payment, refund shift, verified actor, reason, amount, and currency. Receipt reads derive `refundedAmount`, `netAmount`, adjustment status (`ISSUED`, `PARTIALLY_REFUNDED`, `REFUNDED`), and an ordered adjustment list. Refund IDs and actor fields are server-owned.
10. **Payment state:** partial refunds leave the source payment `CAPTURED`; when cumulative refunds equal its amount, set it to `REFUNDED`. Shift reconciliation must still count the immutable original cash capture and separately subtract cash refunds attributed to the refund shift, avoiding double subtraction when payment status changes.
11. **Cash protection:** for a CASH refund, lock the refund shift and reject an amount exceeding its expected available drawer cash: opening balance plus original CASH captures attributed to that shift minus prior CASH refunds from that shift. Card/digital/gift/other refunds are local audited ledger records only; no external-provider settlement is claimed.
12. **No inventory return:** a refund does not restock items, reverse `OUT` movements, cancel the fulfilled order, or create a return authorization. Item-level returns, tax allocation, tender exchange, fees, and external gateway settlement require separate contracts.

**Function Signature Contract (Concrete):**

- `PosOperationsHandler.getPosReceiptByNumber(RoutingContext)` and `listPosReceiptsBySalesOrder(RoutingContext)` now delegate through `PosOperationsService` to scoped repository SQL. Only `generatePosReceipt` and `createPosReceiptRefund` call `respondNotImplemented`.
- `PosOperationsRepository.closePosShift(shiftId, closingBalance, idempotencyKey, actorSubject, organizationId, authorizedLocationIds): Single<JsonObject>` locks terminal then shift and snapshots expected cash.
- `PosOperationsService` is a Vert.x Java proxy returning `Future<JsonObject>`; `PosOperationsServiceImpl` adapts repository `Single<JsonObject>` values.
- `OrderProcessRepository.fulfillSalesOrder(orderId, createdBy, notes, idempotencyKey): Single<JsonObject>` requires `CONFIRMED`, fully captured value and fulfillable lines, then writes order/inventory state atomically.

**Function Signature Contract (Conceptual):**

- Repository reads: `getPosReceiptByNumber(receiptNumber, authorizedLocationIds)` and `listPosReceiptsBySalesOrder(salesOrderId, page, size, authorizedLocationIds)` return the existing object/list envelope payloads.
- Receipt command: `generatePosReceipt(salesOrderId, idempotencyKey, actorSubject, organizationId, authorizedLocationIds)` returns the issued receipt.
- Refund command: `createPosReceiptRefund(receiptId, paymentId, amount, shiftId, reason, idempotencyKey, actorSubject, organizationId, authorizedLocationIds)` returns the refund and updated receipt adjustment summary.
- Java proxy methods mirror only confirmed repository values using scalar values plus `JsonArray` location grants. Exact nullability and names require codegen confirmation in Chunk 0.

Temporary write-operation stubs must return explicit `501 NOT_IMPLEMENTED` through the existing handler path. A repository stub, if introduced before wiring, returns a failed asynchronous result with a fixed unavailable error; it must never return success or reserve an idempotency key.

### II. Observed Evidence and Assumptions

#### Observed evidence

| Evidence read | Design implication |
|---|---|
| `docs/implementation-plan/05-pos-manufacturing-expansion.md`, Task 05.3 | Requires fulfilled-order issuance, both lookups, refund/adjustment behavior, POS integration tests, and completed Bruno requests |
| `docs/implementation-plan/ads/phase-05-task-2.md` | Refunds must use a separate cash adjustment/ledger semantic and must not mutate closed reconciliation snapshots |
| `api_collections/open_api_spec/pos-operations.yaml` | Two lookup paths, schemas, pagination, envelopes, parameters and operation IDs already exist; generation/refund paths do not |
| `PosOperationsHandler.kt`, receipt methods | Both reads are safe 501 placeholders with no service calls |
| `PosOperationsService.java` and `PosOperationsServiceImpl.kt` | Existing Vert.x proxy has terminal/shift methods only; any receipt signature affects codegen and test fakes |
| `SecurityPolicy.kt` and `PosScopeHandler.kt` | Read capability/scope policy exists, but receipt scope is not dispatched by `PosScopeHandler` and placeholder routes skip it |
| Initial migration, receipt/payment tables | Receipt has order/nullable shift/number/date/totals/JSON but no currency, issuer, API issuance marker, refund relation or refund amount ledger; payment supports `REFUNDED` status only |
| `OrderProcessRepository.kt` | Order total is the sum of line totals; fulfillment requires captured value and writes stock `OUT`; payments may be split; attributed captures persist `pos_payment_context` |
| `PosOperationsRepository.closePosShift` | Current expected cash sums only `CAPTURED` CASH payments; refund work must revise this query before CASH refunds are exposed |
| Seed migration | Seed receipt uses quantity 2 as `total_items`, zero-independent tax data and a sensitive cashier/items JSON snapshot |
| POS contract/auth tests | Exactly two operations and Bruno files are currently expected to remain placeholders; test inventories must evolve atomically with availability |
| Handoff/05.2 acceptance evidence | 39 of 41 operations are implemented; only these receipt lookups remain 501; approved Bruno runtime smoke remains pending |

#### Assumptions and proposed decisions

- PostgreSQL remains the authoritative transactional store; no payment gateway or fiscal printer integration is available.
- A refund is an internal completed ledger event, not proof that an external acquirer returned funds. API/docs must state this plainly.
- New API-issued receipt snapshots are immutable. Derived adjustment summaries may change only by appending refund rows.
- Refunds require an open operator-owned shift even for noncash methods, providing one consistent audit boundary. Approval is required because the plan does not state this.
- The original receipt remains the sale document; refunds are adjustments, not replacement receipts. If credit-note documents are legally required, their numbering/schema must be designed before implementation.
- API issuance accepts service principals only if maintainers explicitly approve it; refunds remain human-only. Default implementation should fail closed while eligibility is unresolved.

#### Open confirmations for Chunk 0

- Approve the two proposed paths, operation IDs, capabilities, payloads and response additions.
- Approve one API-issued receipt per order, sequence format, zero-tax behavior, fractional `totalItems`, immutable snapshot fields and legacy-row handling.
- Approve payment-level partial refunds, open refund-shift ownership, local-only noncash semantics, cash availability guard, and no automatic inventory reversal.
- Confirm whether refund reason is mandatory and whether any supervisor override is required; none is designed by default.
- Resolve payment overcapture versus receipt adjustment semantics: existing noncash payments can exceed the order total, while the proposal caps refunds per payment. Decide whether receipt `refundedAmount`/`netAmount` includes excess-tender refunds and how status is derived; do not silently clamp a negative net or label an over-refund as a normal sale reversal. Add split/overcaptured payment tests before enabling refunds.
- This design does not handle refund-before-cancel for a paid but unfulfilled order because receipt eligibility requires FULFILLED. Document that recovery gap rather than promising that 05.3 unblocks all paid-order cancellation.
- Confirm the exact current Alembic head, proxy codegen behavior, all `PosOperationsService` test doubles, and an approved disposable database/Python environment before migration execution.

### III. Required Technical Dependencies and Imports

Reuse existing PostgreSQL/Alembic, Vert.x 5 Java proxy/codegen, RxJava `Single`/`Completable`, `JsonObject`/`JsonArray`, SQL client transactions, Java `BigDecimal`, UUID/time APIs, JUnit, authenticated principal adapters, `BaseHandler`, `PosScopeHandler`, and the POS command ledger. No new runtime dependency or external service is proposed.

Expected existing integration files include:

- `src/main/kotlin/com/literp/repository/PosOperationsRepository.kt`
- `src/main/java/com/literp/service/pos/PosOperationsService.java`
- `src/main/kotlin/com/literp/service/pos/impl/PosOperationsServiceImpl.kt`
- `src/main/kotlin/com/literp/verticle/handler/PosOperationsHandler.kt`
- `src/main/kotlin/com/literp/verticle/handler/PosScopeHandler.kt`
- `src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt`
- `src/main/kotlin/com/literp/security/SecurityPolicy.kt`
- the POS OpenAPI YAML/JSON pair, README, Bruno collection and focused tests.

Proposed additive persistence is one Alembic revision after the confirmed head. It may add a receipt-number sequence, legacy-compatible receipt metadata/type changes, an API-issuance uniqueness marker, and an append-only refund table with restrictive foreign keys and indexes for receipt/payment/shift lookups. Do not modify initial or seed migrations. Prefer check constraints for positive bounded amounts/currency and a partial unique constraint for one API-issued sale receipt per order. Exact names remain subject to Chunk 0 repository confirmation.

### IV. Step-by-Step Procedure / Execution Flow

#### Shared ingress, scope and lock discipline

1. Authenticate and require the exact operation capability. Empty location grants deny. Pass a defensive copy of authorized location IDs and handler-derived principal identity; never accept actor, organization, refund status, receipt number or generated totals from the body.
2. Resolve receipt/order scope through persisted `sales_order.location_id`. Missing and out-of-scope IDs share the same 404. Apply authorization before returning an idempotent replay.
3. Receipt reads parameterize identifiers and apply location grants in SQL. Order-list count and data queries use identical predicates and deterministic `receipt_date, receipt_id` ordering.
4. Receipt issuance locks the sales order, verifies POS attribution/fulfilled state and snapshot consistency, then claims command idempotency, allocates the number, writes receipt/issuance metadata and stores the original response in one transaction.
5. Refund lock order is terminal → refund shift → sales order → payment → receipt → POS command-ledger record. Recheck location, currency, owner, open state, payment ownership and remaining refundable amount under those locks. This preserves the 05.2 terminal/shift/order order and serializes refund versus close.
6. Shift close uses terminal → shift locking and aggregates original attributed CASH captures with status `CAPTURED` or `REFUNDED`, then subtracts completed CASH refunds whose refund shift is the closing shift. A refund and close cannot both win after the same shift-state observation.

#### Receipt issuance

1. Validate `salesOrderId` and bounded `Idempotency-Key`; derive actor/organization/grants.
2. In one transaction, lock the order and load `pos_order_context`, lines, payments and original shift metadata.
3. Require `FULFILLED`, `POS`, trusted attribution, nonempty fully fulfilled lines, exact order/line total consistency, and captured-or-refunded original payments covering the sale total.
4. Resolve matching command replay before attempting a second issuance. A changed request/key collision returns 409; a separately keyed second issue returns 409 under the API-issued uniqueness invariant.
5. Allocate the number from the sequence; snapshot exact decimal values and server timestamps; insert the receipt and issuance marker.
6. Store the same 201 payload in the POS command ledger and commit. A rollback leaves neither receipt nor completed command response.

#### Receipt lookup

- Number lookup validates decoded length 1–50, joins receipt → order, filters authorized locations, and returns one receipt with derived adjustment summary.
- Order lookup validates UUID/pagination, first hides missing/out-of-scope orders as 404, then returns all historical/API receipts with identical scoped count/data predicates.
- Refund details are ordered by creation time then refund ID. Sensitive `receiptData`, reasons and actor IDs remain business response data only for authorized callers and are never logged as telemetry.

#### Refund and adjustment

1. Validate IDs, positive two-decimal amount, reason length and idempotency key; require an eligible human principal.
2. Lock and verify the refund terminal/shift first, then the receipt order, selected payment and receipt. Require matching order, location and currency; source payment must represent a prior capture and have sufficient unrefunded amount.
3. Resolve authorized replay only after all current authorization/scope checks. Changed normalized payload conflicts.
4. For CASH, compute available drawer cash from original attributed captures minus prior refund outflows on this refund shift. Reject insufficient cash. Noncash records do not alter drawer totals and make no external-settlement claim.
5. Insert the append-only refund row. If cumulative refunds equal the payment amount, update payment status to `REFUNDED`; partial refunds retain `CAPTURED`.
6. Return the immutable refund plus the receipt's newly derived adjustment summary; store the response atomically in the POS command ledger.
7. Never alter order/line fulfillment, reservations, inventory movements, original receipt totals, original shift attribution, or a closed shift snapshot.

### V. Failure Modes and Resilience

| Stage | Failure Mode | Agent/System Action | Next State/Error Report |
|---|---|---|---|
| Contract approval | Paths, total-items, tax, refund-shift or provider semantics unresolved | Stop before schema/source work | Proposed ADS remains unimplemented |
| Migration preflight | Duplicate rows violate proposed API-issued uniqueness or invalid legacy totals prevent safe conversion | Abort with sanitized remediation requirement; never delete/rewrite financial history automatically | Migration blocked |
| Authentication | Missing/invalid bearer | Reject before resource lookup | 401 `UNAUTHENTICATED` |
| Authorization | Missing capability, empty grants, ineligible principal or non-owner refund shift | Reject before replay/mutation | 403 `FORBIDDEN` |
| Scope | Missing or hidden order/receipt/payment/shift | Preserve hidden-resource behavior | 404 `RESOURCE_NOT_FOUND` |
| Input | Invalid UUID, number length, amount precision/range, reason or key | Reject before writes | 400 `VALIDATION_ERROR` |
| Issuance | Order not POS/attributed/fulfilled, inconsistent totals, no lines, underpaid state or receipt already issued | Roll back; do not fabricate snapshot | 409 `CONFLICT` |
| Number allocation | Unique collision or sequence/storage failure | Roll back whole transaction; sequence gaps are acceptable | 409 for collision or sanitized 500 |
| Refund | Payment belongs to another order, zero/excess refund, currency mismatch, closed shift or insufficient drawer cash | Roll back; preserve original receipt/payment | 409 `CONFLICT` |
| Retry | Same scoped key with changed normalized payload | Return no prior payload | 409 `CONFLICT` |
| Refund/close race | Both inspect an open shift concurrently | Shared terminal/shift locks serialize; loser rechecks state | One commit; late refund gets 409 |
| Persistence | Timeout/deadlock/write error | Roll back all receipt/refund/ledger changes; retry only the whole transaction with a durable key and bounded policy | 503 `DB_TIMEOUT` or sanitized 500 |
| External provider | Caller assumes noncash refund reached acquirer | Contract labels result as local record; do not claim external success | Successful local refund record only |
| Partial delivery | Write endpoint remains a stub | Keep explicit authenticated placeholder | 501 `NOT_IMPLEMENTED`, no mutation |

Use existing public error codes. Proposed typed repository exceptions should map explicitly to validation/scope/conflict without exposing SQL, receipt snapshots, payment references or bearer data.

### VI. Security, Integrity, Idempotency, and Cleanup

- **Security:** authorization and persisted location scope precede replay/data return. Receipt IDs, payment IDs and shift IDs are selectors, never grants. Refund actors and issuer identity come only from verified principals. New operation IDs remain deny-by-default until exact policies and fixture capabilities are added.
- **Integrity:** preserve exact decimal arithmetic and database bounds. Receipt snapshots and refunds are append-only financial evidence. Use restrictive foreign keys; no cascade may erase receipt/refund audit history. Closed reconciliation snapshots remain immutable.
- **Idempotency:** use `pos_command_ledger` scoped by organization, actor, operation, target and key. Normalize generation target to order and refund target to receipt; fingerprint all client-controlled semantic fields. Reauthorize every replay. Response and mutation commit together.
- **Cash reconciliation:** count original cash inflows even after full refund status transition; subtract refund outflows by refund shift. Never retroactively recalculate a closed shift. Prevent cash refunds that would make expected drawer cash negative.
- **Privacy/logging:** do not log bearer tokens, full `receipt_data`, refund reasons, payment references, command response payloads, or SQL parameters. Log safe request ID, operation, actor, resource IDs and outcome under the pending audit-ownership limitations.
- **Cleanup/rollback:** transaction rollback removes partial rows and incomplete command claims. PostgreSQL sequence gaps after rollback are acceptable and must not be reused. Migration downgrade must not silently discard refund history; prefer forward repair or disabling routes once financial data exists.
- **Out of scope:** inventory returns, tax engine, credit-note/fiscal compliance, tender exchange, payment gateway calls, accounting journal integration, receipt rendering/printing/email, supervisor override and Task 05.4 manufacturing work.

### VII. Validation Strategy

Use Java 25 and an explicitly approved disposable PostgreSQL database. Before executing Python migration scripts, obtain and activate the user's approved Python virtual environment as required by repository workflow. Skipped database tests are not acceptance evidence.

- **Syntax/codegen:** `rtk proxy ./gradlew compileKotlin compileJava`; search all service implementers/fakes with `rtk grep -Rni 'PosOperationsService' src/main src/test` before changing proxy signatures.
- **Migration:** `rtk python -m py_compile <confirmed-new-revision>` and `rtk python scripts/verify_migrations.py` in the approved environment. Add upgrade assertions for fresh/legacy data, sequence, fractional totals, API-issued uniqueness, positive refund bounds, restrictive FKs and one Alembic head.
- **Repository tests:** `rtk proxy ./gradlew test --tests com.literp.repository.PosOperationsRepositoryTest`; cover scoped reads, snapshot consistency, retries, split payments, partial/full/excess refunds, cross-order payment rejection, and rollback.
- **HTTP/security tests:** `rtk proxy ./gradlew test --tests com.literp.verticle.AuthenticationHttpIntegrationTest --tests com.literp.security.SecurityPolicyTest`; cover 401/403/404, human eligibility, owner mismatch, hidden resources, issuer spoofing, long valid subjects and request IDs.
- **Contract tests:** `rtk proxy ./gradlew test --tests com.literp.contract.PosOperationsContractTest --tests com.literp.contract.OpenApiOperationIdRegistrationTest`; operation/policy/route/Bruno inventories must match and only unfinished operations may advertise 501.
- **OpenAPI parity:** `rtk python scripts/verify_openapi_assets.py` after approved environment activation; parse through the production router, not pair equality alone.
- **Concurrency:** independent connections/barriers for duplicate issuance, same/different-key issuance, two refunds against one payment, refund versus close, changed-payload replay and transaction rollback. Avoid timing-only sleeps.
- **Reconciliation regression:** prove original CASH capture plus same-shift full/partial refund yields net expected cash once; later-shift refund affects only the later open shift; noncash refund never changes drawer totals; prior closed snapshots do not change.
- **Core regression:** targeted order transaction and authenticated lifecycle tests prove fulfillment/inventory semantics remain unchanged and payment reads expose correct status. A final full `rtk proxy ./gradlew test` and `rtk proxy ./gradlew build` are warranted because proxy/public contracts and reconciliation change.
- **Bruno smoke:** complete requests with inherited auth and no secrets; run native smoke only when `bru` and an approved non-production token are available locally. Do not invent evidence if unavailable.
- **Formatting/diff:** no formatter task is currently documented; preserve repository Kotlin/Java/YAML/Python/Markdown style and use an existing formatter only if confirmed. Every chunk runs `rtk git diff --check`, `rtk git status --short`, and `rtk git diff -- <changed-files>` including untracked-file review.

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full feature in one pass.

Chunks 1–3 are recorded implemented; their former proposed paths/contracts must be reconciled with current code, not recreated. Remaining behavior symbols and decisions require explicit approval. Each behavior chunk includes its direct tests and ends compile-safe. Multi-file exceptions are called out where Vert.x proxy codegen, OpenAPI registration and route-policy parity make an atomic slice necessary.

#### Chunk 0: Discovery and Integration Confirmation
- **Goal:** Confirm task authorization and settle the receipt/refund business contract before edits.
- **Files to read:** This ADS; Phase 05 Task 05.3; 05.1/05.2 ADSs; current migration graph; receipt/payment/order/shift repositories and schemas; POS OpenAPI/Bruno/security/router/tests.
- **Commands:** `rtk git status --short --branch`; `rtk git log -1 --oneline`; `rtk grep -Rni 'receipt\|refund\|CAPTURED\|REFUNDED\|closePosShift' src python/database/migration/alembic/versions api_collections/open_api_spec`.
- **Evidence to confirm:** Explicit 05.3 implementation authorization; approved operation matrix/payloads; tax/item/currency semantics; refund shift/provider/inventory decisions; current Alembic head; proxy callers/fakes; disposable database and Python environment.
- **Stop condition:** Read-only approval/blocker report. Any unresolved financial semantic blocks Chunk 1.

#### Chunk 1: Authenticated Write Contracts and Compile-Safe Stubs
- **Goal:** Publish generation/refund contracts as safe deny-by-default placeholders before persistence exists.
- **Files to change:** POS OpenAPI YAML/JSON pair; `PosOperationsHandler.kt`; `HttpServerVerticle.kt`; `SecurityPolicy.kt`; `PosScopeHandler.kt`; POS contract/registration/security tests; two new Bruno skeletons. This is an inseparable contract-registration asset slice.
- **Symbols to add/change:** Proposed `generatePosReceipt`, `createPosReceiptRefund`, capabilities/scopes, handler 501 methods, route registrations and exact inventory assertions.
- **Implementation shape:** Validate/authenticate/policy-check then return 501; no service/repository calls or idempotency claims. Extend scope dispatch fail-closed and pass grants only; do not resolve data in stubs.
- **Validation:** Compile/codegen, POS contract, registration, security and authentication tests; OpenAPI pair verifier; credential scan.
- **Stop condition:** Both writes are documented authenticated 501s, existing reads remain 501, no database mutation path exists.

#### Chunk 2: Receipt And Refund Persistence Foundation
- **Goal:** Add legacy-safe storage/invariants without enabling HTTP behavior.
- **Files to change:** One new Alembic revision after the confirmed head; `scripts/verify_migrations.py` only if focused assertions belong there.
- **Symbols to add/change:** Receipt sequence/metadata and fractional item storage; API-issued uniqueness marker; proposed append-only refund table, checks, indexes and restrictive FKs.
- **Implementation shape:** Preflight unsafe legacy values; preserve historical receipt rows; no seed rewrite. Downgrade warns/blocks if needed to avoid silent financial-history loss.
- **Validation:** Python syntax, migration verifier and explicit fresh/legacy upgrade SQL assertions on the approved disposable target.
- **Stop condition:** One migration head; schema invariants proven; all HTTP operations still 501.

#### Chunk 3: Scoped Receipt Lookup Slice
- **Goal:** Replace both existing read placeholders with location-scoped behavior.
- **Files to change:** `PosOperationsRepository.kt`, `PosOperationsService.java`, `PosOperationsServiceImpl.kt`, `PosOperationsHandler.kt`, `PosScopeHandler.kt`, `HttpServerVerticle.kt`, focused repository/auth/contract tests, existing receipt Bruno files and POS OpenAPI pair. Multi-file exception is required for one generated proxy boundary and route availability parity.
- **Symbols to add/change:** Concrete lookup/list repository and service methods, row/adjustment mapping, pagination, authorized-location propagation; remove 501 only for the two reads.
- **Implementation shape:** Parameterized joins through sales-order location; identical count/data scope; deterministic ordering; legacy nullable metadata; no write behavior.
- **Validation:** Compile/codegen; POS repository, contract, registration, security and authenticated HTTP tests; pair verifier.
- **Stop condition:** Both lookups return scoped 200/404 results, hide foreign locations, and no longer advertise 501; writes remain placeholders.

#### Chunk 4: Fulfilled POS Receipt Issuance Slice
- **Goal:** Enable one idempotent immutable receipt from a fulfilled attributed POS order.
- **Files to change:** POS repository/service/handler files, generation route contract/Bruno request, and focused repository/HTTP/contract tests. Existing registration from Chunk 1 is reused.
- **Symbols to add/change:** Concrete `generatePosReceipt`, issuance snapshot/number helpers, command-ledger use and receipt response fields.
- **Implementation shape:** Lock order, validate eligibility/totals/payments, resolve replay, allocate sequence, insert snapshot/marker and store 201 response atomically. No refund logic.
- **Validation:** Compile/codegen; duplicate/concurrent issuance, replay/conflict/rollback tests; auth/scope and pair/Bruno parity tests.
- **Stop condition:** Generation works and its 501 is removed; exactly one API-issued receipt per order; refunds remain 501.

#### Chunk 5: Refund Ledger And Derived Adjustment Slice
- **Goal:** Implement refund transaction logic without exposing the endpoint yet.
- **Files to change:** `PosOperationsRepository.kt` and `PosOperationsRepositoryTest.kt`.
- **Symbols to add/change:** Conceptual refund method, refundable-total query, cash-availability calculation, payment status transition and derived receipt adjustment mapper.
- **Implementation shape:** Terminal→shift→order→payment→receipt→ledger locks; append one refund and original response atomically; CASH guard; local-only noncash record; no order/inventory mutation. Keep HTTP stub mounted.
- **Validation:** Repository tests for partial/full/excess/cross-order refunds, changed-key replay, owner/scope/currency/closed-shift denial, independent-connection races and rollback.
- **Stop condition:** Repository behavior is complete and tested, but clients still receive 501 for refunds.

#### Chunk 6: Refund API And Reconciliation Integration
- **Goal:** Expose refunds only after drawer reconciliation includes them correctly.
- **Files to change:** POS service interface/implementation, handler, `PosOperationsRepository.kt` close query, refund OpenAPI/Bruno assets, security/auth/contract/repository tests. Multi-file exception joins public proxy behavior with its required financial invariant.
- **Symbols to add/change:** Concrete `createPosReceiptRefund`, payload validation/mapping, updated `closePosShift` aggregation, remove refund 501.
- **Implementation shape:** Handler derives trusted actor/org/grants; service delegates; close counts original CASH captures in `CAPTURED` or `REFUNDED` state and subtracts refund-shift CASH outflows. Preserve completed close snapshots.
- **Validation:** Compile/codegen; refund/close concurrency and reconciliation tests; full POS contract/security/auth regressions; OpenAPI verifier.
- **Stop condition:** Refund endpoint is implemented, auditable and cash-correct; no 501 remains in Task 05.3 operations.

#### Chunk 7: Integrated POS Acceptance And Documentation
- **Goal:** Prove the complete sell→fulfill→receipt→refund/adjustment workflow and record only evidenced completion.
- **Files to change:** POS/order API READMEs, Bruno requests/environment nonsecret variables, Task 05.3 checklist/evidence, verification/authentication docs, integrated tests; CI only if existing jobs omit required checks.
- **Symbols to add/change:** End-to-end acceptance coverage and accurate availability/evidence, not new business behavior.
- **Implementation shape:** Authenticated operator opens shift, creates/fulfills attributed order, issues/looks up receipt, records partial/full refunds, verifies derived adjustments and closes refund shift with correct net cash. Include a later-shift refund and unchanged closed snapshot case.
- **Validation:** All Section VII targeted checks, then full Gradle test/build, migration and OpenAPI verification, report XML skip inspection, diff/security review, and approved Bruno smoke if tooling/token exist.
- **Stop condition:** Task 05.3 done criteria are evidenced, deferred external/provider claims remain explicit, and work stops before Task 05.4.

### IX. Handoff to `chunked-implementation`

**Historical prompts:** Chunks 1–3 are now implemented. Use these only as the original design record; continuation must inspect the current boundary and obtain authorization for the next unfinished chunk, not execute Chunk 1 again.

Original agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, safe-python-edit, and post-edit-discipline if available.

Task:
Phase 05 Task 05.3, docs/implementation-plan/ads/phase-05-task-3.md.

Mode:
Execute Chunk 0 only. Do not edit files. Confirm explicit implementation
authorization, operation/payload contracts, receipt snapshot semantics, refund
shift/provider/inventory decisions, migration head and validation environment.
Report blockers and stop.
```

After Chunk 0 and all financial/contract decisions are accepted:

```text
Use the chunked-implementation skill.
Execute Chunk 1 only from docs/implementation-plan/ads/phase-05-task-3.md.
Do not continue to Chunk 2. Keep all write routes as authenticated 501 stubs.
After editing, run targeted validation and show git diff. Report risks/skips and stop.
```

### X. Conclusion and Next Steps

This design makes receipt issuance a snapshot of the existing fulfilled POS order and makes refunds append-only adjustments instead of destructive receipt/order rewrites. It closes the 05.2 reconciliation gap by attributing cash outflow to the shift performing the refund while preserving already-closed snapshots.

Reconfirm the recorded Chunks 1–3 boundary and settle remaining issuance/refund financial decisions before authorizing the next unfinished chunk. Do not use the original Chunk 1 handoff above to replay completed work. Implementation must stop after Task 05.3 acceptance. Task 05.4 BOM work and any deployment/external-provider acceptance require separate authorization.
