# 00. Implementation Plan Overview

This directory translates the Literp architecture and current branch state into
implementation work.

The plan is synced to the code and documentation that already exist in this
repository. Checked tasks mean the behavior or artifact is already present in
the current branch. Unchecked tasks are the remaining implementation path.

## 00.1 Current Baseline

Literp is currently a Kotlin and Vert.x backend for a lightweight ERP core.
The implemented slice is POS-first and covers catalog, locations, sales order
processing, payment capture, fulfillment, and movement-based inventory writes.

Current completed runtime scope:

- [x] 2 utility endpoints: root and database health
- [x] 29 REST API endpoints across 5 domains
- [x] PostgreSQL schema for catalog, inventory, sales, POS, and manufacturing
- [x] deterministic Alembic seed data
- [x] OpenAPI contracts for product catalog, locations, and order process
- [x] Bruno collection aligned to the implemented handlers
- [x] developer documentation for setup, testing, endpoints, and implementation

Current important gaps:

- [ ] some multi-step order commands still need broader transaction coverage
- [ ] order-process list responses still need envelope normalization
- [ ] OpenAPI, Bruno, docs, and handlers need automated drift checks as the API expands
- [ ] receipt, refund, partial fulfillment, POS operations, and manufacturing APIs are not exposed yet
- [ ] automated test coverage is focused on foundation and master-data behavior; order flow still needs broader tests

## 00.2 Directory Name

This directory is named:

```text
docs/implementation-plan
```

Reason:

- the content is a plan, not multiple implementation variants
- each file describes work to be implemented
- the name is clear for project management and engineering execution

## 00.3 Estimation Rules

Estimates are intentionally rough.

Use this baseline:

```text
1 engineer-day = about 6 focused engineering hours
```

The estimates do not include long external delays, product review cycles, or
deployment approval delays.

## 00.4 Phase Summary

| Phase | File | Goal | Status | Estimate |
|---|---|---|---|---:|
| 00 | `00-implementation-overview.md` | Explain execution plan | Done for initial planning | Documentation only |
| 01 | `01-foundation.md` | Stabilize runtime, schema, config, and data foundation | Complete | 0 remaining engineer-days |
| 02 | `02-master-data-api.md` | Complete catalog and location API parity | Complete | 0 remaining engineer-days |
| 03 | `03-order-inventory-flow.md` | Harden order, payment, reservation, and fulfillment flows | Queued until Phase 02 is done | 10-18 remaining engineer-days |
| 04 | `04-quality-contracts-observability.md` | Add verification, contract safety, structure readiness, and operational readiness | Queued until earlier phase gates are done | 9-16 remaining engineer-days |
| 05 | `05-pos-manufacturing-expansion.md` | Expose POS and manufacturing capabilities beyond the MVP slice | Future phase | 18-35 future engineer-days |

Estimated remaining MVP hardening:

```text
19-34 engineer-days
```

The current branch already implements the functional MVP path. The remaining
MVP work is mostly reliability, parity, tests, and operational hardening.

## 00.5 Recommended Build Order

Build in this order:

1. Foundation hardening
2. Master-data API parity
3. Order and inventory flow hardening
4. Quality, contracts, and observability
5. Authentication and authorization baseline
6. POS and manufacturing expansion

Phase discipline:

- [x] Complete the remaining work in `01-foundation.md`
- [x] Satisfy the Phase 01 definition of done
- [x] Re-check this overview and mark Phase 01 as complete
- [x] Unblock Phase 02 after Phase 01 completion
- [x] Complete Phase 02 before starting Phase 03 implementation
- [ ] Complete the Phase 04 project structure gate before starting Phase 05 implementation
- [ ] Complete the Phase 05.0 authentication and authorization baseline before Phase 05 expansion implementation

The [project structure decision](../knowledge/PROJECT_STRUCTURE_DECISION.md) is accepted and 04.6 is complete. The formal Phase 04 entry-gate checkbox remains pending the remaining Phase 04 evidence review; Phase 05 expansion is also blocked by the pending 05.0 authentication and authorization baseline.

Later phase files may be used for planning and context. Do not build IAM before
the platform proves its order-to-inventory workflow:

```text
Add product -> Create sales order -> Confirm and reserve -> Capture payment -> Fulfill -> Write inventory movement
```

That workflow is the baseline for the accepted [security sequencing decision](../knowledge/SECURITY_SEQUENCING.md). The proposed 05.0 authentication and authorization baseline must be designed and implemented before Phase 05 POS and manufacturing expansion starts. It protects the current business API surface; it is not broad IAM work.

## 00.6 Cross-Phase Principles

- [x] Treat POS as a channel, not as the whole system
- [x] Keep inventory movement-based and auditable
- [x] Keep sales intent separate from physical inventory changes
- [x] Keep manufacturing as an extension of the same inventory model
- [x] Keep APIs contract-first through OpenAPI operation IDs
- [ ] Wrap multi-step business commands in explicit database transactions
- [ ] Normalize response envelopes before broad client adoption
- [ ] Add automated tests before expanding API surface materially
- [ ] Keep OpenAPI, Bruno, docs, and handlers synchronized with each change
- [x] Resolve the project structure decision before POS and manufacturing expand the codebase materially; retain the current layout through Phase 05

## 00.7 Recommended MVP Slice

The smallest valuable end-to-end slice is:

```text
Catalog and location setup
Draft sales order with lines
Confirm order and create reservations
Capture payment
Fulfill order and write inventory movement
Fetch order with lines, reservations, and payments
```

Current state:

- [x] Catalog and location setup APIs exist
- [x] Draft sales order creation exists
- [x] Order line insertion exists
- [x] Confirmation creates reservations
- [x] Payment capture exists
- [x] Fulfillment writes inventory movement rows
- [x] Order detail fetch includes lines, reservations, and payments
- [ ] The full slice is covered by automated integration tests
- [ ] The full slice runs inside explicit database transactions where needed

## 00.8 Ordered Task Format

Each phase is broken into ordered task chunks.

Use this format:

```text
### 01.1 Task name

Estimate: 1-2 engineer-days

Tasks:

- [ ] Task not started
- [x] Task completed

Done when:

- [ ] Observable completion condition
- [ ] Verification condition
```

Rules:

- task chunks are executed in order inside a phase
- later phases stay queued until the active phase is complete
- estimates belong to the chunk, not every checklist item
- `Done when` describes acceptance criteria, not implementation steps
- completed subtasks should only be checked when the behavior exists and is verified

Individual checklist items use this format:

```text
- [ ] Task not started
- [x] Task completed
```

Subtasks should be checked only when the behavior exists in the branch and has
a clear code, migration, documentation, OpenAPI, test, or collection artifact.

## 00.9 Definition of MVP Done

The MVP is done when a basic application can move through this complete path:

```text
Create or list UOM
Create or list product
Create or list location
Create draft order
Add order line
Confirm order
Capture payment
Fulfill order
Inspect inventory movement and order detail
```

MVP completion checklist:

- [x] The happy-path behavior exists in handlers, services, and repositories
- [x] The database schema supports the needed entities
- [x] Seed data supports local demos and regression testing
- [x] OpenAPI and Bruno assets exist
- [ ] The happy path is covered by automated integration tests
- [ ] State transition guardrails are covered by automated tests
- [ ] Multi-step writes are atomic
- [ ] API response shape is stable and documented as the client contract
