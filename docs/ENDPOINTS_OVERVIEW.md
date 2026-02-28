# Literp REST API - Endpoint Overview

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│         HTTP Requests (localhost:8010)                  │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│         OpenAPI Router (Vert.x)                         │
│   Loads: product-catalog + locations + order-process    │
└─────────────────────────────────────────────────────────┘
      ↙               ↓                ↓                ↘
 UOM Routes      Product Routes   Location Routes   Order Routes
      ↓               ↓                ↓                ↓
┌──────────┐    ┌────────────┐   ┌────────────┐   ┌────────────┐
│5 handlers│    │10 handlers │   │6 handlers  │   │8 handlers  │
└──────────┘    └────────────┘   └────────────┘   └────────────┘
      ↓               ↓                ↓                ↓
┌───────────────────────────────────────────────────────────────┐
│ Service Proxy Layer                                           │
│ com.literp.service.master + com.literp.service.order          │
└───────────────────────────────────────────────────────────────┘
                          ↓
┌────────────────────────────────────────────────────────────────┐
│ Repository Layer                                               │
│ UnitOfMeasure, Product, ProductVariant, Location, OrderProcess │
└────────────────────────────────────────────────────────────────┘
                          ↓
┌───────────────────────────────────────────────────────────────┐
│ PostgreSQL (Async RxJava3 + Vert.x SQL Client)                │
└───────────────────────────────────────────────────────────────┘
```

## Resource Hierarchy

```
API Root: /api/v1/

├── UOM
│   ├── GET    /uom
│   ├── POST   /uom
│   ├── GET    /uom/{uomId}
│   ├── PUT    /uom/{uomId}
│   └── DELETE /uom/{uomId}
│
├── Products
│   ├── GET    /products
│   ├── POST   /products
│   ├── GET    /products/{productId}
│   ├── PUT    /products/{productId}
│   ├── DELETE /products/{productId}
│   └── Variants
│       ├── GET    /products/{productId}/variants
│       ├── POST   /products/{productId}/variants
│       ├── GET    /products/{productId}/variants/{variantId}
│       ├── PUT    /products/{productId}/variants/{variantId}
│       └── DELETE /products/{productId}/variants/{variantId}
│
├── Locations
│   ├── GET    /locations
│   ├── POST   /locations
│   ├── GET    /locations/{locationId}
│   ├── GET    /locations/by-code/{code}
│   ├── PUT    /locations/{locationId}
│   └── DELETE /locations/{locationId}
│
└── Order Process
    ├── GET    /orders
    ├── POST   /orders
    ├── GET    /orders/{salesOrderId}
    ├── POST   /orders/{salesOrderId}/lines
    ├── POST   /orders/{salesOrderId}/confirm
    ├── POST   /orders/{salesOrderId}/payments
    ├── POST   /orders/{salesOrderId}/fulfill
    └── POST   /orders/{salesOrderId}/cancel
```

## Endpoint Statistics

| Domain | Count |
|---|---:|
| Unit of Measure | 5 |
| Product | 5 |
| Product Variant | 5 |
| Location | 6 |
| Order Process | 8 |
| **TOTAL** | **29** |

## HTTP Methods & Status Codes

| Method | Purpose | Typical Status Codes |
|---|---|---|
| GET | Read resource(s) | 200, 404, 500 |
| POST | Create/command | 200, 201, 400, 404, 409, 500 |
| PUT | Update | 200, 400, 404, 500 |
| DELETE | Remove/archive | 204, 404, 409, 500 |

## Response Shapes

### Success (single)
```json
{
  "data": { }
}
```

### Success (list)
```json
{
  "data": [ ],
  "pagination": {
    "page": 0,
    "size": 20,
    "totalElements": 100,
    "totalPages": 5
  }
}
```

### Error
```json
{
  "error": "message",
  "status": 400,
  "errorId": "uuid"
}
```

## Handler Flow

```
HTTP Request
    ↓
OpenAPI route match (operationId)
    ↓
Handler extracts + validates input
    ↓
Service proxy call (Vert.x event bus)
    ↓
Repository executes SQL (RxJava Single)
    ↓
Handler returns standardized response
```

## Order Process Flow (Implemented)

```
Draft Order
  ↓ add lines
Confirm (creates reservations)
  ↓ capture payment
Fulfill (creates inventory movements)
  ↓
Fulfilled
```

Cancellation path:
- `DRAFT/CONFIRMED` -> `CANCELLED` (blocked when captured payment exists)

## State Summary

### Sales Order
- `DRAFT -> CONFIRMED -> FULFILLED`
- `DRAFT -> CANCELLED`
- `CONFIRMED -> CANCELLED` (policy-constrained)

### Line / Reservation
- Line: `PENDING -> RESERVED -> FULFILLED`
- Reservation: `RESERVED -> FULFILLED` or `RESERVED -> CANCELLED`

### Payment
- Current API captures as `CAPTURED` entries.

## Notes
- Master data and order process are both active in current API.
- Receipt persistence, refund endpoint flow, and explicit partial-fulfillment endpoint are pending.
