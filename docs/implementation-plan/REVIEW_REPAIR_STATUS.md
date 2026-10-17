# Snapshot Review Repair Status

## Repair contract and candidate

- Review source: maintainer-supplied whole-snapshot review of Phase 00–04 and Phase 05 tasks 05.1–05.3.
- Reviewed/current HEAD: `c3126c1987f650e21c2b3c6672fb776cd831ec07`; the candidate includes cumulative uncommitted repairs. All pre-existing continuation work was preserved.
- Requirements: phase plans, approved ADS behavior, the product narrative, authentication baseline and accepted layer-based structure. No standalone PRD is assumed.
- The maintainer approved the recommended remaining financial and request-correlation decisions and continued implementation. The corresponding ADSs now distinguish approved decisions, completed local implementation, historical drafting text and external acceptance.
- No deployment, manufacturing, external payment settlement, credential change, push or merge is included.

## Finding ledger

| ID | Severity | Scenario / expected behavior | Disposition | Repair / evidence |
|---|---|---|---|---|
| B1 | BLOCKING | Concurrent same-order fulfillment with different keys must deduct once | CONFIRMED | Repaired: `FOR NO KEY UPDATE` serializes the order read. The earlier database-barrier regression reproduced two successful commands before repair; it now asserts one transition and single movement/event/idempotency effects. |
| B2 | BLOCKING | Reject monetary precision/overflow rather than rounding | CONFIRMED | Repaired: positive bounded two-decimal capture validation precedes database work; YAML/JSON synchronized. Earlier HTTP reproduction returned 201 for CASH 0.004. Regression covers that value, CARD 1.999, overflow, no persisted command/payment, then valid capture/replay. |
| B3 | BLOCKING | Lifecycle conflicts must remain 409 through the generated proxy | CONFIRMED | Repaired: known POS exceptions become structured service failures, mapped before message fallbacks. Earlier router regressions reproduced two 500s; occupied-terminal open/deactivation now return 409. Refund owner/validation/conflict failures are also exercised through the production proxy. |
| B4 | BLOCKING | Refuse downgrade that would discard financial history | CONFIRMED | Repaired: table locks and pre-DDL guards protect order/payment attribution and trusted shift metadata. Seven PostgreSQL tests cover history preservation, each nullable field, long actor identity, whole-chain rollback and safe legacy downgrade/re-upgrade. Head and forward schema unchanged. |
| B5 | BLOCKING | Collection-level token must be empty | CONFIRMED | Repaired: only the collection token value was removed; regression requires empty credentials, bearer-variable indirection and no JWT literal. The earlier regression failed before removal. Authenticity/provenance is unknown; history was not rewritten and no revocation is claimed. |
| B6 | BLOCKING | Whole-task acceptance requires issuance and audited refunds | CONFIRMED | Locally repaired: immutable issuance, append-only payment/receipt-bounded refunds, owner/open-shift/location/currency guards, CASH availability and refund-aware reconciliation are enabled through the production router. No POS operation remains 501. Exact settlement and API-issued-receipt restrictions follow the approved ADS; excess-tender recovery, inventory returns, tax/fiscal compliance and external settlement are excluded. |
| B7 | BLOCKING | Trace requests through asynchronous repository execution | CONFIRMED | Locally repaired: request-scoped proxy delivery headers and direct scope gateways carry an explicit immutable ID. Repository lifecycle logs contain only safe ID/operation/repository/outcome/duration fields. A database-barrier HTTP regression proves overlapping writes retain distinct IDs through check/create callbacks, reads/errors and direct scope queries; malformed ID replacement and trace-payload privacy are asserted. |
| N1 | NON_BLOCKING | Public 500 responses must not expose SQL details | CONFIRMED | Repaired: fixed public messages replace raw exception concatenation in generic mapping and master-data list handlers; diagnostic throwables remain server-side. Earlier router regression reproduced a PostgreSQL FK/constraint message; the public response is now sanitized. |
| N2 | NON_BLOCKING | Documentation must match runtime surface/setup/behavior | CONFIRMED | Repaired: docs describe 43 implemented operations, transactional commands, NULL sale OUT destination, authentication configuration before startup, local-only refunds and actual correlation behavior. Historical acceptance records remain explicitly historical. |
| U1 | Concern | Required CI checks must actually prevent merging failures | UNCONFIRMED | External evidence gap remains. Read-only REST inspection works without `gh`: effective main-branch rules contain only deletion/non-fast-forward restrictions. Classic protection still returns 401. No remote policy was changed; authenticated browser/classic-protection and matching PR enforcement evidence are needed. |

## Financial implementation and regression coverage

`PosReceiptIssuanceTest` now covers issuance plus the refund lifecycle:

- Fractional quantity/split-payment snapshots, exact settlement, after-close issuance, immutable snapshot/replay, hidden resources and duplicate issuance.
- Partial/full refunds and payment status transitions; payment and receipt caps; malformed snapshots, foreign payment IDs, legacy receipts, currency mismatch, blank reasons, precision/range and owner/scope guards.
- CASH refunds in the sale shift and a later shift; original inflows remain counted after `REFUNDED`, refund outflows are deducted only on their refund shift, and earlier closed snapshots remain unchanged.
- Database barriers for same/different-key issuance/refunds and refund-versus-close serialization. Both legal serial orders are accepted; a new refund cannot write after close.
- Failed command-response persistence rolls back the refund row and payment status. Normal-connection DELETE is rejected by the append-only trigger.

The production HTTP lifecycle now sells, closes/fulfills, issues/reads/replays a receipt, opens a later refund shift, records partial/full CASH refunds, closes it with correct cash, and replays after closure. It rejects spoofed identity fields, service actors, owner/location violations and excess/invalid amounts. Fulfillment movement count and the original closed snapshot remain unchanged.

Refund fixture cleanup is confined to `src/test`: it checks the selected test database, uses a privileged isolated test role and `SET LOCAL session_replication_role` on one transaction-local connection, and deletes only the fixture order's adjustments. It does not alter production schema, disable triggers globally, or change the runtime append-only policy. CI's disposable PostgreSQL superuser role supports this cleanup.

## Correlation implementation and evidence

- `RequestCorrelation` accepts 1–128 ASCII letters/digits or `._:-`; missing/unsafe IDs receive a UUID. The canonical ID is returned in the HTTP header and copied into per-request proxy delivery options.
- Per-request handler/proxy instances preserve that ID through asynchronous follow-up calls. No shared mutable current-request field or event-loop ThreadLocal is used.
- `RepositoryServiceTracing` wraps each service message/reply with an invocation-owned completion closure; repository loggers emit structured start/success/failure events without serializing arguments, responses or exception details.
- Direct order-scope gateways use the same lifecycle logging around Rx subscriptions. These are operation-boundary logs, not per-SQL profiling or distributed tracing.
- The concurrent HTTP/log-capture regression deliberately blocks both writes in PostgreSQL, then checks each ID's check/create operation pair and completion. Read/error, direct list/location-scope paths, oversized-ID replacement and absence of private body/bearer values are asserted. A separate unit test covers control characters, oversized and missing IDs.
- The first new HTTP trace test failed because its fixture code violated the existing uppercase location-code schema; only the fixture was corrected. No pre-fix trace-test execution is fabricated: the original missing logging was substantiated by source inspection, and the new regression verifies the repaired path.

## Verification executed

Python used the maintainer-approved `~/Documents/PyEnv/myEnv/` environment.

- Relevant issuance/HTTP baseline passed before the remaining implementation.
- Focused financial repository, production HTTP, POS contract, correlation and operation-registration checks passed after each repair slice.
- `rtk proxy ./gradlew --no-daemon build --rerun-tasks` passed against a freshly migrated disposable PostgreSQL 18.6 database with matching runtime/test overrides: **115 tests, zero skipped/failures/errors**, verified from all JUnit XML reports.
- `rtk proxy python -B scripts/verify_migrations.py` passed fresh upgrade and final head/seed/schema verification at `c7a8e2f4d6b1`.
- `rtk proxy python -B scripts/test_migration_downgrade_safety.py` passed **7 tests** on newly created temporary databases.
- `rtk proxy python -B scripts/verify_openapi_assets.py` passed all four YAML/JSON pairs. Runtime parsing is also exercised by the production-router tests.
- Complete diff and untracked source/test files were reviewed. Final relative-link/CI YAML checks, credential guard, fixture/trigger cleanup and `git diff --check` passed. Kotlin/Java compilation verifies syntax/symbols; no repository Kotlin formatter is configured. Python syntax compilation and focused Ruff checks passed again; Black was executed in the earlier repair batch and unrelated formatting churn was removed.
- The fresh verification database contained no remaining test refund/API-receipt rows, scoped command fixtures or failure triggers. Temporary downgrade databases were confirmed removed; the repair-created disposable container was stopped/removed. The existing test database was not reset or downgraded.
- Existing KAPT source-version and Gradle deprecation warnings remain. `bru` is unavailable and no external authenticated collection/provider/deployment acceptance is claimed. Asset/contract parity and deterministic authenticated HTTP tests passed.

## U1: remaining external gate, not unfinished local implementation

The final read-only REST recheck returned HTTP 200 with effective rules `deletion` and `non_fast_forward`, and HTTP 401 for classic branch protection. These results do not prove absence of classic protection or actual merge blocking. The previous inspected reviewed-commit check run count was zero; that is not a substitute for a PR run of the current uncommitted candidate.

A maintainer can complete inspection in the GitHub browser UI without installing `gh`, following [CI verification](../CI_VERIFICATION.md#inspect-merge-enforcement-without-gh). Verify required jobs, protected branch coverage, bypass policy and an actual failed-check merge denial; record nonsecret evidence. Do not paste credentials into this repository or a conversation. If policy changes are needed, they remain remote administrative work, not a local YAML repair.

Local B1–B7 and N1–N2 repair implementation is complete and may undergo independent review. Full Phase 04 acceptance remains **BLOCKED on U1 external enforcement evidence**, not on more financial/correlation coding or another design approval. HEAD is unchanged; all repairs remain uncommitted. No commit, push, deployment, remote-policy mutation or credential change is claimed.
