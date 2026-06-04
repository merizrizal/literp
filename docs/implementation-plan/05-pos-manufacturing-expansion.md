# 05. POS And Manufacturing Expansion

## Goal

Expose the POS and manufacturing capabilities that are already anticipated by
the schema without turning POS into the center of the core domain model.

## Scope

This phase covers POS terminals, shifts, receipts, refunds, BOMs, work orders,
production runs, material consumption, production output, and inventory
movements created by manufacturing operations.

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

## Remaining POS Work

- [ ] Create POS Operations OpenAPI spec
- [ ] Add terminal list/create/get/update/deactivate endpoints
- [ ] Add shift open/close endpoints
- [ ] Add current-shift lookup by terminal
- [ ] Add receipt generation from fulfilled POS order
- [ ] Add receipt lookup by receipt number
- [ ] Add receipt lookup by sales order
- [ ] Add cashier/operator attribution to POS order workflows
- [ ] Add cash reconciliation rules for closing shifts
- [ ] Add refund endpoint and receipt adjustment behavior
- [ ] Add POS integration tests
- [ ] Add Bruno requests for POS operations

## Remaining Manufacturing Work

- [ ] Create BOM OpenAPI spec
- [ ] Add BOM create/update/activate/deprecate endpoints
- [ ] Add BOM line management endpoints
- [ ] Create Work Order OpenAPI spec
- [ ] Add work order plan/start/complete/cancel endpoints
- [ ] Add production run start/complete endpoints
- [ ] Write material consumption inventory `OUT` movements
- [ ] Write finished-goods inventory `IN` movements
- [ ] Track yield and scrap behavior
- [ ] Add made-to-stock workflow
- [ ] Add made-to-order workflow only after the basic production loop is stable
- [ ] Add manufacturing integration tests
- [ ] Add Bruno requests for manufacturing operations

## Assumptions

- POS remains a sales channel adapter.
- Manufacturing produces inventory through the same movement ledger used by sales fulfillment.
- Work orders should not require changes to sales order semantics.
- Accounting integration is outside this phase.

## Definition of Done

- [ ] POS operators can open a shift, sell, fulfill, generate receipt, and close the shift
- [ ] Manufacturing users can define a BOM, run a work order, consume materials, and produce finished goods
- [ ] POS and manufacturing movements are auditable in the same inventory ledger
- [ ] POS and manufacturing APIs have OpenAPI specs, Bruno requests, docs, and integration tests
