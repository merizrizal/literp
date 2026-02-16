# Project Overview

## Literp - Lightweight ERP core with POS as a channel (POS-First, Manufacturing-Ready)

### 1. Purpose

This project aims to build a **generalized, extensible sales and inventory platform** that supports **Point of Sale (POS)** operations today while being **natively capable of supporting manufacturing workflows** in the future—without architectural rewrites.

The platform is designed to serve as a **core Order-to-Cash system**, where POS is treated as **one sales channel**, not the center of the system.

---

### 2. Problem Statement

Most POS systems are:

- Tightly coupled to retail or restaurant workflows
- Inventory-naive (simple counters, not auditable movements)
- Hard to extend into manufacturing or production use cases

Manufacturing systems, on the other hand:

- Assume ERP-level complexity
- Are heavy, rigid, and expensive
- Poorly suited for fast, front-of-house sales

There is a gap for a **lightweight, modern platform** that:

- Handles real POS operations reliably
- Uses proper inventory accounting
- Can grow into manufacturing without refactoring core logic

---

### 3. What We Are Building

We are building a **domain-driven core platform** that unifies:

- **Sales**
- **Inventory**
- **Payments**
- **Production (optional extension)**

into a **single coherent system**, while keeping industry-specific logic at the edges.

At its core, the platform provides:

- A **generalized product catalog**
- A **channel-agnostic sales engine**
- A **movement-based inventory system**
- An **extensible production model** (for manufacturing)

---

### 4. Key Design Principles

#### 4.1 POS Is a Channel, Not the System

POS functionality (cash handling, shifts, receipts, UI flows) is implemented as a **sales channel adapter**, not embedded in core business logic.

#### 4.2 Inventory Is Movement-Based

Inventory is modeled using **immutable stock movements**, not mutable stock counters.
This ensures:

- Auditability
- Manufacturing compatibility
- Offline safety
- Correct historical reporting

#### 4.3 Sales Never Mutate Inventory Directly

Sales create **intent** (orders, reservations).
Inventory executes **state changes** (movements).

This decoupling allows:

- Deferred fulfillment
- Offline POS
- Manufacturing-driven stock availability

#### 4.4 Manufacturing Is an Extension, Not a Fork

Manufacturing introduces:

- Bills of Materials (BOM)
- Work Orders
- Production Runs

These **produce inventory**, but do not alter sales or POS logic.

---

### 5. Core Capabilities (Phase-Independent)

#### 5.1 Product & Catalog

- Products, variants, SKUs
- Units of measure
- Pricing references
- Industry-agnostic modeling

#### 5.2 Sales & POS

- Sales orders
- Pricing and discounts
- Payments
- Receipts / invoices
- Multi-channel support (POS, online, B2B)

#### 5.3 Inventory

- Multi-location stock
- Inventory movements (IN, OUT, TRANSFER, ADJUSTMENT)
- Stock reservation
- Full audit trail

#### 5.4 Production (Manufacturing Extension)

- Bill of Materials
- Work Orders
- Raw material consumption
- Finished goods output
- Yield and scrap handling

---

### 6. Target Use Cases

#### Initial Phase

- Retail POS
- Restaurant POS
- Small multi-branch businesses

#### Future Phase

- Light manufacturing
- Assembly-based production
- Made-to-stock or made-to-order businesses

The same core system supports all scenarios.

---

### 7. Architectural Scope

The platform is:

- API-first
- Domain-driven
- Database-backed (relational)
- Event-friendly (for future scalability)
- Offline-capable (POS clients)

It is intentionally **not**:

- A full ERP
- An accounting system (integration point instead)
- A UI-centric monolith

---

### 8. Long-Term Vision

Over time, this platform can evolve into:

- A POS-centric ERP core
- A foundation for automation and analytics
- A composable backend for multiple sales channels
- A manufacturing-aware commerce system

Without breaking existing POS deployments.

---

### 9. Summary (One Sentence)

> We are building a **generalized sales and inventory core** where POS is a channel, inventory is authoritative, and manufacturing is a natural extension—not a redesign.
