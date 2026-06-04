# 01. Foundation

## Goal

Stabilize the runtime, database, migration, configuration, and service structure
that every later Literp feature depends on.

## Scope

This phase covers project build setup, Vert.x startup, database connectivity,
OpenAPI router loading, service proxy wiring, migrations, seed data, and local
runtime infrastructure.

## Phase Status

Phase 01 is the current active implementation gate.

Do not start Phase 02, Phase 03, Phase 04, or Phase 05 implementation work until
the ordered tasks below are completed or deliberately moved out of the
foundation gate with a documented reason.

## Generated Source Policy

Vert.x service proxy classes are generated build artifacts.

- [x] Service interfaces are committed under `src/main/java/com/literp/service`
- [x] Generated proxy classes are produced under `build/generated/sources/java`
- [x] Generated proxy classes are not committed to source control
- [x] Gradle includes the generated source directory in the main source set

## Current Completed Work

- [x] Kotlin and Java Gradle project exists
- [x] Runtime targets Java `25`
- [x] Current build file uses Kotlin `2.4.0`
- [x] Current build file uses Vert.x `5.1.1`
- [x] Application entry point deploys `MainVerticle`
- [x] `MainVerticle` deploys `HttpServerVerticle`
- [x] HTTP server reads `cfg.properties`
- [x] PostgreSQL pool is initialized through Vert.x PG client
- [x] Root utility endpoint exists
- [x] Database health endpoint exists
- [x] OpenAPI contracts are loaded at startup
- [x] Product catalog, location, and order-process routers are mounted under `/api/v1`
- [x] Repository layer exists for UOM, product, variant, location, and order process
- [x] Vert.x service proxy interfaces exist for master data and order process
- [x] Kotlin service implementations bridge repositories to Vert.x futures
- [x] Base handler provides success and error response helpers
- [x] Error code constants exist
- [x] PostgreSQL Docker Compose stack exists
- [x] Migration container runs Alembic to `head`
- [x] JVM Docker Compose stack exists
- [x] Initial Alembic migration creates catalog, inventory, sales, POS, and manufacturing tables
- [x] Seed Alembic migration populates deterministic data

## Ordered Tasks

### 01.1 Repository Transaction Boundary

Estimate: 2-3 engineer-days

Tasks:

- [x] Add a transaction helper or unit-of-work abstraction for repositories
- [x] Support composing multiple repository statements on one database connection
- [x] Apply the transaction helper to at least one representative multi-step command
- [x] Decide whether generated Vert.x service proxy sources should be committed, cached, or only generated in build

Done when:

- [x] Repository code has a reusable transaction boundary
- [x] A multi-step command can commit all writes or roll back all writes
- [x] Generated service proxy source policy is documented

### 01.2 Runtime Configuration Hardening

Estimate: 1-2 engineer-days

Tasks:

- [x] Add explicit startup failure behavior when `cfg.properties` is missing
- [x] Add explicit startup failure behavior when required config keys are incomplete
- [x] Add environment-variable override support for runtime config
- [x] Document config precedence between file values and environment variables

Done when:

- [x] Missing or invalid config fails startup with actionable logs
- [x] Local file config still works
- [x] Docker and deployment-oriented environment overrides work

### 01.3 Migration And Test Database Foundation

Estimate: 1.5-2 engineer-days

Tasks:

- [x] Harden migration behavior for partially initialized databases, especially enum creation
- [x] Add a dedicated test database workflow
- [x] Ensure seed data can be loaded consistently in the test workflow
- [x] Document when to use local development database vs. test database

Done when:

- [x] A clean database can migrate to `head`
- [x] A partially initialized local database has a clear recovery path
- [x] Tests can run against an isolated PostgreSQL database

Implementation notes:

- [x] Initial migration creates PostgreSQL enum types only when missing
- [x] Table declarations bind existing PostgreSQL enum types without re-creating them
- [x] Alembic fails fast when `DB_URL` is not configured
- [x] `docker/pgsql-test` provides an isolated `literp_test` database on host port `55432`
- [x] Test database migration to `head` was verified with deterministic seed data loaded

### 01.4 Build And Migration Verification

Estimate: 1-1.5 engineer-days

Tasks:

- [ ] Add build verification to CI
- [ ] Add database migration verification to CI
- [ ] Ensure CI uses the same Java and Gradle expectations as the project
- [ ] Document the minimum CI checks required before merging implementation work

Done when:

- [ ] CI validates compilation
- [ ] CI validates migrations against PostgreSQL
- [ ] CI failure output is actionable for local reproduction

### 01.5 Local Reset And Phase 02 Gate

Estimate: 0.5-1 engineer-day

Tasks:

- [ ] Add a local reset workflow that clearly separates destructive and non-destructive paths
- [ ] Document how to reset PostgreSQL and seed data for local work
- [ ] Re-check every Phase 01 definition-of-done item
- [ ] Update `00-implementation-overview.md` when Phase 01 is complete

Done when:

- [ ] Local reset instructions are documented
- [ ] Phase 01 definition of done is fully checked
- [ ] Phase 02 is explicitly unblocked in `00-implementation-overview.md`

## Assumptions

- PostgreSQL remains the primary database.
- The Vert.x service proxy pattern remains the local service boundary.
- OpenAPI operation IDs remain the route-to-handler contract.
- Runtime configuration can start simple, but must not block future Docker and deployment workflows.

## Definition of Done

- [ ] Runtime can start from a clean checkout with documented local setup
- [x] Database schema and seed data can be recreated deterministically
- [ ] Startup failures are actionable in logs
- [ ] CI validates compilation and migrations
- [ ] Repository transaction support is available for business commands
- [ ] Phase 02 is explicitly unblocked in `00-implementation-overview.md`
