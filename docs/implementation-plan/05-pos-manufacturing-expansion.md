# 05. POS And Manufacturing Expansion

## Goal

Expose the POS and manufacturing capabilities that are already anticipated by
the schema without turning POS into the center of the core domain model.

## Scope

This phase covers POS terminals, shifts, receipts, refunds, BOMs, work orders,
production runs, material consumption, production output, and inventory
movements created by manufacturing operations.

## Entry Gate

- [x] The accepted [security sequencing decision](../knowledge/SECURITY_SEQUENCING.md) is implemented through 05.0 before POS and manufacturing expansion begins, under the maintainer-approved deferred-deployment exception in [`AUTHENTICATION_BASELINE.md`](../knowledge/AUTHENTICATION_BASELINE.md)
- [x] Phase 04 project structure gate is recorded complete; 04.6 is accepted and complete, while unrelated Phase 04 closure criteria remain separately tracked
- [x] Backend package layout is confirmed by the accepted [project structure decision](../knowledge/PROJECT_STRUCTURE_DECISION.md) before POS and manufacturing handlers, services, and repositories are added
- [x] API asset layout is confirmed by the accepted [project structure decision](../knowledge/PROJECT_STRUCTURE_DECISION.md) before new POS and manufacturing OpenAPI and Bruno files are added

### Accepted Layout Guidance

The accepted [project structure decision](../knowledge/PROJECT_STRUCTURE_DECISION.md) retains the current layer-based backend layout and `api_collections` asset roots through Phase 05. Catalog, location, and order remain in their existing layers; inventory remains with order-process behavior.

When concrete POS or manufacturing behavior is added, it follows the existing Kotlin handler/repository/service layers. Proposed `service/pos` and `service/manufacturing` Java proxy groups are created only when concrete proxy services require confirmed codegen contracts. New OpenAPI YAML/JSON pairs and Bruno requests remain in their current roots and update their loader, verifier, test, documentation, and CI consumers in the same feature slice.

## Current Completed Work

### POS Data Foundation

- [x] `pos_terminal` table exists
- [x] `pos_shift` table exists
- [x] `receipt` table exists
- [x] seed data includes POS terminal
- [x] seed data includes POS shift
- [x] seed data includes receipt
- [x] simulated seed data includes additional POS shifts and receipts

### Manufacturing Data Foundation

- [x] `bill_of_material` table exists
- [x] `bom_line` table exists
- [x] `work_order` table exists
- [x] `production_run` table exists
- [x] seed data includes a BOM for the signature latte product
- [x] seed data includes BOM lines for raw materials and packaging
- [x] seed data includes completed and planned work orders
- [x] seed data includes production run records
- [x] seed data includes manufacturing-related inventory movements
- [x] simulated seed data includes 14 days of production and sales activity

## Design Coverage and Sequencing

Every task 05.0–05.6 has a dedicated ADS; see the [coverage review](ADS_COVERAGE_REVIEW.md) for links and requirement gaps. ADS presence does not mean approval or implementation. Continue 05.3 → 05.4 → 05.5 → 05.6 in order unless independently authorized. The manufacturing designs must agree on legacy policy, recipe/unit stability, graph/stock lock order, and policy-aware run posting before implementation.

The added manufacturing prerequisites and acceptance criteria below describe the aligned proposal, not new policy approval. If atomic complete-and-post or exclusive MTO allocation is chosen instead, revise both the plan and dependent ADSs before enabling behavior.

## Ordered Tasks

### 05.0 Authentication And Authorization Baseline

Estimate: Defined by the approved authentication implementation ADS

Tasks:

- [x] Create and approve the authentication implementation ADS from the [security sequencing decision](../knowledge/SECURITY_SEQUENCING.md)
- [x] Implement the approved authentication and deny-by-default authorization baseline for the current protected surface
- [x] Validate credential rejection, authorized access, denied capability/resource scope, operational-route restriction, and public probe behavior
- [x] Preserve existing lifecycle, idempotency, response, and request-ID contracts under authorization

Done when:

- [x] The accepted security decision is implemented and validated by the approved authentication ADS
- [x] Current business operations cannot be reached without authorized credentials and scope
- [x] POS and manufacturing expansion can build on the authenticated baseline without widening access implicitly

**Deferred deployment acceptance:** The maintainer-approved exception permits
05.1 development while Bruno, genuine-provider, deployment, and timed JWKS
rotation evidence remain pending. It does not authorize untrusted deployment.

### 05.1 POS Operations Contract

Estimate: 2-3 engineer-days

Tasks:

- [x] Create POS Operations OpenAPI spec
- [x] Define terminal operations
- [x] Define shift operations
- [x] Define receipt lookup operations
- [x] Add Bruno request skeletons for POS operations

Done when:

- [x] POS Operations API has an agreed OpenAPI contract
- [x] Bruno has placeholder requests for every planned POS endpoint
- [x] POS scope is clearly separated from core sales order behavior

**Acceptance note:** Task 05.1 is locally accepted as an authenticated
`501 NOT_IMPLEMENTED` contract-publication slice. The OpenAPI pair verifier,
registration and POS contract tests, and filesystem-backed Bruno parity checks
provide local evidence. Native Bruno import/smoke validation remains unavailable
because the installed desktop application cannot start its Linux sandbox helper.
Genuine-provider, deployment, timed-JWKS, audit-ownership, and external
authenticated acceptance remain deferred under the authentication baseline.
At the time, this 05.1 contract acceptance did not start or authorize behavior
work. Task 05.2 was authorized separately; Task 05.3 remains out of scope.

### 05.2 POS Terminal And Shift API

Estimate: 4-7 engineer-days

Tasks:

- [x] Add terminal list/create/get/update/deactivate endpoints
- [x] Add shift open/close endpoints
- [x] Add current-shift lookup by terminal
- [x] Add cashier/operator attribution to POS order workflows
- [x] Add cash reconciliation rules for closing shifts

Done when:

- [x] A POS operator can open and close a shift
- [x] Active terminal and shift state can be queried
- [x] POS order workflows can be attributed to an operator or shift

**Local acceptance evidence:**

- `rtk proxy ./gradlew test --tests com.literp.verticle.AuthenticationHttpIntegrationTest`:
  9 tests passed, 0 skipped; includes terminal creation, shift open, attributed
  order/cash capture, reconciliation, persistence/actor checks, and fulfillment
  after close.
- `rtk proxy ./gradlew test`: 92 tests passed, 0 skipped, failures, or errors;
  run against the confirmed disposable `literp_test` database.
- `rtk proxy ./gradlew build`: passed; CI build/test/migration/OpenAPI jobs were
  inspected and already cover these checks.
- `rtk python scripts/verify_migrations.py`: passed at Alembic head
  `b2d6f8a1c4e3`.
- `rtk python scripts/verify_openapi_assets.py`: all four YAML/JSON pairs
  synchronized; POS contract/Bruno and operation-registration tests passed.
- The approved non-production Bruno runtime smoke remains pending: no
  `LITERP_ACCESS_TOKEN` or `bru` CLI is available in this environment.
  Filesystem contract parity and authenticated HTTP integration coverage passed.
  No provider, deployment, timed-JWKS, audit-ownership, or external acceptance
  is claimed.

At the 05.2 acceptance boundary, receipt lookups were placeholders. Task 05.3
has since implemented both scoped reads; receipt generation and refund writes
remain authenticated `501 NOT_IMPLEMENTED`. The test counts above are historical
05.2 acceptance evidence, not current-suite results.

### 05.3 Receipt And Refund API

Estimate: 4-7 engineer-days

Tasks:

- [ ] Add receipt generation from fulfilled POS order
- [x] Add receipt lookup by receipt number
- [x] Add receipt lookup by sales order
- [ ] Add refund endpoint and receipt adjustment behavior
- [ ] Add POS integration tests
- [ ] Complete Bruno requests for POS operations

Done when:

- [ ] Fulfilled POS orders can produce receipts
- [x] Receipts can be retrieved by receipt number and sales order
- [ ] Refund behavior is explicit, auditable, and tested

**Current implementation boundary:** [05.3 ADS](ads/phase-05-task-3.md) Chunks 1–3 have contracts, additive receipt/refund persistence, and scoped receipt reads at `1ed2617`. Only `generatePosReceipt` and `createPosReceiptRefund` remain 501. Existing read/contract tests do not complete issuance/refund integration acceptance. No new tests or database checks were run by the documentation review.

### 05.4 BOM API

Estimate: 3-5 engineer-days

Tasks:

- [ ] Create BOM OpenAPI spec
- [ ] Add BOM create/update/activate/deprecate endpoints
- [ ] Add BOM line management endpoints
- [ ] Add Bruno requests for BOM operations
- [ ] Add BOM integration tests
- [ ] Approve a serializable graph-mutation/lock protocol and stable base-UOM interpretation shared with catalog and execution before relying on immutable recipes

Done when:

- [ ] Users can define and activate a bill of materials
- [ ] BOM lines can be managed without direct database edits
- [ ] BOM lifecycle behavior is tested and documented

### 05.5 Work Order And Production Run API

Estimate: 5-9 engineer-days

Tasks:

- [ ] Create Work Order OpenAPI spec
- [ ] Add work order plan/start/complete/cancel endpoints
- [ ] Add production run start/complete endpoints
- [ ] Track yield and scrap behavior
- [ ] Preserve execution-only completion until 05.6 explicitly enables stock policy; distinguish completed results from posted inventory and retain legacy read-only rules
- [ ] Add Bruno requests for work order and production run operations

Done when:

- [ ] Users can plan, start, complete, and cancel work orders
- [ ] Production runs capture operator, output, and scrap data
- [ ] Work order lifecycle behavior is tested and documented

### 05.6 Manufacturing Inventory Movements

Estimate: 4-7 engineer-days

Tasks:

- [ ] Write material consumption inventory `OUT` movements
- [ ] Write finished-goods inventory `IN` movements
- [ ] Add made-to-stock workflow
- [ ] Add made-to-order workflow only after the basic production loop is stable
- [ ] Add manufacturing integration tests
- [ ] Approve explicit per-run posting versus atomic complete-and-post, backflush/rounding, location and shortage policies
- [ ] Implement and validate shared stock/reservation serialization and catalog unit/type guards across all relevant writers before enabling posting
- [ ] Enforce policy-aware closure and immutable posting audit/material projection without replaying historical movements
- [ ] Approve historical cutover/rollout and demand-linked versus exclusively allocated MTO scope

Done when:

- [ ] Manufacturing consumes components through the inventory movement ledger
- [ ] Manufacturing produces finished goods through the inventory movement ledger
- [ ] POS, sales, and manufacturing stock effects are visible in the same stock rollups
- [ ] Competing stock/reservation writers cannot overspend unreserved stock under the approved shared protocol
- [ ] Policy-enabled work orders close only after every completed run is posted; historical/execution-only orders are not automatically adopted
- [ ] Both MTS and the explicitly approved MTO scope have acceptance evidence; deferred MTO means this task remains incomplete

## Assumptions

- POS remains a sales channel adapter.
- Manufacturing produces inventory through the same movement ledger used by sales fulfillment.
- Work orders should not require changes to sales order semantics.
- Accounting integration is outside this phase.
- The project structure gate is resolved; retain its accepted layout through Phase 05.
- Production-only locations, calculated consumption, separate posting, and shared-stock MTO are proposed restrictions, not settled product requirements; see the [05.6 ADS](ads/phase-05-task-6.md).
- Offline synchronization, exclusive demand allocation, fiscal documents, partial fulfillment and production-to-store transfer APIs are not delivered by this phase; assign separate requirements/design ownership if needed.

## Definition of Done

- [ ] POS operators can open a shift, sell, fulfill, generate receipt, and close the shift
- [ ] Manufacturing users can define a BOM, run a work order, consume materials, and produce finished goods
- [ ] POS and manufacturing movements are auditable in the same inventory ledger
- [ ] POS and manufacturing APIs have OpenAPI specs, Bruno requests, docs, and integration tests
