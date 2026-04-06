# Documentation Index

This directory documents the current implementation on this branch.

## Read in this order

1. [QUICK_START.md](QUICK_START.md)
2. [API_IMPLEMENTATION.md](API_IMPLEMENTATION.md)
3. [API_TESTING_GUIDE.md](API_TESTING_GUIDE.md)
4. [ENDPOINTS_OVERVIEW.md](ENDPOINTS_OVERVIEW.md)
5. [VERIFICATION_CHECKLIST.md](VERIFICATION_CHECKLIST.md)

## File Map

### Setup and usage

- [QUICK_START.md](QUICK_START.md)
  Setup, Docker database startup, migrations, Bruno collection, first requests.

### Technical reference

- [API_IMPLEMENTATION.md](API_IMPLEMENTATION.md)
  Architecture, handler/service/repository layers, endpoint behavior, caveats.

- [ENDPOINTS_OVERVIEW.md](ENDPOINTS_OVERVIEW.md)
  Complete endpoint inventory, utility routes, and order lifecycle map.

### Testing

- [API_TESTING_GUIDE.md](API_TESTING_GUIDE.md)
  Curl examples for master data and order flow.

- [VERIFICATION_CHECKLIST.md](VERIFICATION_CHECKLIST.md)
  Validation checklist for runtime behavior, docs, and assets.

### Branch summary

- [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md)
  What this branch adds on top of the original skeleton.

### Domain notes

- [knowledge/PROJECT_OVERVIEW.md](knowledge/PROJECT_OVERVIEW.md)
- [knowledge/PROJECT_SUMMARY.md](knowledge/PROJECT_SUMMARY.md)
- [knowledge/MODEL_DESIGN.md](knowledge/MODEL_DESIGN.md)
- [knowledge/PROCESS_ORDER_PAYMENT_FULFILLMENT.md](knowledge/PROCESS_ORDER_PAYMENT_FULFILLMENT.md)

## Related Assets Outside `docs`

- Bruno collection: [`../api_collections/Literp`](../api_collections/Literp)
- OpenAPI specs: [`../api_collections/open_api_spec`](../api_collections/open_api_spec)
- Root overview: [`../README.md`](../README.md)

## Current Implementation Snapshot

- Kotlin `2.3.20`
- Vert.x `5.0.10`
- Java `25`
- 29 implemented API endpoints
- 2 utility endpoints
- deterministic seed data through Alembic
- synchronized Bruno collection
