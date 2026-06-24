# CI Verification

The foundation gate requires the `Foundation Verification` workflow to pass
before merging implementation work.

## Required Checks

| Check | Runtime | Command |
|---|---|---|
| Build | Java 25, Gradle wrapper 9.5.1 | `./gradlew build` |
| Migration Verification | Python 3.13, PostgreSQL 18 | `python scripts/verify_migrations.py` |

The migration check runs Alembic to `head`, verifies the database revision
matches the repository head, and confirms that deterministic seed data exists in
core tables.

## Local Reproduction

Build check:

```bash
./gradlew build
```

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
