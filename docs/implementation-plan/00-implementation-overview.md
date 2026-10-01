# 00. Implementation Plan Overview

This directory translates the Literp architecture and current branch state into
implementation work.

Checked tasks record implemented artifacts or accepted evidence in the owning
phase; they are not evidence of a fresh validation run or deployment approval.
See [ADS coverage and requirements review](ADS_COVERAGE_REVIEW.md) for every
task's design coverage, source authority, missing ADSs, and unresolved gaps.
No standalone PRD is tracked; the product requirements narrative is
[PROJECT_OVERVIEW.md](../knowledge/PROJECT_OVERVIEW.md).

## 00.1 Current Baseline

Literp is currently a Kotlin and Vert.x backend for a lightweight ERP core.
The implemented slice is POS-first and covers catalog, locations, sales order
processing, payment capture, fulfillment, and movement-based inventory writes.

Current completed runtime scope:

- [x] 5 utility endpoints: root, liveness, readiness, database health, and metrics
- [x] 43 registered business operations across 6 API domains: 41 implemented and 2 authenticated receipt/refund write placeholders
- [x] PostgreSQL schema for catalog, inventory, sales, POS, and manufacturing
- [x] deterministic Alembic seed data
- [x] OpenAPI contracts for product catalog, locations, order process, and POS operations
- [x] Application-side authentication, scoped POS terminals/shifts, and receipt lookups
- [x] Bruno collection aligned to the implemented handlers
- [x] developer documentation for setup, testing, endpoints, and implementation

Current important gaps:

- [ ] receipt generation and refund writes remain authenticated `501 NOT_IMPLEMENTED`
- [ ] manufacturing APIs are proposed, not implemented; partial fulfillment remains deferred
- [ ] shared stock-writer serialization and catalog unit/type stability need the cross-domain safeguards proposed in 05.6
- [ ] Phase 04 request-correlated repository logging and external CI required-check enforcement remain open
- [ ] provider, deployment, authenticated external smoke, and related operational acceptance remain pending

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
| 03 | `03-order-inventory-flow.md` | Harden order, payment, reservation, and fulfillment flows | Recorded complete; shared-writer concurrency caveat remains | Re-estimate follow-up separately |
| 04 | `04-quality-contracts-observability.md` | Add verification, contract safety, structure readiness, and operational readiness | Gates complete; logging and CI governance criteria open | Re-estimate remaining criteria |
| 05 | `05-pos-manufacturing-expansion.md` | Expose POS and manufacturing capabilities beyond the MVP slice | 05.0–05.2 locally accepted; 05.3 partial; 05.4–05.6 design only | Re-estimate after design approval |

The original 19–34 engineer-day MVP estimate predates completed Phase 03 and
much of Phase 04; it is not a current remaining-work estimate. Re-estimate open
criteria and manufacturing prerequisites rather than summing historical task
estimates. The current branch implements the functional MVP path; operational
acceptance and the explicitly listed integrity gaps remain separate.

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
- [x] Complete the Phase 04 project structure gate before starting Phase 05 implementation
- [x] Complete the Phase 05.0 authentication and authorization baseline before Phase 05 expansion implementation, under the maintainer-approved deferred-deployment exception

The [project structure decision](../knowledge/PROJECT_STRUCTURE_DECISION.md) is accepted and 04.6 is complete. The remaining Phase 04 Definition-of-Done criteria for request-correlated repository logging and external CI required-check enforcement remain separately tracked; they do not reopen the project-structure gate. Phase 05 development remains subject to the 05.0 deferred-deployment restrictions.

Later phase files may be used for planning and context. Do not build IAM before
the platform proves its order-to-inventory workflow:

```text
Add product -> Create sales order -> Confirm and reserve -> Capture payment -> Fulfill -> Write inventory movement
```

That workflow is the baseline for the accepted [security sequencing decision](../knowledge/SECURITY_SEQUENCING.md). The 05.0 authentication and authorization baseline is implemented and accepted for Phase 05 development under the documented deferred-deployment exception. It protects the business API surface; it is not broad IAM work or deployment approval.

## 00.6 Cross-Phase Principles

- [x] Treat POS as a channel, not as the whole system
- [x] Keep inventory movement-based and auditable
- [x] Keep sales intent separate from physical inventory changes
- [x] Keep manufacturing as an extension of the same inventory model
- [x] Keep APIs contract-first through OpenAPI operation IDs
- [x] Wrap existing multi-step order commands in explicit database transactions
- [x] Normalize existing response envelopes
- [x] Establish order-flow and API contract tests before expansion
- [ ] Maintain OpenAPI, Bruno, docs, policies, and handler parity for every new slice
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
- [x] The full slice has recorded automated integration-test coverage in Phase 03
- [x] Existing multi-step order commands use explicit transactions; this does not prove serialization across competing orders

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
- later phases stay queued until the active phase is complete, unless a documented maintainer-approved sequencing exception applies
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
- [x] The happy path has recorded automated integration-test coverage
- [x] State transition guardrails have recorded automated-test coverage
- [x] Existing multi-step order writes are atomic
- [x] Existing API response shape is documented and contract-tested

These local delivery criteria do not close external deployment acceptance,
Phase 04's remaining criteria, or the shared stock concurrency gap in the
[coverage review](ADS_COVERAGE_REVIEW.md).
