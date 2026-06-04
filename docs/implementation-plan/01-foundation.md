# 01. Foundation

## Goal

Stabilize the runtime, database, migration, configuration, and service structure
that every later Literp feature depends on.

## Scope

This phase covers project build setup, Vert.x startup, database connectivity,
OpenAPI router loading, service proxy wiring, migrations, seed data, and local
runtime infrastructure.

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

## Remaining Work

- [ ] Add a transaction helper or unit-of-work abstraction for repositories
- [ ] Add explicit startup failure behavior when `cfg.properties` is missing or incomplete
- [ ] Add environment-variable override support for runtime config
- [ ] Harden migration behavior for partially initialized databases, especially enum creation
- [ ] Add build verification to CI
- [ ] Add database migration verification to CI
- [ ] Add a dedicated test database workflow
- [ ] Add a local reset workflow that clearly separates destructive and non-destructive paths
- [ ] Decide whether generated Vert.x service proxy sources should be committed, cached, or only generated in build

## Assumptions

- PostgreSQL remains the primary database.
- The Vert.x service proxy pattern remains the local service boundary.
- OpenAPI operation IDs remain the route-to-handler contract.
- Runtime configuration can start simple, but must not block future Docker and deployment workflows.

## Definition of Done

- [ ] Runtime can start from a clean checkout with documented local setup
- [ ] Database schema and seed data can be recreated deterministically
- [ ] Startup failures are actionable in logs
- [ ] CI validates compilation and migrations
- [ ] Repository transaction support is available for business commands
