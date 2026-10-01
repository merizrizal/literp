# Implementation Plan and ADS Coverage Review

## Review boundary and document authority

Reviewed against repository revision `2cf843c` on `phase-05-task-3`. This is a documentation/source-inspection review, not a new runtime, database, deployment, or acceptance test run.

No standalone PRD was found among tracked files. [PROJECT_OVERVIEW.md](../knowledge/PROJECT_OVERVIEW.md) supplies the product vision and requirements narrative; it is not a formally approved PRD invented by this review. If a separate external PRD exists, alignment with it remains unverified.

Use these sources together:

1. Product intent: [project overview](../knowledge/PROJECT_OVERVIEW.md), especially sections 4–7.
2. Accepted decisions: [security sequencing](../knowledge/SECURITY_SEQUENCING.md), [project structure](../knowledge/PROJECT_STRUCTURE_DECISION.md), and the [authentication baseline](../knowledge/AUTHENTICATION_BASELINE.md). Preserve their deployment restrictions.
3. Domain contracts: [model design](../knowledge/MODEL_DESIGN.md) and [order/payment/fulfillment process](../knowledge/PROCESS_ORDER_PAYMENT_FULFILLMENT.md). Migrations and runtime code establish actual schema and behavior; conceptual model text does not override them.
4. Delivery ownership: the phase plans and ADSs below. Proposed policy is not approved policy. Historical ADS evidence/signatures describe their drafting baseline, not necessarily current code.
5. Acceptance: recorded tests and [Phase 04 evidence](../knowledge/PHASE_04_COMPLETION_EVIDENCE.md), with their original revision and scope. File presence, source inspection, and passing historical tests are not a fresh acceptance run.

## Complete task-to-ADS inventory

Grouped ADSs count as coverage; each task does not need a duplicate file.

| Plan/task | ADS | Coverage and current boundary |
|---|---|---|
| [00 overview](00-implementation-overview.md) | Not applicable | Roadmap/index, not a runtime feature |
| [01.1–01.5 foundation](01-foundation.md) | **Missing dedicated ADS** | Plan records completion; preserve implementation notes. Do not fabricate retrospective design approval or rerun migrations to fill this documentation gap |
| [02.1–02.5 master data](02-master-data-api.md) | **Missing dedicated ADS** | Plan records completion and concrete response/delete contracts. Future changes need a scoped ADS; existing checkmarks do not prove an ADS existed |
| [03.1–03.3](03-order-inventory-flow.md) | [Order command hardening](ads/phase-03-task-1-2-3.md) | Covered; historical design, plan records completion. Shared stock concurrency remains a later safety gap, not proven by command atomicity |
| [03.4–03.6](03-order-inventory-flow.md) | [Movement semantics and verification](ads/phase-03-task-4-5-6.md) | Covered; historical design. Receipts/refunds belong to 05.3; partial fulfillment is deferred without a scheduled implementation task |
| [04.1–04.2](04-quality-contracts-observability.md) | [Test and contract foundation](ads/phase-04-task-1-2.md) | Covered; recorded local evidence, not a current test run |
| [04.3–04.4](04-quality-contracts-observability.md) | [CI and observability](ads/phase-04-task-3-4.md) | Covered; required-check enforcement and repository request correlation remain open |
| [04.5–04.6](04-quality-contracts-observability.md) | [Security and structure gates](ads/phase-04-task-5-6.md) | Covered; accepted decisions supersede original proposal status; no layout migration |
| Phase 04 closure | [Closure verification](ads/phase-04-closure-verification.md) | Supplemental ADS, not a replacement for task ADSs; does not close remaining criteria |
| [05.0](05-pos-manufacturing-expansion.md#050-authentication-and-authorization-baseline) | [Authentication](ads/phase-05-task-0.md) | Covered; application baseline accepted for development, external/deployment acceptance pending |
| [05.1](05-pos-manufacturing-expansion.md#051-pos-operations-contract) | [POS contracts](ads/phase-05-task-1.md) | Covered; contract-publication slice locally accepted, historical placeholders subsequently replaced |
| [05.2](05-pos-manufacturing-expansion.md#052-pos-terminal-and-shift-api) | [Terminal and shift](ads/phase-05-task-2.md) | Covered; locally accepted per plan, external smoke pending |
| [05.3](05-pos-manufacturing-expansion.md#053-receipt-and-refund-api) | [Receipt and refund](ads/phase-05-task-3.md) | Covered; contracts, persistence foundation and scoped reads present; both writes still 501 |
| [05.4](05-pos-manufacturing-expansion.md#054-bom-api) | [BOM](ads/phase-05-task-4.md) | Covered, proposed only; sequencing and graph-lock/unit-stability decisions block implementation |
| [05.5](05-pos-manufacturing-expansion.md#055-work-order-and-production-run-api) | [Work orders and runs](ads/phase-05-task-5.md) | Covered, proposed only; depends on implemented/accepted BOM contract |
| [05.6](05-pos-manufacturing-expansion.md#056-manufacturing-inventory-movements) | [Inventory posting](ads/phase-05-task-6.md) | Covered, proposed only; depends on runs/snapshots and all stock-writer compatibility |

The Phase 01/02 absence is recorded rather than obscured by an invented historical ADS. A retrospective specification can be commissioned separately if required; until then their plans and implementation references are the documented contracts.

## Requirement alignment and corrections

| Requirement / source | Plan and ADS alignment | Review outcome |
|---|---|---|
| POS is a channel, not a second sales system — overview 4.1, process matrix | 03 and 05.1–05.3 reuse core orders, payments and fulfillment | Aligned. Refunds must not silently restock or cancel fulfilled orders |
| Immutable movement ledger; intent separate from physical stock — overview 4.2–4.3, model inventory sections | 03.4 fixes OUT direction; 05.5 records production results; 05.6 owns atomic OUT/IN posting | Clarified policy-aware closure and posting-owned material projection; completed runs alone do not prove stock posting |
| Manufacturing extends the same inventory model — overview 4.4/5.4 | 05.4 definitions → 05.5 execution → 05.6 ledger | Clarified that nested definitions do not promise recursive explosion, and legacy work orders are read-only unless separately adopted |
| Stable recipe/unit interpretation — model Product/BOMLine | 05.4–05.6 pin definitions and quantities | Catalog currently permits UOM/type edits. Definition immutability alone is insufficient; approve compatible guards or immutable unit snapshots before relying on it |
| No oversell/reservation integrity — 03.3, model reservations | Existing availability checks; 05.6 proposes shared stock serialization | Do not claim global concurrency safety from per-command transactions. Inspect every stock/reservation writer and test competing transactions before enabling manufacturing |
| Acyclic BOM graph — 05.4 proposed lifecycle | Concurrent activation/deprecation across different products | Direct-product locks alone do not prove graph serializability. Block implementation until a common graph-mutation protocol and cross-domain lock order are approved/tested |
| Same-location / multi-location stock — overview 5.3, model Location | Proposed production-only consumption/output in 05.6 | Deliberate initial restriction, not a complete production-to-store transfer workflow; transfer remains separate |
| Made-to-stock and made-to-order — overview future use cases, 05.6 | MTS gate, then demand-linked MTO | MTO is shared stock, not exclusive customer allocation. Maintainer must approve that interpretation or commission allocation design |
| Offline-capable POS — overview 7, model receipt snapshot | Durable command retries and receipt snapshots support parts of the goal | No offline synchronization/conflict-resolution protocol is delivered by these ADSs. Future requirement remains unassigned; do not claim offline operation from idempotency alone |
| Phase-independent invoices/pricing/multi-channel — overview 5.1–5.2 | Basic order prices/channels; receipt extension | Fiscal invoicing/tax/credit notes and richer pricing are not delivered here; explicit scope decisions required before promising them |
| Deny-by-default authentication and retained layout — accepted knowledge decisions | 05.0 and all expansion ADSs | Aligned; no implicit permission inheritance, no deployment approval, no package/assets relocation |
| Validation and operational readiness — 04 plan and closure evidence | Chunked validation, CI, HTTP tests, runtime parsing | Historical evidence retained. External required checks, request-correlated repository logs and deployment evidence stay open |

## Current source evidence used for status corrections

- Four tracked OpenAPI YAML bundles declare 43 operation IDs; `HttpServerVerticle.kt` registers the matching business routes. The original 31-operation security matrix is a historical baseline, not a cap on expansion.
- `PosOperationsHandler.kt`, receipt methods: both GET operations call the service; `generatePosReceipt` and `createPosReceiptRefund` call `respondNotImplemented`.
- `PosOperationsRepository.kt`, `getPosReceiptByNumber` / `listPosReceiptsBySalesOrder`: SQL filters persisted order location; order-list reads are bounded and distinguish missing/hidden orders from an authorized empty list.
- `PosOperationsContractTest.kt`, `placeholderRequests`: only the two write operations remain placeholders. This is inspected test code, not a test result from this review.
- Migration `c7a8e2f4d6b1_pos_receipt_refund_foundation.py` supplies fractional receipt totals, issuance metadata, uniqueness and refund storage. Schema presence does not enable write APIs.
- `OrderProcessRepository.kt`, stock queries: current stock includes IN/ADJUSTMENT at destination, TRANSFER destination/source and OUT at source; available stock subtracts RESERVED quantities. The model's old SQL referenced a nonexistent movement `location_id` and omitted ADJUSTMENT.
- `ProductRepository.updateProduct` still writes `product_type` and `base_uom` without the proposed historical-reference guard.
- The initial migration uses SQL JSON for extensible fields, including `material_consumed`; the model's JSONB wording was conceptual, not actual storage.

## Remaining decisions and stop boundary

- Confirm whether an external approved PRD adds requirements absent from the repository narrative.
- Decide whether retrospective Phase 01/02 ADSs are needed; no runtime work is required solely because they are absent.
- Preserve normal 05.3 → 05.4 → 05.5 → 05.6 sequencing unless independently authorized.
- Approve receipt issuance/refund eligibility and financial semantics before enabling writes; recorded persistence/read work does not approve every proposal in the original ADS. In particular, settle overcaptured-payment versus receipt net/refund totals; paid-but-unfulfilled cancellation remains a separate recovery gap.
- Settle production location, backflush versus measured usage, scrap allowance/rounding, separate posting versus atomic completion, shortage handling, historical cutover and MTO allocation expectations.
- Approve shared stock/catalog/BOM graph locking and rollout compatibility before manufacturing activation. Never auto-replay historical WORK_ORDER movements.
- Assign later delivery ownership if partial fulfillment, offline synchronization, transfers, fiscal documents or exclusive MTO allocation are required. They have no complete implementation ADS in this plan.

## Documentation verification

- Reviewed the tracked Markdown diff and this new coverage report; only files under `docs/` changed.
- `rtk git diff --check` passed.
- A temporary dependency-free Node check verified relative Markdown links/anchors, balanced code fences, all ten required sections in each existing ADS, and exact OpenAPI/router operation-ID parity: four bundles, 43 operations, two write placeholders.
- No repository Markdown formatter configuration was found; formatting was reviewed manually. No formatter dependency was installed.
- No Gradle, Python, database, external provider or Bruno runtime checks were run. Markdown structural checks and source inspection are not runtime acceptance or proof that unresolved design policies are safe.

No source, migration, API asset, deployment, credential, or database changes were made.
