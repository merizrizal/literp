## Architectural Design Specification: Phase 03 Fulfillment Movement Semantics, Deferred Payment Extensions, and Order Flow Verification

**Source:** `docs/implementation-plan/03-order-inventory-flow.md`, tasks `03.4`, `03.5`, and `03.6`

**Goal:** Finish Phase 03 by making sales fulfillment `OUT` movement semantics explicit, documenting receipt/refund/partial-flow ownership, and adding automated verification for valid and invalid order lifecycle transitions, inventory side effects, and payment guardrails.

---

### I. Overview and Contract

This ADS covers the remaining Phase 03 work:

- `03.4 Fulfillment Movement Semantics`
- `03.5 Payment, Receipt, And Partial Flow Design`
- `03.6 Order Flow Verification`

The design preserves the Phase 03 domain rule from the plan: **sales captures intent, inventory records physical state changes**. Reservations are earmarks; fulfillment remains the authoritative stock deduction event.

#### Movement semantics decision

Recommended Phase 03 decision:

- Sales fulfillment remains an immutable `inventory_movement` row with `movement_type = 'OUT'` and `reference_type = 'SALES_ORDER'`.
- `OUT` movement semantics should be explicit as: stock leaves `from_location_id`; there is no internal destination location.
- The current workaround `to_location_id = from_location_id` should be replaced for newly written sales fulfillment rows by allowing `to_location_id = NULL` for `OUT` rows.
- Stock rollup queries should continue deducting `OUT` rows by `from_location_id`, not by `to_location_id`.
- Existing historical/seed sales `OUT` rows using same-location source/destination should be normalized by migration or tolerated by rollup queries until normalized.

This is the narrowest schema-correcting approach because the existing schema already allows `from_location_id` to be nullable for inbound `IN` rows; making `to_location_id` nullable for outbound `OUT` rows gives symmetric semantics without introducing a fake sink location.

#### Payment, receipt, refund, and partial-flow decision

Recommended Phase 03 decision:

- Receipt generation is deferred to Phase 05 (`05.3 Receipt And Refund API`).
- Refund/payment reversal is deferred to Phase 05 (`05.3 Receipt And Refund API`).
- Partial fulfillment is explicitly deferred; current Phase 03 fulfillment is all-or-nothing for the remaining quantity on all fulfillable lines.
- Partial payment remains allowed only as multiple captured payment rows, but fulfillment requires captured total `>= sales_order.total_amount`; no partial-fulfillment unlock is added in Phase 03.

This keeps Phase 03 focused on core lifecycle correctness and leaves POS shift/receipt/refund orchestration to the POS expansion phase that already owns terminals, shifts, receipts, and refunds.

#### Existing concrete contracts

**Function Signature Contract (Concrete):** `OrderProcessService.getCurrentStock(String productId, String locationId): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.getAvailableStock(String productId, String locationId): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.confirmSalesOrder(String salesOrderId, String idempotencyKey): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.capturePayment(String salesOrderId, String paymentMethod, String amount, String transactionRef, String idempotencyKey): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.fulfillSalesOrder(String salesOrderId, String createdBy, String notes, String idempotencyKey): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessService.cancelSalesOrder(String salesOrderId, String reason, String idempotencyKey): Future<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessRepository.getCurrentStock(productId: String, locationId: String): Single<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessRepository.getAvailableStock(productId: String, locationId: String): Single<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessRepository.confirmSalesOrder(orderId: String, idempotencyKey: String): Single<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessRepository.capturePayment(orderId: String, paymentMethod: String, amount: BigDecimal, transactionRef: String?, idempotencyKey: String): Single<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessRepository.fulfillSalesOrder(orderId: String, createdBy: String?, notes: String?, idempotencyKey: String): Single<JsonObject>`

**Function Signature Contract (Concrete):** `OrderProcessRepository.cancelSalesOrder(orderId: String, reason: String?, idempotencyKey: String): Single<JsonObject>`

#### Proposed movement-schema contract

**Schema Contract (Conceptual):** `inventory_movement.to_location_id` becomes nullable so `OUT` rows can represent stock leaving the system/channel without a fake destination.

**Schema Contract (Conceptual):** Sales fulfillment `OUT` rows should satisfy `from_location_id IS NOT NULL`, `to_location_id IS NULL`, `movement_type = 'OUT'`, `reference_type = 'SALES_ORDER'`, `reference_id = sales_order_id`.

If implementation adds a check constraint, it must account for existing movement types:

- `IN`: `to_location_id` required, `from_location_id` may be null.
- `TRANSFER`: both locations required.
- `OUT`: `from_location_id` required; `to_location_id` should be null for sales fulfillment after normalization.
- `ADJUSTMENT`: preserve current behavior unless a separate adjustment-semantics task is created.

### II. Observed Evidence and Assumptions

Observed evidence:

- `docs/implementation-plan/03-order-inventory-flow.md` keeps the core rule: sales captures intent; inventory records physical state changes.
- `03.4` explicitly calls out the current `to_location_id = from_location_id` workaround and requires movement docs, seed/test updates if semantics change, and stock rollup verification.
- `03.5` asks either to implement or explicitly move receipt generation, refund/payment reversal, partial fulfillment, and partial payment rules.
- `03.6` asks for integration tests for valid transitions, invalid transitions, inventory movement side effects, and payment guardrails; Bruno examples are already marked aligned.
- `docs/knowledge/PROCESS_ORDER_PAYMENT_FULFILLMENT.md` states fulfillment creates `inventory_movement` entries and is the authoritative stock deduction event; cancellation creates no movement entry.
- `docs/implementation-plan/05-pos-manufacturing-expansion.md` already owns POS terminals, shifts, receipt lookup, receipt generation from fulfilled POS orders, refund endpoint behavior, and POS integration tests.
- `python/database/migration/alembic/versions/314b57a8dd0f_00_initial_migration.py` currently defines `inventory_movement.to_location_id` as non-null and `from_location_id` as nullable.
- `src/main/kotlin/com/literp/repository/OrderProcessRepository.kt` currently inserts fulfillment movements with both `from_location_id` and `to_location_id` set to the order location.
- The same repository's stock queries deduct `OUT` only when `from_location_id = locationId`; they do not add `OUT` by `to_location_id`.
- `src/test/kotlin/com/literp/repository/OrderProcessRepositoryTransactionTest.kt` currently has a stock test that inserts an `OUT` row using `from_location_id = to_location_id` and expects source stock to decrease.
- Seed/simulated data in `acf82479ef78_99_populate_seed_data.py` includes sales `OUT` rows with `to_location_id = from_location_id`.
- `api_collections/open_api_spec/order-process-README.md` says fulfillment writes `movement_type = OUT` and that receipt/refund lifecycle endpoints are not included yet.
- `docs/ENDPOINTS_OVERVIEW.md` still notes that fulfillment currently writes `from_location_id` and `to_location_id` with the same location.
- Current service/handler contracts require an `Idempotency-Key` header for confirm, payment capture, fulfill, and cancel.
- Existing tests already cover transaction rollback, idempotency, some stock calculations, insufficient stock, and audit events, but do not fully enumerate every valid/invalid state transition required by `03.6`.

Assumptions to confirm during Chunk 0:

- Alembic remains the correct mechanism for changing `inventory_movement.to_location_id` nullability.
- No downstream report depends on same-location `OUT` rows having a non-null `to_location_id`.
- No generated OpenAPI JSON process must be run beyond the existing repository convention for YAML/JSON parity.
- The test database can apply a new movement-semantics migration before running `OrderProcessRepositoryTransactionTest`.

### III. Required Technical Dependencies and Imports

Existing dependencies and integration points:

- Kotlin/JVM with Vert.x, RxJava 3, PostgreSQL client, and Gradle.
- PostgreSQL accessed through `io.vertx.rxjava3.sqlclient.Pool`, `SqlConnection`, and `Tuple`.
- Alembic migrations under `python/database/migration/alembic/versions`.
- OpenAPI assets under `api_collections/open_api_spec` with YAML/JSON parity.
- Bruno client assets under `api_collections/Literp`.
- JUnit/Kotlin tests under `src/test/kotlin` using `TestDatabase.assumeAvailable(pool)`.

Likely files for implementation:

- `python/database/migration/alembic/versions/<new_revision>_04_inventory_out_movement_semantics.py` (proposed filename; actual revision must be generated/confirmed during implementation)
- `src/main/kotlin/com/literp/repository/OrderProcessRepository.kt`
- `src/test/kotlin/com/literp/repository/OrderProcessRepositoryTransactionTest.kt`
- `docs/implementation-plan/03-order-inventory-flow.md`
- `docs/knowledge/MODEL_DESIGN.md`
- `docs/knowledge/PROCESS_ORDER_PAYMENT_FULFILLMENT.md`
- `docs/ENDPOINTS_OVERVIEW.md`
- `docs/API_IMPLEMENTATION.md`
- `docs/API_TESTING_GUIDE.md`
- `api_collections/open_api_spec/order-process.yaml`
- `api_collections/open_api_spec/order-process.json`
- `api_collections/open_api_spec/order-process-README.md`

No new external service dependency is expected for Phase 03 completion.

### IV. Step-by-Step Procedure / Execution Flow

#### 03.4 Fulfillment Movement Semantics

1. Confirm current movement insert and stock rollup behavior.
2. Add a migration that makes `inventory_movement.to_location_id` nullable.
3. Normalize existing sales fulfillment `OUT` rows where `to_location_id = from_location_id` to `to_location_id = NULL`, if safe.
4. Optionally add a conservative check constraint only if it does not break existing valid `IN`, `TRANSFER`, and `ADJUSTMENT` data.
5. Change fulfillment movement insertion so sales `OUT` rows pass `from_location_id = order.location_id` and `to_location_id = NULL`.
6. Update repository tests so sales `OUT` semantics are pinned with nullable destination and stock still deducts from source location.
7. Update docs/API assets to remove the workaround caveat and state the final semantics.

#### 03.5 Payment, Receipt, And Partial Flow Design

1. Document that receipt generation is deferred to Phase 05 because Phase 05 owns POS terminal/shift/receipt API and receipt lookup.
2. Document that refund/payment reversal is deferred to Phase 05 because cancellation after captured payment currently remains blocked until refund orchestration exists.
3. Document that partial fulfillment is deferred; Phase 03 fulfill remains all-or-nothing for order completion.
4. Document partial payment behavior precisely:
   - Multiple captured payment rows may exist.
   - Payment capture is allowed for `CONFIRMED` or `FULFILLED` orders by current contract.
   - Fulfillment remains blocked until captured total covers order total.
5. Link each deferred item to Phase 05 or a future explicit issue before marking `03.5` complete.

#### 03.6 Order Flow Verification

1. Inventory current test coverage in `OrderProcessRepositoryTransactionTest`.
2. Add valid transition tests for:
   - `DRAFT -> CONFIRMED -> FULFILLED`
   - `DRAFT -> CANCELLED`
   - `CONFIRMED -> CANCELLED` with no captured payment
   - multiple captured payments summing to full order total before fulfillment
3. Add invalid transition tests for:
   - confirm non-`DRAFT` orders
   - fulfill non-`CONFIRMED` orders
   - fulfill before full captured payment
   - cancel `FULFILLED` orders
   - cancel orders with captured payment
   - add line to non-`DRAFT` order
   - commands missing/blank idempotency keys at repository and/or handler level where appropriate
4. Add inventory side-effect tests for:
   - fulfill writes exactly one sales `OUT` movement per fulfilled line
   - movement rows have explicit `from_location_id` and null `to_location_id`
   - stock decreases by movement quantity after fulfillment
   - cancellation creates no movement rows
5. Add payment guardrail tests for:
   - zero/negative capture is rejected
   - duplicate idempotent payment retry does not add rows
   - conflicting idempotency key payload is rejected
6. Run targeted compile/test validation and review diff.

### V. Failure Modes and Resilience

| Stage | Failure Mode | Agent/System Action | Next State/Error Report |
|---|---|---|---|
| Migration | `to_location_id` nullability migration fails | Stop implementation; do not change repository insert logic | Schema remains unchanged; report Alembic/PostgreSQL error |
| Migration | Existing same-location `OUT` rows conflict with new check constraint | Normalize sales `OUT` rows before constraint or defer constraint | Historical stock remains queryable; report rows requiring migration |
| Fulfillment insert | Repository continues writing same-location destination after schema change | Tests fail on movement semantics assertion | No completion claim; fix movement insert to pass null destination |
| Stock rollup | Current stock query accidentally stops counting `OUT` rows with null destination | Stock side-effect test fails | Query must keep deducting by `from_location_id` |
| Fulfillment | Captured payment total is below order total | Reject before movement insert | Order remains `CONFIRMED`; error: `Insufficient captured payment for fulfillment` |
| Cancellation | Order has captured payment | Reject cancellation; no refund compensation attempted | Order remains existing status; error: `Cannot cancel order with captured payment` |
| Cancellation | Order is fulfilled | Reject cancellation | Order remains `FULFILLED`; error: `Cannot cancel a fulfilled order` |
| Partial fulfillment | Client expects line-level partial fulfillment | Do not silently partially fulfill; reject/avoid endpoint change | Phase 03 remains all-or-nothing; document deferral |
| Receipt | Client expects receipt after fulfill | Do not write partial receipt logic in order repository | Receipt/refund endpoints remain Phase 05; docs point to `05.3` |
| Idempotency | Same idempotency key with different payment payload | Reject conflict | No duplicate payment; error: `Idempotency key conflict` |
| Verification | Test DB unavailable | Use existing `TestDatabase.assumeAvailable` behavior | Tests skip according to current pattern; report environment limitation |

### VI. Security, Integrity, Idempotency, and Cleanup

- Continue using parameterized SQL and `Tuple` values.
- Keep `inventory_movement` immutable; do not repair command failures by mutating movements outside schema-normalization migration.
- Fulfillment movement insertion, reservation updates, line updates, order updates, audit event insertion, and idempotency storage must remain transactional.
- Do not introduce receipt/refund side effects into Phase 03 fulfillment; that would couple POS receipt orchestration into core order flow prematurely.
- Payment capture idempotency must continue preventing duplicate payment rows for retried requests.
- Cancellation must continue blocking captured-payment orders until refund orchestration exists.
- Migration cleanup/downgrade must be explicit: re-applying `NOT NULL` on `to_location_id` is only safe if null `OUT` rows are converted or removed during downgrade.
- Test cleanup must delete payment, movement, reservation, line, order, product, and location rows created by each test to avoid cross-test leakage.

### VII. Validation Strategy

Chunk-aware validation:

- Repository status/diff:
  - `rtk git status --short`
  - `rtk git diff --stat`
  - `rtk git diff -- <changed-files>`
- Syntax/compile:
  - `rtk ./gradlew compileJava compileKotlin`
- Targeted tests:
  - `rtk ./gradlew test --tests '*OrderProcess*'`
  - Prefer narrower tests during chunks, e.g. `rtk ./gradlew test --tests 'com.literp.repository.OrderProcessRepositoryTransactionTest'`
- Migration validation:
  - `rtk python scripts/verify_migrations.py`
- YAML/JSON/API asset checks when OpenAPI files change:
  - `rtk python3 - <<'PY'` with YAML/JSON parse/parity script, following the existing API asset validation approach from prior Phase 03 work.
- Symbol/path checks:
  - `rtk grep -Rni "movement_type = 'OUT'\|to_location_id\|from_location_id" src docs python/database/migration api_collections/open_api_spec`
  - `rtk grep -Rni "receipt\|refund\|partial fulfillment\|partial payment" docs/implementation-plan docs/knowledge docs/API_IMPLEMENTATION.md api_collections/open_api_spec`

Final validation should include:

- Compile passed.
- OrderProcess tests passed or skipped only by existing test DB assumption.
- Migration verification passed if migration changed.
- Diff reviewed for source, docs, API assets, and migration consistency.
- No claim that receipt/refund/partial flows are implemented unless code actually exists.

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full feature in one pass.

#### Chunk 0: Discovery and Integration Confirmation
- **Goal:** Confirm current code, schema, seed data, tests, and docs before editing.
- **Files to read:**
  - `docs/implementation-plan/03-order-inventory-flow.md`
  - `docs/implementation-plan/05-pos-manufacturing-expansion.md`
  - `docs/knowledge/MODEL_DESIGN.md`
  - `docs/knowledge/PROCESS_ORDER_PAYMENT_FULFILLMENT.md`
  - `src/main/kotlin/com/literp/repository/OrderProcessRepository.kt`
  - `src/main/java/com/literp/service/order/OrderProcessService.java`
  - `src/main/kotlin/com/literp/verticle/handler/OrderProcessHandler.kt`
  - `src/test/kotlin/com/literp/repository/OrderProcessRepositoryTransactionTest.kt`
  - relevant Alembic migrations and API assets
- **Commands:**
  - `rtk git status --short`
  - `rtk grep -Rni "movement_type\|from_location_id\|to_location_id\|fulfillSalesOrder\|getCurrentStock\|getAvailableStock" src python/database/migration docs api_collections/open_api_spec`
  - `rtk grep -Rni "receipt\|refund\|partial" docs src api_collections/open_api_spec python/database/migration`
- **Evidence to confirm:** Current fulfillment insert shape, stock rollup rules, migration chain, seed `OUT` rows, existing test coverage, and docs that already defer receipt/refund.
- **Stop condition:** Evidence summary produced; no edits.

#### Chunk 1: Schema Contract for Explicit `OUT` Movements
- **Goal:** Allow explicit sales `OUT` movements without fake destination locations.
- **Files to change:**
  - New Alembic migration under `python/database/migration/alembic/versions/`
- **Symbols to add/change:**
  - **Schema Contract (Conceptual):** make `inventory_movement.to_location_id` nullable.
  - Optional constraint/check only after confirming it does not break existing valid data.
- **Implementation shape:**
  - Add migration after the current head.
  - Alter `inventory_movement.to_location_id` to nullable.
  - Normalize sales `OUT` rows with `from_location_id = to_location_id` to `to_location_id = NULL` if data audit shows only workaround rows.
  - Downgrade must handle null `to_location_id` rows explicitly before restoring non-nullability, or clearly fail with an actionable message.
- **Validation:**
  - `rtk python scripts/verify_migrations.py`
  - `rtk git diff -- python/database/migration/alembic/versions`
- **Stop condition:** Migration validates and no Kotlin code has been changed yet.

#### Chunk 2: Fulfillment Insert Uses Nullable Destination
- **Goal:** Make runtime fulfillment write semantically explicit sales `OUT` movements.
- **Files to change:**
  - `src/main/kotlin/com/literp/repository/OrderProcessRepository.kt`
  - `src/test/kotlin/com/literp/repository/OrderProcessRepositoryTransactionTest.kt`
- **Symbols to add/change:**
  - `OrderProcessRepository.fulfillSalesOrder(...)`
  - Test helper `insertInventoryMovement(...)` if it currently requires non-null `toLocationId`.
  - Existing/current stock movement test expectations.
- **Implementation shape:**
  - Change `movementInsertQuery` usage for fulfillment so `from_location_id = locationId` and `to_location_id = null`.
  - Preserve transaction, idempotency, payment guard, reservation update, line update, and audit behavior.
  - Adjust test helper to permit `toLocationId: String?` for `OUT` rows.
  - Pin stock query behavior with `OUT` rows whose destination is null.
- **Validation:**
  - `rtk ./gradlew compileKotlin`
  - `rtk ./gradlew test --tests 'com.literp.repository.OrderProcessRepositoryTransactionTest.currentStockCombinesInboundTransfersAndOutboundMovements'`
  - `rtk ./gradlew test --tests 'com.literp.repository.OrderProcessRepositoryTransactionTest.fulfillSalesOrderIsIdempotentAndWritesAuditEvent'`
- **Stop condition:** Runtime writes compile, focused movement tests pass, and diff is limited to fulfillment movement semantics plus tests.

#### Chunk 3: Movement Semantics Documentation and API Asset Alignment
- **Goal:** Remove workaround language and document final movement semantics.
- **Files to change:**
  - `docs/knowledge/MODEL_DESIGN.md`
  - `docs/knowledge/PROCESS_ORDER_PAYMENT_FULFILLMENT.md`
  - `docs/ENDPOINTS_OVERVIEW.md`
  - `docs/API_IMPLEMENTATION.md`
  - `api_collections/open_api_spec/order-process-README.md`
  - `api_collections/open_api_spec/order-process.yaml` and `order-process.json` only if they mention movement details
- **Symbols to add/change:** Not applicable; documentation-only.
- **Implementation shape:**
  - State that sales fulfillment writes `OUT` movements with source in `from_location_id` and no internal destination in `to_location_id`.
  - State that stock rollups deduct sales `OUT` by source location.
  - Keep receipt/refund endpoint absence accurate.
- **Validation:**
  - `rtk grep -Rni "to_location_id = from_location_id\|same location\|workaround" docs api_collections/open_api_spec`
  - `rtk python3 -m json.tool api_collections/open_api_spec/order-process.json >/dev/null` if JSON changed
  - YAML parse command using existing project parser if YAML changed
- **Stop condition:** Docs/API assets no longer describe the workaround as current behavior.

#### Chunk 4: Document Receipt, Refund, Partial Fulfillment, and Partial Payment Decisions
- **Goal:** Close `03.5` as an explicit design decision without implementing Phase 05 features.
- **Files to change:**
  - `docs/implementation-plan/03-order-inventory-flow.md`
  - `docs/implementation-plan/05-pos-manufacturing-expansion.md` only if adding back-reference is useful
  - `docs/API_IMPLEMENTATION.md` or `docs/API_TESTING_GUIDE.md` for client-facing guardrails
- **Symbols to add/change:** Not applicable; documentation-only.
- **Implementation shape:**
  - Mark decisions as documented, not implemented, unless implementation occurs.
  - Link receipt/refund to `05.3 Receipt And Refund API`.
  - State Phase 03 fulfillment is all-or-nothing.
  - State partial payments can be captured as multiple payment rows, but fulfillment requires captured total to cover order total.
  - Keep cancellation-after-captured-payment blocked until refund orchestration exists.
- **Validation:**
  - `rtk grep -Rni "Phase 05\|receipt\|refund\|partial fulfillment\|partial payment\|captured total" docs/implementation-plan docs/API_IMPLEMENTATION.md docs/API_TESTING_GUIDE.md`
  - `rtk git diff -- docs/implementation-plan docs/API_IMPLEMENTATION.md docs/API_TESTING_GUIDE.md`
- **Stop condition:** Every `03.5` decision is explicit and linked to the owning phase or current guardrail.

#### Chunk 5: Valid State Transition and Inventory Side-Effect Tests
- **Goal:** Add automated coverage for successful lifecycle paths and movement effects.
- **Files to change:**
  - `src/test/kotlin/com/literp/repository/OrderProcessRepositoryTransactionTest.kt`
- **Symbols to add/change:**
  - New tests for `DRAFT -> CONFIRMED -> FULFILLED`, `DRAFT -> CANCELLED`, and `CONFIRMED -> CANCELLED` without payment.
  - New assertions for sales `OUT` movement count, null destination, source location, and stock decrease.
- **Implementation shape:**
  - Reuse existing `createSeedOrder`, `insertInventoryMovement`, cleanup helpers, and `TestDatabase.assumeAvailable` pattern.
  - Avoid HTTP-layer setup unless repository tests cannot prove a required transition.
  - Keep each test independent with cleanup in `finally`.
- **Validation:**
  - `rtk ./gradlew test --tests 'com.literp.repository.OrderProcessRepositoryTransactionTest'`
- **Stop condition:** Valid transition and inventory side-effect tests pass.

#### Chunk 6: Invalid Transition and Payment Guardrail Tests
- **Goal:** Add automated coverage for rejected lifecycle transitions and payment rules.
- **Files to change:**
  - `src/test/kotlin/com/literp/repository/OrderProcessRepositoryTransactionTest.kt`
- **Symbols to add/change:**
  - New tests for non-draft confirm, non-confirmed fulfill, fulfill before full payment, cancel fulfilled, cancel with captured payment, invalid payment amount, and idempotency-key conflict.
- **Implementation shape:**
  - Use existing repository methods to move orders into states, then assert failures with existing `assertFailsWithMessage` helper.
  - Assert failed commands do not create extra payments, movements, reservations, or status changes.
- **Validation:**
  - `rtk ./gradlew test --tests 'com.literp.repository.OrderProcessRepositoryTransactionTest'`
- **Stop condition:** Invalid transition and payment guardrail tests pass.

#### Chunk 7: Final Phase 03 Plan Alignment and Verification
- **Goal:** Mark only evidence-backed Phase 03 remaining items complete and run final checks.
- **Files to change:**
  - `docs/implementation-plan/03-order-inventory-flow.md`
  - possibly `docs/implementation-plan/ads/phase-03-task-4-5-6.md` if implementation reveals a design correction
- **Symbols to add/change:** Not applicable; documentation-only.
- **Implementation shape:**
  - Update checkboxes for `03.4`, `03.5`, and `03.6` only after corresponding code/docs/tests exist.
  - Add concise evidence bullets if the implementation plan convention supports them.
  - Do not mark receipt/refund/partial feature implementation complete; mark the Phase 03 decision complete if it is documented and linked to Phase 05.
- **Validation:**
  - `rtk ./gradlew compileJava compileKotlin test --tests '*OrderProcess*'`
  - `rtk python scripts/verify_migrations.py` if migration changed
  - `rtk git diff --stat`
  - `rtk git diff -- docs src python api_collections`
- **Stop condition:** Final targeted validation passes, diff reviewed, and Phase 03 status matches actual evidence.

### IX. Handoff to `chunked-implementation`

Recommended agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, safe-python-edit, and post-edit-discipline if available.

Task:
Implement Phase 03 tasks 03.4, 03.5, and 03.6 from docs/implementation-plan/03-order-inventory-flow.md using docs/implementation-plan/ads/phase-03-task-4-5-6.md as the design.

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

For each following chunk:

```text
Use the chunked-implementation skill.
Execute the next approved chunk only.
Keep the change compile-safe.
Run the chunk validation commands.
Show git diff and stop for review before continuing.
```

### X. Conclusion and Next Steps

Phase 03 should finish with one schema-backed semantics correction, explicit deferral of POS receipt/refund/partial capabilities, and stronger automated order-flow verification. The immediate next step is Chunk 0 discovery, followed by a migration-only chunk that makes the movement ledger capable of representing sales `OUT` rows without a fake destination location.

Key risk: changing `inventory_movement.to_location_id` nullability affects seed data, stock rollup assumptions, and any reports that filter only by destination location. Implementation must verify and test stock rollups before updating the phase plan as complete.
