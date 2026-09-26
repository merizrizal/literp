## Architectural Design Specification: Manufacturing Inventory Movements

**Source:** [Phase 05, Task 05.6](../05-pos-manufacturing-expansion.md#056-manufacturing-inventory-movements).

**Preceding designs:** [Task 05.4 BOM API](phase-05-task-4.md) and [Task 05.5 Work Order And Production Run API](phase-05-task-5.md).

**Status:** Proposed; design only. Approval of this document is separate from permission to implement, migrate, reconcile historical inventory, or deploy. The preceding manufacturing designs are not evidence of implemented behavior.

**Evidence baseline:** Branch `phase-05-task-3`, previously observed HEAD `1ed2617`. The existing untracked Task 05.5 ADS was inspected and preserved. No fetch or database probe performed for this design.

**Goal:** Consume manufacturing components and receive good finished output through the existing inventory movement ledger, with atomic run-level posting, durable duplicate prevention, shared sales/POS stock protection, and a made-to-stock-first delivery gate before demand-linked made-to-order.

---

### I. Overview and Contract

#### Ownership and posting boundary — proposed

Retain Task 05.5 run completion as the recording of physical production results. Introduce an **explicit inventory-posting command for a completed run** rather than silently making old completion retries create stock effects. A completed run is not necessarily posted. The new command writes all component OUT movements and the optional good-output IN movement in one transaction; there is never a successful half-posting.

New stock-enabled work orders must have every completed run posted before work-order closure. A shortage can therefore leave physical results COMPLETED and inventory status UNPOSTED without falsifying results or forcing a second run completion. Operators replenish/reconcile through an approved process and retry posting; no negative-stock override is exposed here.

Posting per run makes finished output available before the entire work order closes. Work-order completion must not create a second set of movements. No movement is written on plan/start/cancel. This is a proposed extension to Task 05.5, not a claim that its current ADS already contains posting behavior.

#### Proposed HTTP contracts

Extend the proposed Task 05.5 `manufacturing-work-orders.yaml`/`.json` bundle, not a separate duplicate work-order API. Paths are relative to `/api/v1`.

| Method/path or changed operation | operationId | Capability | Contract |
|---|---|---|---|
| POST `/manufacturing/work-orders/{workOrderId}/runs/{runId}/post-inventory` | `postProductionRunInventory` (proposed) | `manufacturing.inventory.post` (proposed) | Required `Idempotency-Key`; no business payload; 200 committed posting envelope |
| GET `/manufacturing/work-orders/{workOrderId}/runs/{runId}/inventory-posting` | `getProductionRunInventoryPosting` (proposed) | `manufacturing.inventory.read` (proposed) | 200 posting state/summary for scoped existing run |
| Existing proposed work-order/run reads | Task 05.5 operation IDs | Existing proposed read capabilities | Add posting policy/state and compact summary; never infer posted from COMPLETED |
| Existing proposed work-order plan | `planWorkOrder` | Existing plan capability; MTO also requires `order.read` | New plans use approved stock-posting policy; later add production purpose/demand link |
| Existing proposed work-order closure | `completeWorkOrder` | Existing complete capability | Stock-enabled orders additionally require every completed run POSTED |

Posting allows authorized HUMAN or SERVICE principals; operator attribution remains the trusted starter/completer captured by Task 05.5. The posting actor is recorded separately. Both new routes scope through `(workOrderId, runId)` and the work-order location; grants/capability are rechecked before replay. Use existing success/error envelopes and request IDs. Unknown fields, client movement IDs/quantities/actors/locations and material overrides are rejected.

**Posting response, proposed:** `workOrderId`, `runId`, `postingId`, `state=POSTED`, `policyVersion`, `calculationVersion`, `postedAt`, finished product/location/base UOM, good/scrap quantities, and movement entries with movement ID, direction, product/SKU/base UOM, positive decimal quantity, source/destination, and pinned BOM-line identity for consumption. Actor provenance lives in the audit record; do not expose issuer/subject broadly without approved read policy.

GET of a policy-1 unposted run returns `state=UNPOSTED`, null posting ID and empty movements, not fabricated zero consumption. Include `eligibleToPost` and a safe reason so an IN_PROGRESS run is distinguishable from a completed run awaiting posting; this flag is not a stock-availability guarantee. History excluded from automatic posting returns `state=HISTORICAL_UNMANAGED`; this means unknown/not managed by this feature, **not** proof of no historical movements. Missing/out-of-scope/wrong-parent run returns 404.

#### Proposed material, scrap and location semantics

1. **One location initially:** consume components from and receive finished output into the work order's PRODUCTION location. No implicit store delivery or transfer. For another fulfillment location, an independently approved transfer workflow is needed; Task 05.6 does not pretend an IN with two locations is a TRANSFER.
2. **BOM backflush:** derive component consumption from the immutable execution snapshot, not the currently ACTIVE BOM. This is calculated consumption, not measured shop-floor usage. Public/audit metadata must say `BOM_BACKFLUSH_V1`. If measured consumption is required, stop and revise the contract before implementation rather than silently label estimates as measurements.
3. **Formula:** for each direct component, `rawConsumption = (goodOutput + scrapOutput) * quantityPerUnit * (1 + scrapPercentage / 100)`. The BOM percentage is an additive component allowance, not a division by expected yield. Finished rejects consume recipe inputs; the component allowance covers a distinct additional loss. Approval of these semantics is mandatory.
4. **Rounding:** use BigDecimal throughout; aggregate by component product/base UOM first, then round each run's consumption **up** to three decimals (`CEILING` for nonnegative values). Persist calculation inputs/version and rounded result. Do not round intermediate multiplication or reuse a client float. Reject each movement quantity outside `NUMERIC(12,3)`; never split an overflowing quantity to evade validation.
5. **Finished output:** IN quantity equals good output only. Scrap is not received and is not a second OUT of finished goods that never entered stock. An all-scrap run posts component OUT rows and no zero-valued IN row.
6. **Example:** 10 good + 2 scrap, component quantity 0.125 and allowance 5% yields `12 * 0.125 * 1.05 = 1.575` OUT and 10.000 finished IN. With quantity 0.001 and allowance 1%, attempted output 1 gives 0.00101, rounded up to 0.002. Tests must make this bias visible.
7. **Nested BOMs:** consume stocked subassemblies as direct components. Do not also consume their raw descendants, expand the graph recursively, or create child work orders automatically. Subassemblies can be produced by separate work orders first; this is a conscious restriction beyond Task 05.4's allowance of nested definitions.
8. **Units/catalog identity:** movement quantities use the snapshotted base UOM; no conversion exists. Compare snapshot product/SKU/base UOM/type with current identity before posting. Base-UOM/type changes on products already referenced by movements, reservations or BOMs must be prevented through the catalog API (see Section IV). Activity changes alone must not strand completion/posting of already-started production.
9. **Availability:** consumption may use only `currentStock - activeSalesReservations` at the source location. No manufacturing reservation is introduced; availability at run start is not a guarantee for later posting. Evaluate after shared product locks and before inserting any IN, so output cannot finance its own inputs.

#### Made-to-stock and gated made-to-order

- **MTS first:** new plans default to proposed `productionPurpose=MADE_TO_STOCK`; produce into shared stock, then existing sales/POS confirm/pay/fulfill workflows can use it. No sales order is manufactured, paid or fulfilled automatically.
- **MTO gate:** implement only after documented MTS atomicity, replay, shortage, decimal and cross-domain concurrency acceptance. Do not mark the entire Task 05.6 complete if this gated task remains unimplemented.
- **Restricted MTO proposal:** allow `productionPurpose=MADE_TO_ORDER` plus `salesOrderLineId` on planning. Server derives its parent order, checks `order.read` and location access, and pins a demand snapshot. It is demand-linked production into shared stock, **not exclusive allocation**. A business requirement for guaranteed customer-specific allocation needs another approved design, not a misleading MTO label.
- Initially accept only a non-POS DRAFT order line for the same finished product and same production/fulfillment location. Plan quantity is positive and no greater than that line's outstanding quantity. One non-cancelled linked work order per sales line in this restricted version; no split/replacement production after completion without a revised contract. Require explicit approval of this limitation.
- Manufacturing does not reserve not-yet-existing output or rewrite sales semantics. After posting, the ordinary sales confirmation reserves stock; payment capture and fulfillment continue unchanged. Other customers may reserve shared output first. If this is unacceptable, block MTO implementation until allocation is designed.
- A linked order may change/cancel while production is underway. Preserve the demand snapshot and physical output; no automatic reversal, work-order cancellation or stock deletion. The caller sees a stale/cancelled demand indication where authorized, and output remains shared stock. Do not hold sales locks during posting merely to preserve a link.

**Function Signature Contract (Concrete):**

- `BaseRepository.inTransaction(work: (SqlConnection) -> Single<T>): Single<T>` provides the transaction boundary.
- `OrderProcessRepository.getCurrentStock(productId: String, locationId: String): Single<JsonObject>` and `getAvailableStock(productId: String, locationId: String): Single<JsonObject>` implement ledger rollups.
- Private `getAvailableStockQuantity(connection: SqlConnection, productId: String, locationId: String): Single<BigDecimal>` uses the caller's transaction, but currently does not acquire a shared product lock.
- `fulfillSalesOrder(orderId: String, createdBy: String?, notes: String?, idempotencyKey: String): Single<JsonObject>` writes SALES_ORDER OUT rows and fulfills reservations in one transaction.
- `ProductRepository.updateProduct(...)` currently permits `product_type` and `base_uom` changes; its observed implementation is not a historical-unit guard.

**Function Signature Contract (Conceptual):**

- Extend the proposed Task 05.5 service/handler/repository with `postProductionRunInventory` and `getProductionRunInventoryPosting`. Service results use `Future<JsonObject>`; repository results use `Single<JsonObject>`; trusted grant sets and actor subject/issuer/organization are separate from request fields.
- Proposed `InventoryStockGuard.lockProducts(connection, productIds): Completable` acquires all distinct product rows in sorted ID order on the existing connection, including products with no stock rows. It never opens/commits a transaction. An available-quantity helper reuses the existing rollup semantics on that same connection.
- Proposed `ManufacturingInventoryPostingRepository.postCompletedRun(connection, scopedRun, trustedActor): Single<JsonObject>` writes posting and movement data inside the caller's transaction; it must not call `pool.rxWithTransaction` internally. Exact DTO/signature/codegen choices remain subject to Chunk 0.
- Proposed pure calculation helper takes a validated immutable snapshot plus good/scrap decimals and produces deterministic movement intents; it does not query current BOM or mutate stock.

**Safe stubs:** new HTTP operations return authenticated explicit 501 until ready. The internal posting contract fails explicitly if called before implementation; never return an empty successful posting or mark a run POSTED. Define called helpers with/before callers. Existing Task 05.5 execution-only behavior is not silently changed by a stub.

### II. Observed Evidence and Assumptions

| Evidence inspected | Design consequence |
|---|---|
| Phase 05 plan, Task 05.6 and assumptions | Requires OUT consumption, IN output, MTS then gated MTO, common stock rollups; no accounting integration |
| Task 05.4 ADS, Section I | Proposed immutable BOMs, per-base-UOM quantities, component scrap allowances and nested definitions; no implemented consumption guarantee |
| Task 05.5 ADS, Sections I/III/IV/VI | Proposed immutable run results/recipe snapshots and legacy provenance; explicitly no movement writes; requires a later cutover policy |
| `docs/knowledge/MODEL_DESIGN.md`, lines 70–109 | Inventory is an immutable movement ledger; reservations represent sales intent; polymorphic reference is not an FK |
| Initial migration, lines 41–60 and 124–145 | Positive-direction IN/OUT types, WORK_ORDER reference type, decimal(12,3), actor width 255; no PRODUCTION_RUN reference enum |
| `e6d9a7c1b2f4_04_inventory_out_movement_semantics.py` | OUT destination may be null; same-location historical OUT destinations were normalized |
| `OrderProcessRepository.kt`, lines 282–376 | Current/available formulas already understand IN, OUT, TRANSFER, ADJUSTMENT and subtract RESERVED quantities |
| Same repository, lines 502–630 and 814–988 | Confirmation checks available stock and creates reservations; fulfillment inserts OUT and changes reservation status; no shared product-key locking in those read/write paths |
| Same repository, lines 1335–1545 | POS context locks terminal→shift→order; ordinary non-attributed commands do not gain those order locks; command keys are not a replacement for inventory serialization |
| `ProductRepository.kt`, lines 201–240 | Base UOM/type can currently change, so snapshot comparison alone cannot guarantee long-term ledger unit consistency |
| Seed migration, lines 828–844 and 902–934 | Completed runs have material JSON; seeded WORK_ORDER IN may target a store while carrying production as source. Replaying or normalizing these rows would change existing stock |
| `OrderProcessRepositoryTransactionTest.kt`, fulfillment/cancel cases | Existing transaction/regression tests assert payment gates, movement counts and reservation behavior; extend rather than replace these guarantees |
| Accepted structure decision and prior inspected build/security/proxy files | Keep inventory stock endpoints in the order path; a small repository lock/helper is permitted as a proposed cross-domain dependency, not a domain-layout migration |

#### Approval gates / assumptions

- Confirm Task 05.4/05.5 actually implemented and accepted before behavior integration; their future paths are proposed, not present at this baseline.
- Approve separate explicit posting and the POSTED-before-work-order-close rule, versus an alternative atomic complete-and-post contract.
- Approve calculated BOM backflush, formula, upward rounding, direct-component-only nesting, PRODUCTION-only same-location stock, all-scrap treatment and shortage/no-negative-stock policy.
- Approve shared sales/POS stock serialization and catalog unit/type guards as necessary cross-domain changes. The original 4–7 day estimate may not cover these safety prerequisites.
- Approve immutable policy-based cutover and historical exclusion. Decide audited adoption separately; no automatic backfill.
- Approve the restricted MTO semantics or require a separate allocation design before that gated slice.
- Confirm deployment organization is the data boundary; shared multi-tenant tables need another design. Confirm approved PostgreSQL/Python environments before execution.

### III. Required Technical Dependencies and Imports

Reuse PostgreSQL row locks/constraints/transactions, Alembic, existing Vert.x Java service proxies/RxJava SQL client, JsonObject/JsonArray, BigDecimal/RoundingMode, UUID/time/digest APIs, and JUnit/HTTP fixtures. No queue, external warehouse system, accounting API or new runtime library is proposed.

**Existing integration paths:** `src/main/kotlin/com/literp/repository/OrderProcessRepository.kt`, `ProductRepository.kt`, `BaseRepository.kt`; corresponding repository/HTTP tests; `scripts/verify_migrations.py`; existing stock API/Bruno assets.

**Proposed new helper paths:**

- `src/main/kotlin/com/literp/repository/InventoryStockGuard.kt`
- `src/main/kotlin/com/literp/repository/ManufacturingInventoryPostingRepository.kt`
- `src/test/kotlin/com/literp/repository/InventoryStockGuardTest.kt`
- `src/test/kotlin/com/literp/repository/ManufacturingInventoryPostingRepositoryTest.kt`

Extend the proposed Task 05.5 `ManufacturingWorkOrderRepository`, `ManufacturingWorkOrderService`, service implementation, handler, scope middleware and contract/HTTP tests in their documented layer roots. Reuse the manufacturing ModuleGen, not a new inventory service package. Two posting Bruno requests and later MTO examples stay under `api_collections/Literp`; update the same work-order YAML/JSON/README. Verify actual symbols in Chunk 0.

#### Proposed additive persistence and cutover

1. Add immutable work-order `inventory_policy_version`: null for all existing orders; new stock-enabled plans explicitly set `1`. Add nullable legacy-compatible production purpose, explicitly MADE_TO_STOCK on these new plans; accept MADE_TO_ORDER only after its gated contract is enabled. No database default silently upgrades an old application writer. Null policy means historical/execution-only; it is not an unposted backlog. Migration must not manufacture posting records from run status or `material_consumed`.
2. Proposed `manufacturing_inventory_posting`: unique run FK, parent FK with parent/run consistency enforcement, policy/calculation versions, stable snapshot/result fingerprint, posting actor subject/issuer/organization, server timestamp and immutable response summary. A posting row is committed only with its full movement set; no durable PENDING row.
3. Proposed `manufacturing_inventory_posting_line`: posting FK, movement FK unique, role (`COMPONENT_OUT`/`FINISHED_IN`), product/base UOM and BOM-line/calculation snapshot. Enforce one aggregated OUT per component per posting and at most one IN; movement IDs are unique across posting lines. New names are conceptual pending migration conventions.
4. Movement rows use existing `reference_type=WORK_ORDER`, `reference_id=workOrderId`. Run attribution comes from the constrained posting/line join; do not overload WORK_ORDER with a run ID or introduce a new enum unnecessarily. OUT: from=work-order location, to=null. IN: from=null, to=work-order location. Quantity strictly positive. Preserve legacy WORK_ORDER rows exactly.
5. Enforce append-only posting/line records and protect linked manufacturing movement rows from UPDATE/DELETE using focused database guards. Avoid retroactively imposing incompatible global movement constraints. Never expose a raw movement mutation API.
6. Persist server-calculated new-run `material_consumed` in a versioned JSON object (`source=BOM_BACKFLUSH_V1`, posting ID, component product/base UOM/quantity/movement IDs). This is a narrowly permitted posting-owned enrichment after completion; any Task 05.5 whole-row immutability guard must permit only this transactional null-to-posted projection while keeping results/attribution immutable. It is a convenience projection of posting lines, not independent posting proof. Do not rewrite legacy JSON values or use them to infer successful stock receipt.
7. `created_by` derives from the trusted posting actor only if it fits its 255-character contract; never truncate. Either approve a bounded subject contract or widen this field additively while retaining full issuer/subject provenance in the posting audit. This decision blocks implementation, not an implicit lossy fallback.
8. MTO linkage is a later additive revision: proposed demand-link table with work-order FK and sales-order-line FK, immutable demand snapshot and constrained uniqueness. Lock the sales parent/line when linking; cancelled production may release the active-link guard through a tested lifecycle path while retaining link history. Reject source-order deletion while referenced, rather than cascade away audit history.

Preflight schema/data, head count, reference consistency and numeric validity. Test fresh install and seeded upgrade separately. Existing seed inventory and historical results remain unchanged; existing managed API work orders without a policy marker continue execution-only and cannot be posted through the new route. Adopting them requires a separate approved reconciliation that proves movements are absent or already accounted for.

### IV. Step-by-Step Procedure / Execution Flow

#### Shared inventory serialization prerequisite

A manufacturing-only lock is insufficient because sales confirmations create reservations against the same availability. Proposed narrow shared protocol:

1. Each command obtains its established domain/actor locks first. Preserve POS terminal→shift→sales-order order. Ordinary sales confirm/fulfill/cancel also lock their sales-order row before command claim/state check; do not let different idempotency keys race the same lifecycle.
2. After domain/command/run locks, acquire **all affected product rows FOR UPDATE**, sorted by product ID, including output and components. This deliberately serializes a product across locations; lower concurrency is an accepted initial trade-off for a stable existing lock target and no advisory-hash collisions.
3. Only after locks are acquired, read current/reserved sums in a fresh READ COMMITTED statement on the same connection; check all deductions before inserts. Collect/lock the full product set before iterating lines. Never lock an aggregate SELECT as if that serialized future inserts.
4. Sales confirmation, fulfillment and reservation-releasing cancellation join this protocol; use the same set ordering in manufacturing. Confirmation checks aggregated demand for repeated product lines, not independently against stale availability. Fulfillment preserves payment/status rules and checks required OUT against `available + this order's active reservations`, with reservation credit counted once per product. Convert its reservations and OUT atomically.
5. No code holding stock locks subsequently acquires another sales/work-order parent. MTO planning locks its sales parent before creating the work order; posting never locks the linked sales order. Inspect remaining POS/refund/transfer writers at implementation time, since Task 05.3 is unfinished in this evidence baseline. Any new decrement/reservation writer must participate before claiming global no-overspend safety.
6. Catalog base-UOM/type mutation also uses the product lock and a **subsequent** reference check before update, refusing changes once movements/reservations/BOM headers/lines reference it. Preserve ordinary name/metadata/activity edits. Recheck product identity under posting locks. This is a narrow integrity guard, not a master-data refactor.

All protocol participants use caller-owned transactions. Product-row locks also serialize concurrent catalog UPDATEs; validate catalog guards under real concurrent connections. Out-of-band SQL/import tools are outside this application guarantee and must follow an approved maintenance protocol; do not advertise a universal database stock constraint that does not exist.

#### Post a completed run

1. Authenticate/capability-check and validate IDs/key/empty body. SQL-scope parent and run by current grants; lock parent then claim command then lock run, following Task 05.5 conventions. Check current authorization before any command replay.
2. Return a saved command response or existing immutable posting for a legitimate same-run retry; unique run posting prevents duplicates even with a new key or another authorized poster. Validate any existing posting fingerprint against immutable run/snapshot data. A mismatch is an integrity conflict, not permission to overwrite stock history.
3. Require policy version 1, API-managed consistent snapshot, parent IN_PROGRESS and run COMPLETED. Historical exclusion or unsupported policy conflicts. No automatic posting of earlier completed runs. Already-posted replay remains valid after work-order closure.
4. Calculate the complete deterministic movement set. Validate product identities, positive bounds and formula version, then acquire sorted product locks and revalidate identities after waiting. Allow inactive products/locations for already-started production, but never changed UOM/type or unauthorized location.
5. Read available component balances; reject shortages before any write. Good-output IN must not be considered available to satisfy its own consumption. Reject malformed/self-component snapshots rather than netting IN and OUT.
6. Insert posting header, component OUT rows, optional finished IN row, linking rows, versioned material projection and audit event. Update command response in the same transaction. Row constraints, event failure or movement failure roll back everything. Do not mutate good/scrap quantities or replace completion actor/time.
7. Commit and return 200 with stable posting/movement IDs. A lost response retries the same key; no double stock change. GET joins scoped run/posting/lines in one consistent read and never calculates historical status from material JSON.

#### Work-order closure and MTS

- New plans carry stock policy 1 only once posting and shared guards are accepted. Do not set the marker in an earlier stub-only slice.
- Run recording continues as Task 05.5 specified. Inventory posting is a separate explicitly authorized action. Closure locks the parent, requires no open run and at least one completed run, and additionally requires every completed run to have a valid committed posting. Legacy execution-only closure keeps its prior behavior.
- MTS smoke: approved component stock fixture → plan → start → complete run → inspect UNPOSTED → post → inspect stock → repeat posting → close. Then confirm/pay/fulfill a sale from the same location and observe common CURRENT/AVAILABLE rollups. Moving goods to a store is not implicitly demonstrated by this same-location smoke.

#### Gated MTO

- Once MTS acceptance is recorded, validate immutable purpose and optional demand-link fields at plan time. MTS rejects a line link; MTO requires one. Scope sales lookup by `order.read` and grants without revealing inaccessible order metadata.
- Lock source sales parent/line, verify DRAFT/non-POS/same location/product/outstanding quantity, reject duplicate active linkage, and store link snapshot together with work-order plan/event/command. Include purpose/line identity in the plan fingerprint. No stock reservation or sales status/payment mutation.
- Follow identical run/POSTED closure behavior. Authorized read responses can identify the link; manufacturing-only readers must not gain customer/order details merely through production visibility.
- After posting, explicitly execute normal sales confirmation/payment/fulfillment. Preserve failure on insufficient stock/payment. Demand cancellation does not erase manufactured stock; report it as linked demand no longer current, not failed inventory posting.

### V. Failure Modes and Resilience

| Stage | Failure Mode | Agent/System Action | Next State/Error Report |
|---|---|---|---|
| Dependency | BOM/run lifecycle absent, lock writers unknown, policy unapproved | Stop integration | ADS or blocked chunk; no stock writes |
| Authentication | Invalid/missing bearer | Reject before lookup | 401 `UNAUTHENTICATED` |
| Authorization | Wrong organization/capability/empty grants | Reject before replay | 403 `FORBIDDEN` |
| Resource scope | Foreign/missing parent or wrong parent/run | Scoped query only | 404 `RESOURCE_NOT_FOUND` |
| Input | Invalid IDs/key/body or client-owned movement fields | Reject without mutation | 400 `VALIDATION_ERROR` |
| Eligibility | Run unfinished, historical policy or unsupported version | Preserve physical results and historical ledger | 409 `CONFLICT` |
| Calculation | Bad snapshot, self-component, overflow or unit/type drift | Reject entire posting | 409 `CONFLICT`; approved repair required |
| Availability | One or more component shortages after locks | Roll back command; no OUT/IN | 409 `CONFLICT`; run remains COMPLETED/UNPOSTED |
| Concurrency | Manufacturing versus sales reservation/fulfillment | Shared product locks and fresh balance read | Consistent winner; other command rechecks/fails safely |
| Replay | Changed key fingerprint or posting/result inconsistency | Do not overwrite/repost | 409 `CONFLICT` |
| Partial write | OUT succeeds but IN/link/event/command fails | Roll back entire transaction | No posting or stock delta; sanitized failure |
| Timeout | Lock/database timeout or lost connection | Roll back if uncommitted; caller retries durable key | 503 `DB_TIMEOUT` or sanitized 500 `INTERNAL_ERROR` |
| Lost response | Commit succeeded but HTTP response lost | Current authorization then durable replay | Original posting IDs; exactly one movement set |
| Closure | Any policy-1 completed run unposted | Leave work order IN_PROGRESS | 409 `CONFLICT` |
| MTO | Foreign/changed/duplicate/ineligible demand at planning | Roll back plan/link/event | 404 for hidden/missing resource; otherwise 409 |
| MTO after production | Demand changed/cancelled | Preserve stock and demand snapshot | Shared stock remains; no automatic reversal |
| Migration | Legacy inconsistency, multiple heads or unsafe backfill request | Stop with redacted evidence | No auto-deletion or replay of history |
| Partial delivery | Posting path not implemented | Explicit no-write stub | 501 `NOT_IMPLEMENTED` |

Use existing public error vocabulary; proposed posting-specific exception names/failure codes must map safely through the actual Java service proxy. Do not forward raw SQL, fingerprint content, customer fields or credentials.

### VI. Security, Integrity, Idempotency, and Cleanup

- **Security:** verify scope before replay; postings use server-controlled product/location/quantity/reference/actor values. Read permission is not post permission. Demand lookup adds order authorization; production visibility does not imply customer-data access.
- **Integrity:** run/result/snapshot fingerprint, unique posting per run, constrained movement links and append-only records prevent successful duplicate posting. Parent lock makes posting and closure consistent. Existing movement rollups remain authoritative; do not introduce a second stock balance table.
- **Idempotency:** reuse the approved manufacturing command identity scope and normalized key rules from Task 05.5. Different keys cannot create different stock results for one immutable run. No business payload means fingerprint includes stable target/policy, not timestamps or changing grants. Do not change old command fingerprints or reinterpret earlier cached completion successes.
- **Stock preservation:** lock all participants, subtract active sales reservations, and retain sales payment/status gates. No negative-stock override, implicit replenishment, balancing adjustment, cancellation deletion or scrap-as-finished-stock shortcut.
- **Cutover:** marker-based, not timestamp-based. No automatic adoption/backfill of legacy or execution-only runs, even if material JSON is null. Seeded WORK_ORDER rows may already affect stores; leave them untouched. Mixed old/new writers are unsafe: drain or otherwise block old writers that lack shared locks or the policy-aware closure gate before enabling policy-1 plans. Approve that rollout separately; this document authorizes no deployment. Disable rollout if prerequisites cannot be proven.
- **Audit:** distinguish physical operator/completer from inventory poster, calculated consumption from measurement, and run state from posting state. Full provenance belongs in constrained audit storage, not free-text notes or truncated IDs.
- **Cleanup/rollback:** tests use isolated approved targets/fixtures. Append-only protection may require isolated-database disposal or transaction rollback for cleanup, not production trigger disabling. After live postings, roll forward or design an audited compensating movement; destructive downgrade/reversal is not implemented here.
- **Out of scope:** financial costing/valuation, measured material overrides, procurement/replenishment API, new transfer API, exclusive demand allocation, lots/serials, recursive explosion, automatic sub-work orders, negative-stock privilege, corrections/reversals, and historical reconciliation. Escalate if required for the actual production rollout.

### VII. Validation Strategy

Implementation-time commands below are not evidence that tests have run while writing this ADS. Use Java 25. Obtain and activate the user's approved Python virtual environment and explicitly confirm disposable database targets before running Python/migration/DB checks. Database-test skips do not count as acceptance.

- **Compile/proxy:** `rtk proxy ./gradlew compileKotlin compileJava`; verify every changed Java contract, implementation, fake and generated-proxy input. Do not edit generated output.
- **Format/syntax:** confirm existing formatter tooling in Chunk 0; none was declared in the previously inspected build file. Preserve style and run `rtk git diff --check`; run the repository's formatter if one is subsequently found. Python revision: `rtk python -m py_compile <confirmed-revision>` after environment approval.
- **Assets/parity:** `rtk python scripts/verify_openapi_assets.py`; `rtk proxy ./gradlew test --tests com.literp.contract.OpenApiOperationIdRegistrationTest --tests com.literp.contract.BrunoCollectionContractTest --tests com.literp.security.SecurityPolicyTest`. Parse the actual router, not just equal YAML/JSON.
- **New focused tests (proposed):** `rtk proxy ./gradlew test --tests com.literp.repository.InventoryStockGuardTest --tests com.literp.repository.ManufacturingInventoryPostingRepositoryTest`; execute only once the named classes exist.
- **Cross-domain regression:** `rtk proxy ./gradlew test --tests com.literp.repository.OrderProcessRepositoryTransactionTest --tests com.literp.repository.MasterDataRepositoryTest --tests com.literp.verticle.OrderProcessHttpIntegrationTest`; preserve POS shift/actor, payment and reservation semantics.
- **Manufacturing HTTP (proposed):** `rtk proxy ./gradlew test --tests com.literp.verticle.ManufacturingWorkOrderHttpIntegrationTest --tests com.literp.contract.ManufacturingWorkOrderContractTest`; posting routes, anti-enumeration, capabilities, revoked-grant replay, no client actor overrides, UNPOSTED/POSTED/history states and closure gate.
- **Migration:** `rtk python scripts/verify_migrations.py` in approved environment; fresh install plus seeded upgrade, one head, constrained run/parent/movement links, immutable records, legacy byte/value preservation and marker defaults. Never reset a migrated database to fake fresh-install evidence.
- **Arithmetic:** full/partial/all-scrap runs; allowance 0/100; fractional CEILING; multiple sequential runs; decimal precision/overflow; nested stocked component; incorrect UOM/type; weighted yield remains Task 05.5's formula. Check movement direction/null endpoints/reference/run links exactly.
- **Concurrency:** barrier-coordinated independent transactions, not sleeps: two posters same run same/different keys; two work orders competing for a component; manufacturing versus ordinary and POS confirm; fulfillment/reservation release versus consumption; closure versus posting; product-UOM update versus plan/post; MTO duplicate link and order cancellation versus planning. Assert no duplicate movements or overspent unreserved stock under supported writers.
- **Atomic failure injection:** fail after first OUT, before IN, after IN, during link/event/material projection/command storage; verify zero committed delta and retryability. Commit then simulate lost HTTP response; verify one posting on retry.
- **Rollup smoke:** record CURRENT/AVAILABLE before/after OUT/IN and sale reservation/fulfillment, including unrelated locations and reserved-stock protection. All-scrap produces no finished IN. Legacy stock totals unchanged by schema upgrade.
- **MTO acceptance:** only after MTS evidence: DRAFT same-location demand → linked plan → produce/post → ordinary confirm/pay/fulfill; no premature reservation, no automatic sale; source cancellation preserves stock; wrong capability/product/location/quantity/duplicate link rejected. Record the lack of exclusive allocation prominently.
- **Final:** `rtk proxy ./gradlew test` and `rtk proxy ./gradlew build` because shared repositories/security/contracts change; authorized Bruno smoke only with CLI/token/non-production target available. Report external/deployment acceptance pending otherwise.
- **Symbols/diff:** `rtk rg -n 'InventoryStockGuard|ManufacturingInventoryPosting|postProductionRunInventory' src/main src/test`; `rtk git status --short`; `rtk git diff --check`; `rtk git diff -- <changed-files>`. Inspect untracked assets too and report blockers/skips.

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full feature in one pass.

The ladder is longer than the usual 4–8 slices because safe stock posting crosses sales reservations, fulfillment, catalog identity and manufacturing. Those guards must be validated separately before enabling posting. New paths are proposed; abbreviated names below refer to Section III or the exact preceding ADS paths. Each slice must format/check syntax, confirm symbols, run targeted tests, review its diff and record risks. Single-chunk mode stops and asks before a handoff/next chunk; no automatic commits or deployment.

#### Chunk 0: Discovery and Integration Confirmation
- **Goal:** Resolve all business/cutover/MTO decisions and confirm real Task 05.4/05.5 code and every stock writer.
- **Files to read:** Task 05.6 plan; this and preceding ADSs; actual manufacturing implementation; order/product repositories; movement/reservation schema and seeds; loader/security/proxy/test inventories.
- **Commands:** `rtk git status --short --branch`; `rtk git log -1 --oneline`; `rtk rg -n 'inventory_movement|inventory_reservation|FOR UPDATE|material_consumed' src python/database/migration`.
- **Evidence to confirm:** Explicit authorization, readiness, posting/rounding/policy decisions, all writer lock order, actual migration head, formatter/Python/DB targets, restricted versus allocated MTO.
- **Stop condition:** Read-only report. Unresolved stock writer, historical provenance or MTO requirement blocks its dependent slice.

#### Chunk 1: Posting Contracts And Compile-Safe Stubs
- **Goal:** Publish the two proposed posting operations without stock effects.
- **Files to change:** Task 05.5 YAML/JSON/README and handler, two Bruno requests, loader/policy/contract/security/registration inventory deltas. Multi-file exception is necessary for route/spec/policy parity.
- **Symbols to add/change:** `postProductionRunInventory`, `getProductionRunInventoryPosting`, posting schemas/capabilities and scope wiring; minimal handler stubs.
- **Implementation shape:** Authenticate/authorize then explicit 501. No policy marker enabled on planning, no posting row or movement, no closure gate yet. Create route target methods with their registrations.
- **Validation:** Section VII compile, asset/parity/security tests and production router parse; authorized placeholder/unauthorized denial tests.
- **Stop condition:** New operations discoverable but safely unavailable; Task 05.5 behavior unchanged.

#### Chunk 2: Posting Persistence And Historical Exclusion
- **Goal:** Establish additive storage and immutable links without replaying history.
- **Files to change:** One new migration after actual head; `scripts/verify_migrations.py` assertions.
- **Symbols to add/change:** Inventory policy marker, posting/header/line constraints and append-only guards, approved actor-field treatment.
- **Implementation shape:** Section III persistence except gated MTO link; null markers on all old orders; legacy movement values untouched. API remains 501; new orders still execution-only until activation slice.
- **Validation:** Section VII approved migration/syntax commands, fresh/seeded upgrade, FK/unique/immutability probes and no historical stock delta.
- **Stop condition:** Persistence is safe and verified, not enabled for stock writes.

#### Chunk 3: Shared Product Locks And Sales Confirmation
- **Goal:** Serialize sales reservation creation against the future manufacturing lock target.
- **Files to change:** Proposed `InventoryStockGuard.kt` and its test; `OrderProcessRepository.kt` and transaction tests. Four-file exception is the minimal helper-plus-caller integration.
- **Symbols to add/change:** Lock helper and transaction-local availability helper, ordinary-order locking and confirmation aggregate checks.
- **Implementation shape:** Helpers exist before call sites; parent/context→command→all sorted products; grouped demand and fresh balance read; preserve POS guards. No manufacturing posting enabled.
- **Validation:** Compile; stock-guard and order transaction tests; two orders competing for stock, repeated product lines, zero-stock product lock, same/different command keys and existing POS confirmation regression.
- **Stop condition:** Supported confirmations cannot over-reserve concurrently; no out-of-scope order state change.

#### Chunk 4: Fulfillment And Reservation-Release Serialization
- **Goal:** Make other existing sales stock transitions obey the same protocol.
- **Files to change:** `OrderProcessRepository.kt`; `OrderProcessRepositoryTransactionTest.kt`.
- **Symbols to add/change:** `fulfillSalesOrder`, cancellation path, ordinary parent-lock and reservation-credit checks.
- **Implementation shape:** Preserve payment/status behavior, lock all products before OUT/reservation transitions, count own reservation credit once. Do not rework payment/refund logic. Newly discovered decrement writers require an explicitly reviewed additional slice before activation.
- **Validation:** Compile and order transaction/HTTP commands; fulfillment versus confirm/cancel, reserved-stock protection, payment-gate regression, same/different-key duplicate prevention.
- **Stop condition:** All discovered relevant stock/reservation writers participate or a named blocker stops progression.

#### Chunk 5: Historical Product Unit/Type Guard
- **Goal:** Prevent catalog edits from changing the meaning of existing inventory and recipe quantities.
- **Files to change:** `ProductRepository.kt`; `MasterDataRepositoryTest.kt` (plus HTTP assertions only if needed to prove safe conflict mapping).
- **Symbols to add/change:** `updateProduct` transactional lock/reference validation and safe conflict handling.
- **Implementation shape:** Product lock then fresh reference check; reject UOM/type mutation when movement/reservation/BOM references exist. Keep name/metadata/activity changes unchanged. No broad catalog extraction.
- **Validation:** Compile and master-data tests; unused product change allowed; referenced product change rejected; concurrent plan/post/reference creation versus UOM edit; no SQL/error leakage.
- **Stop condition:** Ledger/snapshot unit identity cannot be silently rewritten through supported API paths.

#### Chunk 6: Atomic Run Posting Vertical Slice
- **Goal:** Enable one complete authorized component-OUT/finished-IN path and posting reads.
- **Files to change:** Proposed posting repository/test; actual manufacturing service/proxy/implementation/handler/repository and focused HTTP tests; availability contract/README deltas. Multi-file exception is necessary for a caller-owned transaction across the existing execution boundary.
- **Symbols to add/change:** Deterministic calculation, caller-connection posting helper, durable unique posting, scoped read mapping, policy-aware closure check and new-plan marker assignment.
- **Implementation shape:** Define calculation/persistence helpers with callers; reuse shared stock guard; atomic movement/link/event/command/material projection. Activate policy-1 planning only with working posting and closure gate. Old policy-null work stays execution-only. No nested transaction and no async fire-and-forget posting.
- **Validation:** Compile, focused posting and manufacturing HTTP tests; exact arithmetic, shortage, all-scrap, rollback injection, same/different-key replay, current-scope replay checks and POSTED closure.
- **Stop condition:** Full MTS posting path works atomically; no historical adoption, no MTO implementation yet.

#### Chunk 7: Made-To-Stock Integrated Acceptance Gate
- **Goal:** Prove shared sales/POS/manufacturing balances and record the prerequisite for MTO.
- **Files to change:** Manufacturing HTTP integration tests and approved MTS acceptance evidence/Bruno examples; production code only for narrowly diagnosed fixes within scope.
- **Symbols to add/change:** Cross-domain scenario and barrier-based concurrency assertions.
- **Implementation shape:** Produce/post/sell from one location, verify CURRENT/AVAILABLE and reservation protection, immutable history and no seed drift. Explicitly test all discovered decrement writers.
- **Validation:** Section VII cross-domain/HTTP/migration evidence and final regression commands; authorized MTS Bruno smoke if available.
- **Stop condition:** Documented MTS acceptance with no required-test skips. Stop for explicit approval before gated MTO; failure blocks MTO.

#### Chunk 8: Gated Demand-Linked Made-To-Order
- **Goal:** Add only the approved restricted MTO path after the MTS gate, without changing sales semantics.
- **Files to change:** New link migration/verifier; manufacturing planning/read repository/service/handler and tests; same OpenAPI/Bruno/README assets. Multi-file exception is a single persisted plan/link vertical path. Reconfirm exact file set before editing; split further if observed integration cannot remain reviewable.
- **Symbols to add/change:** Purpose/line input, scoped demand validation, unique link/snapshot, plan fingerprint and safe read projection; active-link release on permitted cancellation.
- **Implementation shape:** Same-location/non-POS DRAFT demand only; no premature sales reservation. Create link and order atomically, preserve source/cancelled history, do not lock sales while posting. If exclusive allocation was requested, stop and design it rather than implement this weaker contract.
- **Validation:** Compile, migration verifier, MTO repository/HTTP/parity tests; duplicate link race, source cancel/change race, quantity/product/location authorization, normal confirm/pay/fulfill after posting and shared-stock contention.
- **Stop condition:** Approved MTO scope works and limitations are documented; no automatic fulfillment/allocation claims.

#### Chunk 9: Final Acceptance And Inventory Boundary Handoff
- **Goal:** Close the task only with evidence for both accepted workflows and safe historical behavior.
- **Files to change:** Tests and acceptance docs, model/API/readme/Phase 05 checklist and synchronized example assets only where verified behavior warrants it.
- **Symbols to add/change:** Final operation availability/evidence inventories; no new runtime feature.
- **Implementation shape:** Review all movement ownership/cutover/rounding/security contracts; reconcile preceding ADS boundary extensions through an explicit documentation change, not retroactive claims that earlier tasks posted stock.
- **Validation:** Section VII full test/build/assets/parity, targeted migration/legacy checks and approved Bruno smoke; final diff and credential review.
- **Stop condition:** Task 05.6 accepted or precise remaining blockers listed. MTO deferred means incomplete scope; external/deployment acceptance remains separately authorized.

### IX. Handoff to `chunked-implementation`

Recommended agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, pre-edit-discipline, safe-python-edit, and post-edit-discipline if available.

Task:
Task 05.6 Manufacturing Inventory Movements.
Use docs/implementation-plan/ads/phase-05-task-6.md.

Mode:
Execute Chunk 0 only. Do not edit files. Confirm repository evidence and stop.
Confirm implemented Task 05.4/05.5 contracts and discover every stock/reservation writer.
Resolve posting, backflush, rounding, cutover, catalog-guard and MTO decisions.
Do not migrate, reconcile historical stock, deploy, or resume earlier implementation tasks.
```

After Chunk 0 and its decisions are accepted:

```text
Use the chunked-implementation skill.
Use docs/implementation-plan/ads/phase-05-task-6.md.
Execute Chunk 1 only. Do not continue to Chunk 2.
Run targeted validation, show git diff, and report skipped checks and risks.
Ask before creating a handoff. Do not commit, migrate a deployment, or deploy.
```

### X. Conclusion and Next Steps

This design makes inventory posting explicit and atomic, preserves the existing ledger/rollups and historical records, and treats shared stock serialization as a prerequisite rather than assuming manufacturing can protect stock alone. MTS acceptance gates the narrower demand-linked MTO path.

Next: approve or revise the decisions in Section II. In particular, confirm calculated backflush versus measured usage, separate posting, same-location production, historical exclusion and whether MTO requires exclusive allocation. Then authorize read-only Chunk 0; no implementation or deployment is authorized by this document.
