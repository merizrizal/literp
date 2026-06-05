# Quick Start

This guide matches the current branch state:
- 29 implemented API endpoints
- schema + seed data via Alembic
- Bruno collection in `api_collections/Literp`

## Prerequisites

- Java 25
- Docker and Docker Compose
- Bash shell
- `jq` recommended for readable JSON

Optional:
- Bruno for request collection testing
- Python if you want to run Alembic manually outside Docker

## Start the Database

From the repository root, create a local app config file:

```bash
cp cfg.properties.template cfg.properties
```

Then start the local development database:

```bash
cd docker
source envrc
make network
DIR=pgsql make env-up
```

What this does:
- creates or reuses the Docker network from `DOCKER_NETWORK`
- starts PostgreSQL
- runs Alembic migrations to `head`
- populates deterministic seed data

The application reads connection settings from `cfg.properties`.
Non-blank environment variables override file values, using `LITERP_*`,
`PG_*`, or the existing `DB_*` names from `python/database/envrc`.

## Start the Test Database

Use the test database for automated tests, destructive checks, or any workflow
that should not touch local development data.

```bash
cd docker
source envrc
make network
DIR=pgsql-test make env-up
```

What this does:
- starts `literp_pgsql_test` on host port `55432`
- creates the `literp_test` database
- runs the same Alembic migrations to `head`
- loads the same deterministic seed data into the test database

From the repository root, point local commands or the application at the test
database:

```bash
source python/database/envrc.test
```

Because runtime config supports `DB_*` overrides, values from
`python/database/envrc.test` take precedence over `cfg.properties`.

## Build and Run

```bash
./gradlew build
./gradlew run
```

Server URLs:
- root: `http://localhost:8010`
- health: `http://localhost:8010/health/db`
- API base: `http://localhost:8010/api/v1`

## First Checks

```bash
curl http://localhost:8010 | jq
curl http://localhost:8010/health/db | jq
curl "http://localhost:8010/api/v1/uom?page=0&size=20&sort=code,asc" | jq
curl "http://localhost:8010/api/v1/locations?page=0&size=20&sort=code,asc&activeOnly=true" | jq
curl "http://localhost:8010/api/v1/orders?page=0&size=20&sort=orderDate,desc" | jq
```

## Use the Bruno Collection

Collection path:

```text
api_collections/Literp
```

Useful collection variables are already defined in [`collection.bru`](../api_collections/Literp/collection.bru):
- `host`
- `port`
- `uomId`
- `productId`
- `variantId`
- `locationId`
- `salesOrderId`

The collection includes:
- utility endpoints (`/`, `/health/db`)
- all 29 implemented API endpoints
- request bodies aligned to the actual handlers

## Manual Alembic Alternative

If you do not want the migration container to run it for you:

```bash
cd python/database/migration
DB_URL=postgresql://root:pgdevpassword@localhost:5432/literp alembic upgrade head
```

For the test database:

```bash
cd python/database/migration
DB_URL=postgresql://root:pgdevpassword@localhost:55432/literp_test alembic upgrade head
```

## Recommended Test Flow

### Master data

1. Create or list UOMs
2. Create or list products
3. Create or list variants
4. Create or list locations

### Order process

1. Create draft order
2. Add line
3. Confirm order
4. Capture payment
5. Fulfill order
6. Fetch order details

## Current Endpoint Totals

| Domain | Endpoints |
|---|---:|
| Unit of Measure | 5 |
| Product | 5 |
| Product Variant | 5 |
| Location | 6 |
| Order Process | 8 |
| Total | 29 |

## Current Behavior Notes

- Product and variant deletes are soft deletes.
- UOM and location deletes are hard deletes.
- Order cancellation is blocked when captured payment exists.
- Fulfillment requires the order to be `CONFIRMED` and fully captured.
- The seed data is deterministic and intended for local demos and regression testing.

## Troubleshooting

### Database health is down

```bash
curl http://localhost:8010/health/db | jq
```

If it is down:
- verify Docker is running
- verify `DIR=pgsql make env-up` completed
- confirm `cfg.properties` still targets `localhost:5432`
- confirm environment overrides such as `DB_HOST`, `DB_PORT`, or `LITERP_PG_HOST` are not pointing elsewhere

### Port 8010 is in use

Edit `cfg.properties` and change:

```properties
http.port=8010
```

### Migration failed after partially creating objects

The initial migration is safe to rerun when PostgreSQL enum types or tables
already exist. If the database is in an unknown state, use the non-destructive
or destructive paths in [LOCAL_RESET.md](LOCAL_RESET.md).

For a destructive development database reset:

```bash
cd docker
source envrc
DIR=pgsql make env-down
DIR=pgsql make env-up
```

For the isolated test database, use `DIR=pgsql-test` instead.

### You want to browse the API quickly

Use:
- Bruno collection in `api_collections/Literp`
- curl examples in [API_TESTING_GUIDE.md](API_TESTING_GUIDE.md)
