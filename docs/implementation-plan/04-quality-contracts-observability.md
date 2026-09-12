# 04. Quality, Contracts, And Observability

## Goal

Make the implemented APIs safe to evolve by adding automated verification,
contract checks, consistent error behavior, and operational visibility.

## Scope

This phase covers tests, OpenAPI and Bruno synchronization, response and error
contracts, logging, health checks, CI, project structure readiness, and
readiness for client integration.

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

- [x] Add unit tests for repository query builders and validation edge cases
- [x] Add integration tests against PostgreSQL
- [x] Add test fixtures for seeded and newly created data
- [x] Document how to run unit and integration tests locally

Done when:

- [x] Tests can run from a clean checkout
- [x] Repository behavior has focused test coverage
- [x] PostgreSQL integration tests are isolated from local development data

### 04.2 API And Contract Verification

Estimate: 2-4 engineer-days

Tasks:

- [x] Add HTTP tests for all 31 implemented endpoints
- [x] Add end-to-end order lifecycle tests
- [x] Add contract tests that verify OpenAPI operation IDs are registered by handlers
- [x] Add response snapshot or schema tests for normalized response envelopes
- [x] Add error response tests for validation, not found, conflict, database timeout, and internal failures

Done when:

- [x] Public endpoint behavior is automated-test covered
- [x] OpenAPI operation IDs cannot drift silently from handler registration
- [x] Response and error contracts are verified by tests

### 04.3 CI And Artifact Synchronization

Estimate: 1.5-2.5 engineer-days

Tasks:

- [x] Add CI workflow for build and tests
- [x] Add OpenAPI validation to CI
- [ ] Add Bruno or collection linting if practical
- [x] Document and enforce a single source of truth for generated OpenAPI JSON
- [x] Keep version references synchronized with `build.gradle.kts`

Done when:

- [ ] CI blocks broken build, tests, and OpenAPI contracts
- [x] OpenAPI YAML and JSON drift is detected or prevented
- [x] Version references do not drift silently from the build file

### 04.4 Logging, Health, And Metrics

Estimate: 2-3 engineer-days

Tasks:

- [x] Add structured logs or a clear log format for production use
- [x] Propagate incoming `X-Request-ID` into all logs and responses
- [x] Add readiness and liveness semantics beyond database health if needed
- [x] Add metrics for request count, latency, error count, and database failures

Done when:

- [ ] A single request can be traced through logs
- [x] Health endpoints clearly separate process, router, and database readiness where needed
- [x] Basic runtime metrics are available for operators

### 04.5 Security Planning Gate

Estimate: 0.5-1 engineer-day

Tasks:

- [x] Decide when authentication and authorization enter the plan
- [x] Define the minimum protected endpoints for the first auth slice
- [x] Decide whether auth belongs before or after Phase 05 expansion
- [x] Document the [security sequencing decision](../knowledge/SECURITY_SEQUENCING.md)

Done when:

- [x] Security scope has the proposed 05.0 owner phase
- [x] The accepted decision states all 31 current business operations and utility-route policy for the first protected surface
- [x] Later expansion work is not blocked by an undefined auth strategy; implementation remains a pending 05.0 prerequisite

### 04.6 Project Structure Gate

Estimate: 1-2 engineer-days

Tasks:

- [x] Decide to retain the current layer-based backend packages through Phase 05
- [x] Not applicable: no restructuring is approved; the accepted decision defines current and proposed placement for catalog, location, order, inventory, POS, and manufacturing code
- [x] Decide to retain API assets under `api_collections`
- [x] Not applicable: no files move under the retained-layout decision
- [x] Not applicable: no move requires import, proxy, asset-path, documentation, or CI-reference updates
- [x] Document the final [project structure decision](../knowledge/PROJECT_STRUCTURE_DECISION.md) in the README and implementation plan

Done when:

- [x] Phase 05 has a clear package and asset layout before POS and manufacturing code expands
- [x] Not applicable: no structure changes are approved; any future mechanical move requires the specified build, test, OpenAPI, Bruno-path, and startup validation
- [x] Existing feature work is not mixed with structural file moves

## Assumptions

- Automated tests should target business behavior before broad refactors.
- OpenAPI remains the external API contract.
- Bruno remains the manual request collection.
- Authentication is important, but should follow a stable workflow baseline.
- The current project structure is acceptable through Phase 02 and Phase 03.
- A structure refactor should happen only after behavior is test-covered enough to make file moves low risk.

## Definition of Done

- [ ] CI blocks broken build, broken tests, and invalid OpenAPI contracts
- [x] Public API behavior is covered by HTTP integration tests
- [x] Error responses are stable and documented
- [ ] Logs are useful for tracing a single request through handler and repository work
- [x] Documentation updates are part of each API behavior change
- [x] Project structure is confirmed sufficient for Phase 05 by the accepted [project structure decision](../knowledge/PROJECT_STRUCTURE_DECISION.md)

## Gate Closure Evidence

- The project maintainer accepted the security sequencing and structure decisions on 2026-09-12. The decisions assign authentication and authorization to proposed task 05.0 before Phase 05 expansion and retain the current backend/API asset layout through Phase 05.
- Current validation: `JAVA_TOOL_OPTIONS='-Djdk.lang.Process.launchMechanism=FORK' rtk ./gradlew --no-daemon test --rerun-tasks` passed with 36 tests, 0 skipped, 0 failures, and 0 errors; the corresponding `rtk ./gradlew --no-daemon build` passed.
- The `Foundation Verification` workflow declares build, test-baseline, OpenAPI-verification, and migration-verification jobs. The fresh `source ~/Documents/PyEnv/myEnv/bin/activate && rtk python scripts/verify_openapi_assets.py` run passed for all three YAML/JSON pairs at version `0.0.1`.
- The production-router smoke test verifies utility routes, unmatched-route `404`, normalized global failure responses, and `X-Request-ID` propagation.
- Bruno collection paths and the three added utility request assets are structurally verified. Bruno linting remains an explicitly deferred optional task until a stable command is selected.

The two planning gates are complete. Do not mark all of Phase 04 complete: CI required-check enforcement and request-correlated repository logging remain open.
