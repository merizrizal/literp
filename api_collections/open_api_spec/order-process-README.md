# Literp Order Process API - OpenAPI Specification

This directory contains the OpenAPI 3.0 specification for the **Literp Order Process API**, covering POS order lifecycle from draft to fulfillment/cancellation.

## Files

- **order-process.yaml** - OpenAPI spec in YAML format (human-readable)
- **order-process.json** - OpenAPI spec in JSON format (tool-compatible)

Both files contain the same specification in different formats.

## API Overview

### Base URL
- Development: `http://localhost:8010/api/v1`
- Production: `https://api.literp.example.com/api/v1`

### Resource Group

#### Order Process (`/orders`)
Manage customer order flow and related stock/payment transitions.

**Operations:**
- `GET /orders` - List sales orders with pagination and optional filters
- `POST /orders` - Create sales order draft
- `GET /orders/{salesOrderId}` - Get order details (lines, reservations, payments)
- `POST /orders/{salesOrderId}/lines` - Add line to draft order
- `POST /orders/{salesOrderId}/confirm` - Confirm order and create reservations
- `POST /orders/{salesOrderId}/payments` - Capture payment
- `POST /orders/{salesOrderId}/fulfill` - Fulfill order and write inventory movement
- `POST /orders/{salesOrderId}/cancel` - Cancel order

## Implemented Process Model

### Core states
- Sales order: `DRAFT -> CONFIRMED -> FULFILLED`
- Cancellation path: `DRAFT/CONFIRMED -> CANCELLED` (policy-constrained)
- Sales order line: `PENDING -> RESERVED -> FULFILLED`
- Reservation: `RESERVED -> FULFILLED | CANCELLED`

### Business constraints currently enforced
- Order lines can only be added while order is `DRAFT`
- Confirm requires at least one order line
- Fulfillment requires:
  - order status `CONFIRMED`
  - captured payment total >= order total
- Cancel is blocked when:
  - order already `FULFILLED`
  - captured payment exists

## Request/Response Examples

### Create Draft Order

**Request**
```bash
POST /api/v1/orders
Content-Type: application/json

{
  "salesChannel": "POS",
  "locationId": "550e8400-e29b-41d4-a716-446655440000",
  "customerId": null,
  "currency": "USD",
  "notes": "Walk-in customer"
}
```

**Response (201)**
```json
{
  "data": {
    "salesOrderId": "b6d5a6f8-0f7d-4f1f-95ce-2fe5e7e2c331",
    "orderNumber": "SO-...",
    "status": "DRAFT",
    "salesChannel": "POS",
    "locationId": "550e8400-e29b-41d4-a716-446655440000",
    "totalAmount": 0,
    "currency": "USD"
  }
}
```

### Add Order Line

**Request**
```bash
POST /api/v1/orders/{salesOrderId}/lines
Content-Type: application/json

{
  "productId": "660e8400-e29b-41d4-a716-446655440000",
  "quantityOrdered": 2,
  "unitPrice": 1500000
}
```

### Confirm Order

```bash
POST /api/v1/orders/{salesOrderId}/confirm
```

### Capture Payment

**Request**
```bash
POST /api/v1/orders/{salesOrderId}/payments
Content-Type: application/json

{
  "paymentMethod": "CASH",
  "amount": 3000000,
  "transactionRef": null
}
```

### Fulfill Order

```bash
POST /api/v1/orders/{salesOrderId}/fulfill
Content-Type: application/json

{
  "createdBy": "cashier-001",
  "notes": "Customer picked up at counter"
}
```

### Cancel Order

```bash
POST /api/v1/orders/{salesOrderId}/cancel
Content-Type: application/json

{
  "reason": "Customer requested cancellation"
}
```

## Status Codes

| Status | Meaning |
|---|---|
| 200 | Success command/read |
| 201 | Created resource/payment |
| 400 | Validation error |
| 404 | Resource not found |
| 409 | State conflict |
| 500 | Internal server error |

## Notes

- This API writes movement rows during fulfillment (`movement_type = OUT`).
- Receipt persistence and refund lifecycle endpoints are not included in this spec yet.
- For deeper technical behavior, refer to `docs/API_IMPLEMENTATION.md`.
