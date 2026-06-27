# 03. Order And Inventory Flow

## Goal

Harden the POS-first order-to-fulfillment workflow while preserving the core
domain rule: sales captures intent, inventory records physical state changes.

## Scope

This phase covers sales order lifecycle endpoints, order lines, reservations,
payments, fulfillment, cancellation, inventory movement writes, and stock
availability behavior.

## Current Completed Work

- [x] `GET /orders` implemented
- [x] `POST /orders` implemented
- [x] `GET /orders/{salesOrderId}` implemented
- [x] `POST /orders/{salesOrderId}/lines` implemented
- [x] `POST /orders/{salesOrderId}/confirm` implemented
- [x] `POST /orders/{salesOrderId}/payments` implemented
- [x] `POST /orders/{salesOrderId}/fulfill` implemented
- [x] `POST /orders/{salesOrderId}/cancel` implemented
- [x] draft order creation defaults sales channel to `POS`
- [x] draft order creation defaults currency to `USD`
- [x] add line requires an existing `DRAFT` order
- [x] add line validates positive quantity and non-negative unit price
- [x] add line checks active product existence
- [x] add line recalculates order total
- [x] confirm requires a `DRAFT` order
- [x] confirm blocks orders without lines
- [x] confirm creates `inventory_reservation` rows
- [x] confirm moves lines to `RESERVED`
- [x] confirm moves order to `CONFIRMED`
- [x] payment capture requires positive amount
- [x] payment capture only allows `CONFIRMED` or `FULFILLED` orders
- [x] payment capture creates `CAPTURED` payment rows
- [x] fulfillment requires `CONFIRMED` order status
- [x] fulfillment requires captured payment total to cover order total
- [x] fulfillment writes `OUT` inventory movements
- [x] fulfillment marks reservations fulfilled
- [x] fulfillment marks lines fulfilled
- [x] fulfillment moves order to `FULFILLED`
- [x] cancellation blocks fulfilled orders
- [x] cancellation blocks orders with captured payments
- [x] cancellation marks non-fulfilled lines cancelled
- [x] cancellation marks active reservations cancelled
- [x] order detail returns order, lines, reservations, and payments
- [x] order process OpenAPI YAML exists
- [x] order process OpenAPI JSON exists
- [x] Bruno requests exist for the order lifecycle
- [x] Bruno requests exist for current and available stock queries
- [x] API testing guide includes end-to-end curl workflow
- [x] API testing guide documents idempotent order commands and stock availability checks

## Ordered Tasks

### 03.1 Transactional Order Commands

Estimate: 3-5 engineer-days

Tasks:

- [x] Wrap add-line total recalculation in a transaction
- [x] Wrap confirm reservation and status updates in a transaction
- [x] Wrap fulfillment movement, reservation, line, and order updates in a transaction
- [x] Wrap cancellation line, reservation, and order updates in a transaction
- [x] Add rollback tests for at least one failure point in each multi-step command

Done when:

- [x] Multi-table order commands commit or roll back as one unit
- [x] Partial reservation, payment, movement, line, or order-state writes cannot persist after command failure
- [x] Transaction behavior is covered by integration tests

### 03.2 Command Idempotency And Order Auditability

Estimate: 2-4 engineer-days

Tasks:

- [x] Add idempotency strategy for confirm, payment, fulfill, and cancel commands
- [x] Add robust order number generation strategy, preferably database-backed
- [x] Add order event history or audit notes for state transitions
- [x] Document idempotency behavior for clients

Done when:

- [x] Retried command requests cannot duplicate reservations, payments, or movements
- [x] Order numbers remain unique under concurrent creation
- [x] State transitions leave a readable audit trail

### 03.3 Stock Availability And Reservation Policy

Estimate: 2-4 engineer-days

Tasks:

- [x] Add current-stock query API
- [x] Add available-stock query API
- [x] Compute available stock from movements minus active reservations
- [x] Enforce stock availability before reservation creation
- [x] Decide whether reservations can oversell by channel or policy

Done when:

- [x] Clients can inspect current and available stock by product and location
- [x] Confirmation cannot reserve more stock than policy allows
- [x] Reservation policy is documented and tested

### 03.4 Fulfillment Movement Semantics

Estimate: 1-2 engineer-days

Tasks:

- [x] Replace the fulfillment `to_location_id = from_location_id` workaround with explicit movement semantics or schema change
- [x] Update inventory movement documentation for sales fulfillment
- [x] Update seed or test data if movement semantics change
- [x] Verify stock rollup queries still produce correct results

Done when:

- [x] Sales fulfillment movements are semantically clear
- [x] Stock rollups handle sales `OUT` movements correctly
- [x] OpenAPI, docs, and tests reflect the chosen movement semantics

### 03.5 Payment, Receipt, And Partial Flow Design

Estimate: 2-4 engineer-days

Tasks:

- [x] Document receipt generation as Phase 05 ownership (`05.3 Receipt And Refund API`)
- [x] Document refund flow and payment reversal as Phase 05 ownership (`05.3 Receipt And Refund API`)
- [x] Document partial fulfillment as deferred to a later phase
- [x] Document partial payment rules: multiple captured payment rows may exist; fulfillment still requires captured total to cover order total

Done when:

- [x] Receipt, refund, partial fulfillment, and partial payment decisions are documented and linked to their owning phase or guardrail
- [x] Any flow kept in Phase 03 has implementation tasks and tests
- [x] Any deferred flow is linked to the later phase that owns it

### 03.6 Order Flow Verification

Estimate: 2-3 engineer-days

Tasks:

- [x] Add integration tests for every valid state transition
- [x] Add integration tests for invalid state transitions
- [x] Add integration tests for inventory movement side effects
- [x] Add integration tests for payment guardrails
- [x] Keep Bruno order workflow examples aligned with final behavior

Done when:

- [x] Order lifecycle happy path is automated-test covered
- [x] Order lifecycle guardrails are automated-test covered
- [x] Inventory and payment side effects are verified from the database

## Assumptions

- Inventory movements remain immutable.
- Reservations represent earmarked stock, not physical movement.
- Fulfillment remains the authoritative stock deduction event.
- Payment capture does not automatically imply fulfillment.
- Cancellation after captured payment requires refund orchestration first.

## Definition of Done

- [x] The full order lifecycle is atomic where writes span multiple tables
- [x] Stock availability is computed from movements minus active reservations
- [x] Fulfillment creates auditable movement records with correct location semantics
- [x] Payment, cancellation, and fulfillment guardrails are automated-test covered
- [x] Order process docs, OpenAPI, and Bruno collection match actual behavior
