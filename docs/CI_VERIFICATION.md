# CI Verification

The foundation gate requires the `Foundation Verification` workflow to pass
before merging implementation work. The gate currently covers build, test
baseline, OpenAPI verification, and migration verification.

## Required Checks

| Check | Runtime | Command |
|---|---|---|
| Build | Java 25, Gradle wrapper 9.7.1 | `./gradlew build` |
| Test Baseline | Java 25, Gradle wrapper 9.7.1, isolated PostgreSQL test DB | `./gradlew test` |
| OpenAPI Verification | Python 3.13 | `python scripts/verify_openapi_assets.py` |
| Migration Verification | Python 3.13, PostgreSQL 18 | `python scripts/verify_migrations.py` |

The migration check runs Alembic to `head`, verifies the database revision
matches the repository head, and confirms that deterministic seed data exists in
core tables. The migration job also runs `scripts/test_migration_downgrade_safety.py`
on newly created temporary databases in its disposable PostgreSQL service.
POS downgrades refuse to discard attribution, trusted reconciliation fields,
or actor identities that cannot fit the old schema; legacy-only rollback and
re-upgrade are tested.

## Local Reproduction

Build check:

```bash
./gradlew build
```

Phase 04.1–04.2 test baseline against the isolated test database:

```bash
cd docker
source envrc
make network
DIR=pgsql-test make env-up
cd ..
./gradlew test --tests com.literp.verticle.MasterDataHttpIntegrationTest \
  --tests com.literp.verticle.OrderProcessHttpIntegrationTest \
  --tests com.literp.contract.OpenApiOperationIdRegistrationTest
./gradlew test
```

For CI parity, the test baseline job uses the PostgreSQL service on port `5432` and sets the `LITERP_TEST_PG_*` environment variables before running `./gradlew test`.

OpenAPI contracts treat `api_collections/open_api_spec/*.yaml` as the source of truth. The matching JSON files are tracked for drift detection, and `build.gradle.kts` is the version source. Keep those files in sync, then run the verifier:

```bash
python3 -m pip install -r python/requirements.txt
python scripts/verify_openapi_assets.py
```

If you edit an OpenAPI YAML file, update the matching JSON file in the same change so CI stays green.

If `literp_test` is unavailable on `127.0.0.1:55432`, the DB-backed tests are skipped by `TestDatabase.assumeAvailable(...)` and should be rerun after the isolated test database is started.

Migration check against the isolated test database:

```bash
cd docker
source envrc
make network
DIR=pgsql-test make env-up
cd ..
python3 -m pip install -r python/requirements.txt
source python/database/envrc.test
python scripts/verify_migrations.py
```

## POS Downgrade Safety Regression

Use an explicitly disposable local PostgreSQL cluster and the Python environment
containing the repository's migration dependencies. Do not use the development,
shared test, or production database as a downgrade target.

```bash
LITERP_MIGRATION_TEST_ADMIN_URL=postgresql://postgres@127.0.0.1:55439/postgres \
  python -B scripts/test_migration_downgrade_safety.py
```

The example assumes a disposable cluster listening only on loopback port 55439.
The test requires database-create permission, creates uniquely named
`literp_downgrade_test_*` databases, and drops only those databases afterward.
It never downgrades an existing application database. The three POS downgrade
guards lock their table before checking data, preventing a writer from racing
the safety check. Refusal is intentional: do not delete financial history to
force a downgrade through.

Workflow success does not prove merge blocking. Branch-protection/ruleset
required checks and a corresponding PR run still need external evidence.

## Request Correlation and Financial Regression Tests

The full Gradle suite includes database-backed receipt/refund and production-router
correlation tests. Use only the isolated test database (or an explicitly disposable
replacement with matching runtime/test connection overrides), never development
or production data. Refund fixtures are append-only: test-only cleanup requires a
privileged test role, checks the selected test database name, and uses
`SET LOCAL session_replication_role` on one transaction-scoped connection before
deleting only the fixture order's adjustments. It does not disable triggers
globally or change production schema. Normal-connection DELETE rejection and
transaction rollback are regression-tested.

A PostgreSQL lock barrier forces overlapping HTTP writes. Captured repository
logs must retain each request ID through asynchronous proxy callbacks and direct
scope-query subscriptions. The test also asserts read/error completion and that
request body values and bearer data do not appear in the structured trace events.
Incoming `X-Request-ID` accepts 1–128 ASCII letters/digits or `._:-`; missing or
malformed IDs receive a generated UUID. The same canonical ID is returned in the
response and transported in per-request proxy delivery headers. These logs cover
repository operation boundaries, not individual SQL statements or distributed
traces; no telemetry infrastructure or runtime dependency was added.

## Inspect Merge Enforcement Without `gh`

The GitHub web UI and REST API work independently of the `gh` CLI. During the
review repair, unauthenticated read-only API calls succeeded for this public
repository:

- `GET /repos/merizrizal/literp/rulesets` and `/rulesets/17364050`: the active
  `admin` ruleset targets the default branch/main but contains only deletion and
  non-fast-forward restrictions, not required status checks.
- `GET /repos/merizrizal/literp/rules/branches/main`: returned those same two
  effective rules; no required-status-check rule was present.
- `GET /repos/merizrizal/literp/branches/main/protection`: returned 401, so classic
  branch protection could not be inspected without authenticated access.
- `GET /repos/merizrizal/literp/commits/c3126c1987f650e21c2b3c6672fb776cd831ec07/check-runs`:
  returned zero check runs. The five most recent returned PRs had different head
  commits; they do not establish acceptance of the reviewed candidate.

These observations do **not** prove that no classic protection exists or that
merges are blocked. U1 remains unconfirmed. No remote setting was modified.

A maintainer can inspect **Settings → Rules → Rulesets** and classic branch
protection in the browser without installing `gh`. Recommended enforcement is
required PR checks for the exact workflow job names `Build`, `Test Baseline`,
`OpenAPI Verification`, and `Migration Verification`, with the intended branch
coverage and bypass policy explicitly reviewed. Verify the names against an
actual PR run and record a failed-check merge denial before closing U1. Use the
provider UI/session for privileged inspection; do not paste tokens into project
files, commands, collections or reports.

## Failure Notes

- Java or Gradle failures should be reproduced with `./gradlew build`.
- Migration failures require `DB_URL`; use `python/database/envrc.test` for the isolated test database.
- Seed verification failures usually mean the seed migration changed and the expected minimum seed checks in `scripts/verify_migrations.py` need to be reviewed with the migration change.
