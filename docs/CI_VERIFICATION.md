# CI Verification

The foundation gate requires the `Foundation Verification` workflow to pass
before merging implementation work. The gate currently covers build, test
baseline, OpenAPI verification, and migration verification.

## Required Checks

| Check | Runtime | Command |
|---|---|---|
| Build | Java 25, Gradle wrapper 9.6.0 | `./gradlew build` |
| Test Baseline | Java 25, Gradle wrapper 9.6.0, isolated PostgreSQL test DB | `./gradlew test` |
| OpenAPI Verification | Python 3.13 | `python scripts/verify_openapi_assets.py` |
| Migration Verification | Python 3.13, PostgreSQL 18 | `python scripts/verify_migrations.py` |

The migration check runs Alembic to `head`, verifies the database revision
matches the repository head, and confirms that deterministic seed data exists in
core tables.

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

## Failure Notes

- Java or Gradle failures should be reproduced with `./gradlew build`.
- Migration failures require `DB_URL`; use `python/database/envrc.test` for the isolated test database.
- Seed verification failures usually mean the seed migration changed and the expected minimum seed checks in `scripts/verify_migrations.py` need to be reviewed with the migration change.
