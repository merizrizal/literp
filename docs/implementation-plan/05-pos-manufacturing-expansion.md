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

- [ ] Create POS Operations OpenAPI spec
- [ ] Define terminal operations
- [ ] Define shift operations
- [ ] Define receipt lookup operations
- [ ] Add Bruno request skeletons for POS operations

Done when:

- [ ] POS Operations API has an agreed OpenAPI contract
- [ ] Bruno has placeholder requests for every planned POS endpoint
- [ ] POS scope is clearly separated from core sales order behavior

### 05.2 POS Terminal And Shift API

Estimate: 4-7 engineer-days

Tasks:

- [ ] Add terminal list/create/get/update/deactivate endpoints
- [ ] Add shift open/close endpoints
- [ ] Add current-shift lookup by terminal
- [ ] Add cashier/operator attribution to POS order workflows
- [ ] Add cash reconciliation rules for closing shifts

Done when:

- [ ] A POS operator can open and close a shift
- [ ] Active terminal and shift state can be queried
- [ ] POS order workflows can be attributed to an operator or shift

### 05.3 Receipt And Refund API

Estimate: 4-7 engineer-days

Tasks:

- [ ] Add receipt generation from fulfilled POS order
- [ ] Add receipt lookup by receipt number
- [ ] Add receipt lookup by sales order
- [ ] Add refund endpoint and receipt adjustment behavior
- [ ] Add POS integration tests
- [ ] Complete Bruno requests for POS operations

Done when:

- [ ] Fulfilled POS orders can produce receipts
- [ ] Receipts can be retrieved by receipt number and sales order
- [ ] Refund behavior is explicit, auditable, and tested

### 05.4 BOM API

Estimate: 3-5 engineer-days

Tasks:

- [ ] Create BOM OpenAPI spec
- [ ] Add BOM create/update/activate/deprecate endpoints
- [ ] Add BOM line management endpoints
- [ ] Add Bruno requests for BOM operations
- [ ] Add BOM integration tests

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

Done when:

- [ ] Manufacturing consumes components through the inventory movement ledger
- [ ] Manufacturing produces finished goods through the inventory movement ledger
- [ ] POS, sales, and manufacturing stock effects are visible in the same stock rollups

## Assumptions

- POS remains a sales channel adapter.
- Manufacturing produces inventory through the same movement ledger used by sales fulfillment.
- Work orders should not require changes to sales order semantics.
- Accounting integration is outside this phase.
- Phase 05 should not begin large new API additions until the project structure gate is resolved.

## Definition of Done

- [ ] POS operators can open a shift, sell, fulfill, generate receipt, and close the shift
- [ ] Manufacturing users can define a BOM, run a work order, consume materials, and produce finished goods
- [ ] POS and manufacturing movements are auditable in the same inventory ledger
- [ ] POS and manufacturing APIs have OpenAPI specs, Bruno requests, docs, and integration tests
