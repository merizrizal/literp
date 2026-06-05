# Local Reset

Use this guide when the local PostgreSQL state needs attention.

Choose the non-destructive path first unless you intentionally want to remove
all local database data and recreate the schema plus seed data from migrations.

## Database Targets

| Target | Compose directory | Host port | Database | Data policy |
|---|---|---:|---|---|
| Development | `docker/pgsql` | `5432` | `literp` | keep unless deliberately resetting local work |
| Test | `docker/pgsql-test` | `55432` | `literp_test` | safe for destructive test resets |

## Non-Destructive Paths

Restart the development database without removing the Docker volume:

```bash
cd docker
source envrc
DIR=pgsql make env-stop
DIR=pgsql make env-up
```

Restart the test database without removing the Docker volume:

```bash
cd docker
source envrc
DIR=pgsql-test make env-stop
DIR=pgsql-test make env-up
```

Re-run migration and seed verification against the development database:

```bash
python3 -m pip install -r python/requirements.txt
source python/database/envrc
python scripts/verify_migrations.py
```

Re-run migration and seed verification against the test database:

```bash
python3 -m pip install -r python/requirements.txt
source python/database/envrc.test
python scripts/verify_migrations.py
```

## Destructive Reset Paths

These commands remove the PostgreSQL Docker volume for the selected target.
Only use them when local data can be discarded.

Reset the development database and reload schema plus seed data:

```bash
cd docker
source envrc
DIR=pgsql make env-down
DIR=pgsql make env-up
```

Reset the test database and reload schema plus seed data:

```bash
cd docker
source envrc
DIR=pgsql-test make env-down
DIR=pgsql-test make env-up
```

## After Reset

From the repository root, verify the selected database:

```bash
source python/database/envrc
python scripts/verify_migrations.py
```

For the test database, use:

```bash
source python/database/envrc.test
python scripts/verify_migrations.py
```

Then run the application build:

```bash
./gradlew build
```
