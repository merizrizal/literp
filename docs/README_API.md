# Documentation Index

This directory documents the current implementation on this branch.

## Read in this order

1. [QUICK_START.md](QUICK_START.md)
2. [API_IMPLEMENTATION.md](API_IMPLEMENTATION.md)
3. [API_TESTING_GUIDE.md](API_TESTING_GUIDE.md)
4. [ENDPOINTS_OVERVIEW.md](ENDPOINTS_OVERVIEW.md)
5. [CI_VERIFICATION.md](CI_VERIFICATION.md)
6. [LOCAL_RESET.md](LOCAL_RESET.md)
7. [VERIFICATION_CHECKLIST.md](VERIFICATION_CHECKLIST.md)

## File Map

### Setup and usage

- [QUICK_START.md](QUICK_START.md)
  Setup, Docker database startup, migrations, Bruno collection, first requests.

- [LOCAL_RESET.md](LOCAL_RESET.md)
  Non-destructive and destructive local PostgreSQL reset paths.

### Technical reference

- [API_IMPLEMENTATION.md](API_IMPLEMENTATION.md)
  Architecture, handler/service/repository layers, endpoint behavior, caveats.

- [ENDPOINTS_OVERVIEW.md](ENDPOINTS_OVERVIEW.md)
  Complete endpoint inventory, utility routes, and order lifecycle map.

### Testing

- [API_TESTING_GUIDE.md](API_TESTING_GUIDE.md)
  Curl examples for master data and order flow.

- [CI_VERIFICATION.md](CI_VERIFICATION.md)
  Required CI checks and local reproduction commands.

- [VERIFICATION_CHECKLIST.md](VERIFICATION_CHECKLIST.md)
  Validation checklist for runtime behavior, docs, and assets.

### Branch summary

- [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md)
  What this branch adds on top of the original skeleton.

### Implementation plan

- [implementation-plan/00-implementation-overview.md](implementation-plan/00-implementation-overview.md)
  Phased plan with recorded completion and remaining acceptance boundaries.

- [implementation-plan/ADS_COVERAGE_REVIEW.md](implementation-plan/ADS_COVERAGE_REVIEW.md)
  Task-to-ADS coverage, product/knowledge alignment, missing designs, and unresolved decisions.

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

- Kotlin `2.4.20`
- Vert.x `5.2.0`
- Java `25`
- 41 implemented API endpoints across 6 API domains, including scoped receipt lookups
- 2 authenticated POS receipt-generation/refund write placeholders (`501 NOT_IMPLEMENTED`), for 43 registered operations
- 5 utility endpoints (two public probes and three restricted operational routes)
- deterministic seed data through Alembic
- synchronized authenticated Bruno collection and OpenAPI contracts
- application-side authentication baseline with provider/deployment acceptance pending
