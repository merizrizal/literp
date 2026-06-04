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
- [x] API testing guide includes end-to-end curl workflow

## Remaining Work

- [ ] Wrap add-line total recalculation in a transaction
- [ ] Wrap confirm reservation and status updates in a transaction
- [ ] Wrap fulfillment movement, reservation, line, and order updates in a transaction
- [ ] Wrap cancellation line, reservation, and order updates in a transaction
- [ ] Add idempotency strategy for confirm, payment, fulfill, and cancel commands
- [ ] Add current-stock and available-stock query APIs
- [ ] Enforce stock availability before reservation creation
- [ ] Decide whether reservations can oversell by channel or policy
- [ ] Replace the fulfillment `to_location_id = from_location_id` workaround with explicit movement semantics or schema change
- [ ] Add receipt generation after successful POS fulfillment
- [ ] Add refund flow and payment reversal rules
- [ ] Add partial fulfillment support
- [ ] Add partial payment rules if needed by POS and B2B channels
- [ ] Add order event history or audit notes for state transitions
- [ ] Add robust order number generation strategy, preferably database-backed
- [ ] Add integration tests for every valid state transition
- [ ] Add integration tests for invalid state transitions
- [ ] Add integration tests for inventory movement side effects

## Assumptions

- Inventory movements remain immutable.
- Reservations represent earmarked stock, not physical movement.
- Fulfillment remains the authoritative stock deduction event.
- Payment capture does not automatically imply fulfillment.
- Cancellation after captured payment requires refund orchestration first.

## Definition of Done

- [ ] The full order lifecycle is atomic where writes span multiple tables
- [ ] Stock availability is computed from movements minus active reservations
- [ ] Fulfillment creates auditable movement records with correct location semantics
- [ ] Payment, cancellation, and fulfillment guardrails are automated-test covered
- [ ] Order process docs, OpenAPI, and Bruno collection match actual behavior
