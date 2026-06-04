# 04. Quality, Contracts, And Observability

## Goal

Make the implemented APIs safe to evolve by adding automated verification,
contract checks, consistent error behavior, and operational visibility.

## Scope

This phase covers tests, OpenAPI and Bruno synchronization, response and error
contracts, logging, health checks, CI, and readiness for client integration.

## Current Completed Work

- [x] Root README documents the current project scope
- [x] Quick start guide exists
- [x] API implementation guide exists
- [x] API testing guide exists
- [x] endpoint overview exists
- [x] verification checklist exists
- [x] implementation summary exists
- [x] project overview knowledge doc exists
- [x] model design knowledge doc exists
- [x] order, payment, and fulfillment process knowledge doc exists
- [x] OpenAPI README exists
- [x] OpenAPI YAML and JSON contracts exist
- [x] Bruno collection exists
- [x] Bruno collection includes utility endpoints
- [x] Bruno collection includes the implemented API endpoints
- [x] Base error response shape includes `error`, `errorCode`, `status`, and `errorId`
- [x] Handlers log generated handling IDs and request metadata
- [x] Database health endpoint returns `UP` or `DOWN`

## Ordered Tasks

### 04.1 Test Foundation

Estimate: 2-3 engineer-days

Tasks:

- [ ] Add unit tests for repository query builders and validation edge cases
- [ ] Add integration tests against PostgreSQL
- [ ] Add test fixtures for seeded and newly created data
- [ ] Document how to run unit and integration tests locally

Done when:

- [ ] Tests can run from a clean checkout
- [ ] Repository behavior has focused test coverage
- [ ] PostgreSQL integration tests are isolated from local development data

### 04.2 API And Contract Verification

Estimate: 2-4 engineer-days

Tasks:

- [ ] Add HTTP tests for all 29 implemented endpoints
- [ ] Add end-to-end order lifecycle tests
- [ ] Add contract tests that verify OpenAPI operation IDs are registered by handlers
- [ ] Add response snapshot or schema tests for normalized response envelopes
- [ ] Add error response tests for validation, not found, conflict, database timeout, and internal failures

Done when:

- [ ] Public endpoint behavior is automated-test covered
- [ ] OpenAPI operation IDs cannot drift silently from handler registration
- [ ] Response and error contracts are verified by tests

### 04.3 CI And Artifact Synchronization

Estimate: 1.5-2.5 engineer-days

Tasks:

- [ ] Add CI workflow for build and tests
- [ ] Add OpenAPI validation to CI
- [ ] Add Bruno or collection linting if practical
- [ ] Document and enforce a single source of truth for generated OpenAPI JSON
- [ ] Keep version references synchronized with `build.gradle.kts`

Done when:

- [ ] CI blocks broken build, tests, and OpenAPI contracts
- [ ] OpenAPI YAML and JSON drift is detected or prevented
- [ ] Version references do not drift silently from the build file

### 04.4 Logging, Health, And Metrics

Estimate: 2-3 engineer-days

Tasks:

- [ ] Add structured logs or a clear log format for production use
- [ ] Propagate incoming `X-Request-ID` into all logs and responses
- [ ] Add readiness and liveness semantics beyond database health if needed
- [ ] Add metrics for request count, latency, error count, and database failures

Done when:

- [ ] A single request can be traced through logs
- [ ] Health endpoints clearly separate process, router, and database readiness where needed
- [ ] Basic runtime metrics are available for operators

### 04.5 Security Planning Gate

Estimate: 0.5-1 engineer-day

Tasks:

- [ ] Decide when authentication and authorization enter the plan
- [ ] Define the minimum protected endpoints for the first auth slice
- [ ] Decide whether auth belongs before or after Phase 05 expansion
- [ ] Document the security sequencing decision

Done when:

- [ ] Security scope has an owner phase
- [ ] The plan states which endpoints must be protected first
- [ ] Later implementation work is not blocked by an undefined auth strategy

## Assumptions

- Automated tests should target business behavior before broad refactors.
- OpenAPI remains the external API contract.
- Bruno remains the manual request collection.
- Authentication is important, but should follow a stable workflow baseline.

## Definition of Done

- [ ] CI blocks broken build, broken tests, and invalid OpenAPI contracts
- [ ] Public API behavior is covered by HTTP integration tests
- [ ] Error responses are stable and documented
- [ ] Logs are useful for tracing a single request through handler and repository work
- [ ] Documentation updates are part of each API behavior change
