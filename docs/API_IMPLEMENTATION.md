# Literp REST API Implementation

## Overview
This document describes the implemented REST API surface in Literp using Vert.x 5.0.8, Kotlin 2.3.10, and RxJava3.

Current API coverage includes:
- Product catalog master data
- Inventory locations master data
- POS order process flow (draft, lines, confirm, payment capture, fulfill, cancel)

## Architecture

### Technology Stack
- **Framework**: Vert.x 5.0.8 (reactive, event-driven)
- **Language**: Kotlin 2.3.10 on Java 25
- **Async Model**: RxJava3 + Vert.x Futures
- **Database**: PostgreSQL via Vert.x PG Client
- **API Spec**: OpenAPI 3.0 with `vertx-web-openapi-router`
- **Build**: Gradle (Kotlin DSL)

### Project Structure

```
src/main/kotlin/com/literp/
├── App.kt
├── config/
│   └── Config.kt
├── db/
│   └── DatabaseConnection.kt
├── repository/
│   ├── BaseRepository.kt
│   ├── UnitOfMeasureRepository.kt
│   ├── ProductRepository.kt
│   ├── ProductVariantRepository.kt
│   ├── LocationRepository.kt
│   └── OrderProcessRepository.kt
└── verticle/
    ├── MainVerticle.kt
    ├── HttpServerVerticle.kt
    └── handler/
        ├── BaseHandler.kt
        ├── UnitOfMeasureHandler.kt
        ├── ProductHandler.kt
        ├── LocationHandler.kt
        └── OrderProcessHandler.kt
```

```
src/main/java/com/literp/service/
├── master/
│   ├── UnitOfMeasureService.java
│   ├── ProductService.java
│   ├── ProductVariantService.java
│   ├── LocationService.java
│   └── package-info.java
└── order/
    ├── OrderProcessService.java
    └── package-info.java
```

## OpenAPI Contracts
The server loads and mounts 3 OpenAPI contracts under `/api/v1`:
- `api_collections/open_api_spec/product-catalog.yaml`
- `api_collections/open_api_spec/locations.yaml`
- `api_collections/open_api_spec/order-process.yaml`

## Endpoint Inventory

### Master Data

#### Unit of Measure (5)
- `GET /uom`
- `POST /uom`
- `GET /uom/{uomId}`
- `PUT /uom/{uomId}`
- `DELETE /uom/{uomId}`

#### Product (5)
- `GET /products`
- `POST /products`
- `GET /products/{productId}`
- `PUT /products/{productId}`
- `DELETE /products/{productId}`

#### Product Variant (5)
- `GET /products/{productId}/variants`
- `POST /products/{productId}/variants`
- `GET /products/{productId}/variants/{variantId}`
- `PUT /products/{productId}/variants/{variantId}`
- `DELETE /products/{productId}/variants/{variantId}`

#### Location (6)
- `GET /locations`
- `POST /locations`
- `GET /locations/{locationId}`
- `GET /locations/by-code/{code}`
- `PUT /locations/{locationId}`
- `DELETE /locations/{locationId}`

### Order Process (8)
- `GET /orders` - list sales orders
- `POST /orders` - create sales order draft
- `GET /orders/{salesOrderId}` - get order with lines/reservations/payments
- `POST /orders/{salesOrderId}/lines` - add line to draft order
- `POST /orders/{salesOrderId}/confirm` - confirm order and reserve stock
- `POST /orders/{salesOrderId}/payments` - capture payment
- `POST /orders/{salesOrderId}/fulfill` - fulfill order and write inventory movements
- `POST /orders/{salesOrderId}/cancel` - cancel order

### Total
- **29 endpoints** across **5 API domains**.

## Repository Layer

### BaseRepository
Shared concerns:
- Repository logger initialization
- Startup DB connectivity check

### Master Data Repositories
- `UnitOfMeasureRepository`
- `ProductRepository`
- `ProductVariantRepository`
- `LocationRepository`

### Order Process Repository
`OrderProcessRepository` implements:
- Sales order listing with pagination/filtering
- Draft creation
- Order detail aggregation (order + lines + reservations + payments)
- Line insertion and order total recalculation
- Confirmation and reservation creation
- Payment capture and captured-balance calculation
- Fulfillment and inventory movement creation
- Cancellation with payment and state guardrails

## Service Proxy Layer

### Master service module
Package: `com.literp.service.master`
- `UnitOfMeasureService`
- `ProductService`
- `ProductVariantService`
- `LocationService`

### Order service module
Package: `com.literp.service.order`
- `OrderProcessService`

Kotlin implementations:
- `com.literp.service.master.impl.*`
- `com.literp.service.order.impl.OrderProcessServiceImpl`

## Handler Layer

Handlers delegate to service proxies and standardize response/error formatting via `BaseHandler`.

- `UnitOfMeasureHandler`
- `ProductHandler`
- `LocationHandler`
- `OrderProcessHandler`

`BaseHandler` utilities include:
- `putResponse(...)`
- `putSuccessResponse(...)`
- `putErrorResponse(...)`

## API Response Format

### Success (Single)
```json
{
  "data": { }
}
```

### Success (List)
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
  "error": "Description",
  "status": 400,
  "errorId": "uuid"
}
```

## HTTP Status Code Usage
- `200` successful read/update/process command
- `201` created resources or captured payment creation
- `204` delete success for hard/soft-delete endpoints
- `400` validation error
- `404` resource not found
- `409` state conflict or uniqueness conflict
- `500` unexpected internal failure

## Order Process State Rules (Implemented)

### Sales order
- `DRAFT -> CONFIRMED -> FULFILLED`
- `DRAFT -> CANCELLED`
- `CONFIRMED -> CANCELLED` only when no captured payment

### Sales order line
- `PENDING -> RESERVED -> FULFILLED`
- `PENDING/RESERVED -> CANCELLED`

### Reservation
- `RESERVED -> FULFILLED`
- `RESERVED -> CANCELLED`

### Payment
- API currently writes `CAPTURED` entries directly.

## Data Validation and Constraints

### Uniqueness
- UOM code unique
- Product SKU unique
- Variant SKU unique
- Location code unique

### Required field examples
- UOM: `code`, `name`
- Product: `sku`, `name`, `productType`, `baseUom`
- Variant: `sku`, `name`
- Location: `code`, `name`, `locationType`
- Sales order draft: `locationId`
- Order line: `productId`, `quantityOrdered`, `unitPrice`
- Payment capture: `paymentMethod`, `amount`

## Delete and Lifecycle Strategy
- Product/ProductVariant: soft delete (`active = false`)
- UOM/Location: hard delete
- Sales orders: state-driven lifecycle (`DRAFT/CONFIRMED/FULFILLED/CANCELLED`)

## Database Dependencies
Core master + order flow tables used by implemented API:
- `unit_of_measure`
- `product`
- `product_variant`
- `location`
- `sales_order`
- `sales_order_line`
- `inventory_reservation`
- `payment`
- `inventory_movement`

## Known Limitations (Current Implementation)
- Fulfillment currently writes `inventory_movement.to_location_id` as the same value as `from_location_id` due non-null schema constraint for `to_location_id`.
- Receipt persistence (`receipt`) is not yet wired to API flow.
- Refund API flow (`payment.status = REFUNDED`) is not yet implemented.
- Partial fulfillment command path is not implemented as a dedicated operation.
- Multi-step confirm/fulfill/cancel flows should be wrapped in explicit database transactions for stronger atomicity.

## Run and Verify
1. Apply migrations.
2. Start server with `./gradlew run`.
3. Base URL: `http://localhost:8010/api/v1`.
4. Validate contracts by calling at least one endpoint from each domain (`/uom`, `/products`, `/locations`, `/orders`).
