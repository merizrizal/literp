## Architectural Design Specification: Phase 04 Completion Evidence and Governance

**Source:** Remaining Definition of Done in `docs/implementation-plan/04-quality-contracts-observability.md`.

**Goal:** Establish current, reproducible evidence for Phase 04’s CI, HTTP-contract, error, logging, and documentation criteria; correct identified documentation/collection drift; and close only criteria that are demonstrably satisfied.

**Status:** Local closure slices completed through final validation; Phase 04 remains open for CI required-check enforcement and request-correlated repository logging.

---

### I. Overview and Contract

Phase 04.5 and 04.6 are accepted planning gates. The remaining phase-level criteria are:

1. CI blocks broken build, tests, and invalid OpenAPI contracts.
2. Public API behavior has HTTP integration coverage.
3. Error responses are stable and documented.
4. Logs trace a request through handler and repository work.
5. Documentation updates accompany API behavior changes.

**Observed concrete contracts:**

- CI workflow: `.github/workflows/foundation-verification.yml`; its jobs are `Build`, `Test Baseline`, `OpenAPI Verification`, and `Migration Verification`.
- OpenAPI contracts: three YAML/JSON pairs under `api_collections/open_api_spec`; 31 registered business operation IDs.
- Utility routes: `/`, `/metrics`, `/health/live`, `/health/ready`, `/health/db`.
- Error envelope: `error`, `errorCode`, `status`, `errorId`; request ID response header: `X-Request-ID`.
- Existing HTTP suites: `MasterDataHttpIntegrationTest` and `OrderProcessHttpIntegrationTest`.

**Function Signature Contracts:** Not applicable until Chunk 0 identifies an actual coverage or observability gap. No new runtime function or stub is authorized merely to mark a checkbox.

**Completion contract:** A Phase 04 Definition-of-Done checkbox changes to complete only when its evidence is recorded at the current revision. Remote GitHub required-check policy is an external configuration contract; a repository workflow alone cannot prove merge blocking.

### II. Observed Evidence and Assumptions

#### Observed evidence

- Current full test run passed: 36 tests, zero skipped, failures, and errors. Current Gradle build passed.
- Fresh OpenAPI asset verification passed using `~/Documents/PyEnv/myEnv`: all YAML/JSON pairs matched and version `0.0.1` matched `build.gradle.kts`.
- The workflow invokes build, isolated PostgreSQL tests, OpenAPI verification, and migration verification on pull requests and pushes to `main`/`master`.
- `BaseHandler` emits request/error IDs, method, path, status, error code, and request ID; HTTP tests assert request-ID propagation and error outcomes.
- Chunk 3 corrected the audited route/utility inventory in `docs/VERIFICATION_CHECKLIST.md`, `README.md`, `docs/ENDPOINTS_OVERVIEW.md`, and `docs/API_IMPLEMENTATION.md`.
- Current Bruno collection contains all five utility requests, including the three utility assets added in Chunk 3.

#### Assumptions requiring confirmation

- Existing integration suites cover all 31 operation IDs with actual HTTP requests; production-router utility and global-failure behavior is covered by the added smoke test.
- Repository logging does not currently include request correlation beyond startup database-connection activity.
- Required-check enforcement remains an external criterion and is not locally verifiable.

### III. Required Technical Dependencies and Imports

- Existing Gradle wrapper, JDK 25, isolated PostgreSQL test database, JUnit XML reports, and OpenAPI verifier.
- Use the user-provided Python environment `~/Documents/PyEnv/myEnv` for `scripts/verify_openapi_assets.py`.
- Remote branch-protection evidence is not available locally. Do not change remote settings without explicit authorization.
- No new dependency is expected for documentation or audit slices. Any proposed logging capture/test dependency requires Chunk 0 confirmation.

### IV. Step-by-Step Procedure / Execution Flow

1. Reconcile every OpenAPI operation ID, utility route, Bruno request, documentation entry, and HTTP-test request.
2. Record a current evidence matrix that distinguishes automated coverage, manual smoke checks, and external CI-governance status.
3. Repair only confirmed documentation/collection drift in small slices.
4. If a route, error condition, or trace requirement lacks evidence, add the smallest targeted test or instrumentation slice; do not broadly refactor handlers/repositories.
5. Keep required-check enforcement explicitly external; do not change remote settings during this local closure pass.
6. Run the complete local validation baseline using the isolated database and supplied Python environment.
7. Update Phase 04 completion status only with current evidence; leave any external or uncovered criterion open.

### V. Failure Modes and Resilience

| Stage | Failure mode | Action | Next state |
|---|---|---|---|
| CI governance | Required checks cannot be inspected locally | Leave the criterion open; no local repository change can prove merge blocking | External blocker; criterion remains open |
| Coverage audit | A route lacks a real HTTP assertion | Add one focused test to its existing domain suite | Targeted test slice required |
| Documentation audit | Count/path/route text is stale | Correct only confirmed affected docs | Docs synchronized |
| Bruno audit | Utility request is missing | Add a request only after reading existing `.bru` conventions | Manual collection parity slice |
| Error/log audit | Tests do not prove a required envelope/trace field | Add a focused test or explicit evidence record | Criterion stays open until pass |
| Validation | DB unavailable or tests skip | Start isolated test DB and rerun; do not use development data | Validation blocked until non-skipped run |
| OpenAPI verification | YAML/JSON/version drift | Fix the specific source/derived asset pair in its own slice | Contract validation blocked |

### VI. Security, Integrity, Idempotency, and Cleanup

- Do not place database passwords, access tokens, or GitHub credentials in documentation or test logs.
- Keep request IDs and error IDs as correlation data; do not add request bodies or secrets to logs.
- Documentation evidence must state whether it is automated, manual, historical, or external.
- CI/branch-protection changes are security-sensitive governance changes and require maintainer authorization.
- Test fixtures must clean up generated data and preserve existing idempotency behavior.
- No feature, security-baseline, package migration, or generated-proxy work belongs in this closure ADS.

### VII. Validation Strategy

Use the narrowest validation for each chunk; run this final set before closing any criterion:

```bash
JAVA_TOOL_OPTIONS='-Djdk.lang.Process.launchMechanism=FORK' rtk ./gradlew --no-daemon test --rerun-tasks
JAVA_TOOL_OPTIONS='-Djdk.lang.Process.launchMechanism=FORK' rtk ./gradlew --no-daemon build
source ~/Documents/PyEnv/myEnv/bin/activate && rtk python scripts/verify_openapi_assets.py
rtk git diff --check
```

Inspect JUnit XML reports for executed, skipped, failure, and error counts. Validate new/changed documentation links and every listed path. Keep CI required-check enforcement open unless authorized external evidence is supplied; local workflow syntax is insufficient.

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full closure pass in one session.

#### Chunk 0: Coverage, Governance, and Drift Audit
- **Goal:** Produce a read-only route/test/doc/Bruno/CI evidence matrix and identify exact gaps.
- **Files to read:** OpenAPI YAMLs; `HttpServerVerticle`; both HTTP integration tests; error/logging code; Bruno collection; current API/verification docs; workflow; JUnit reports.
- **Commands:** Route inventory searches, test-request searches, `rtk git status --short`, and local-only CI/governance capability checks.
- **Evidence to confirm:** Every operation/test mapping; all utility routes; Bruno request inventory; log trace feasibility; exact stale docs; external required-check status recorded as blocked.
- **Stop condition:** No edits; evidence matrix identifies each later chunk’s exact files and whether it is necessary.

#### Chunk 1: Evidence Record and Documentation Inventory
- **Goal:** Add a canonical current-revision Phase 04 evidence record without changing completion checkboxes.
- **Files to change:** Proposed `docs/knowledge/PHASE_04_COMPLETION_EVIDENCE.md` only.
- **Symbols to add/change:** Documentation sections for automated tests, OpenAPI verification, HTTP coverage mapping, error/log evidence, Bruno status, CI governance, and remaining blockers.
- **Implementation shape:** Record confirmed facts from Chunk 0; label incomplete/external evidence explicitly. Do not copy credentials or claim branch protection from workflow YAML alone.
- **Validation:** Markdown structure/link checks and scoped diff.
- **Stop condition:** One source of truth exists for evidence; no phase checkbox changes.

#### Chunk 2: Confirmed HTTP, Error, and Trace Gap Slice
- **Goal:** Close exactly one verified test/evidence gap, if Chunk 0 finds one.
- **Files to change:** At most one affected HTTP integration test and one directly related implementation file only if a production gap is proven.
- **Symbols to add/change:** **Conceptual:** one focused test case or one minimal correlation field; confirm exact names in Chunk 0.
- **Implementation shape:** Prefer a test that exercises the production router and asserts status, normalized error envelope, `X-Request-ID`, and trace-relevant behavior. Do not introduce a logging framework or broad handler refactor.
- **Validation:** Targeted Gradle test, then JUnit report inspection and scoped diff.
- **Stop condition:** The identified gap is covered, or report no code change is needed with evidence.

#### Chunk 3: API and Bruno Asset Parity Slice
- **Goal:** Correct confirmed route/count/utility-request drift without mixing unrelated documentation cleanup.
- **Files to change:** Confirmed stale documentation files and missing utility Bruno requests, limited to audited parity artifacts.
- **Symbols to add/change:** Documentation route inventories or existing Bruno request conventions only.
- **Implementation shape:** Make the external inventory reflect the exact current contract. Add missing utility Bruno requests only after copying observed collection/header/environment conventions.
- **Validation:** Path/link checks, OpenAPI verifier using the supplied environment, and scoped diff.
- **Stop condition:** Corrected artifacts match the audited inventory; no unverified count remains.

#### Chunk 4: Final Validation and Phase Closure
- **Goal:** Run current final validation and mark only proven Phase 04 Definition-of-Done criteria complete.
- **Files to change:** `docs/implementation-plan/04-quality-contracts-observability.md`; `docs/knowledge/PHASE_04_COMPLETION_EVIDENCE.md`.
- **Symbols to add/change:** Checklist state and validation revision/result fields only.
- **Implementation shape:** Reconcile every criterion to automated, manual, or external evidence. Keep optional Bruno linting deferred unless a stable command is selected. Do not claim Phase 04 complete if any required criterion lacks evidence.
- **Validation:** Section VII commands, JUnit counts, Markdown/link checks, staged and unstaged diff review.
- **Stop condition:** Phase 04 is either evidence-complete or has a bounded, explicit blocker list.

### IX. Handoff to `chunked-implementation`

Recommended agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, pre-edit-discipline, safe-python-edit, and post-edit-discipline if available.

Task:
Execute Chunk 0 from docs/implementation-plan/ads/phase-04-closure-verification.md.

Mode:
Single chunk only. Do not edit files. Produce the route/test/doc/Bruno/CI evidence matrix and stop.
```

After Chunk 0 is accepted:

```text
Use the chunked-implementation skill.
Execute only the next necessary chunk from docs/implementation-plan/ads/phase-04-closure-verification.md.
Run targeted validation, review the complete diff, record risks, and stop.
Do not change remote GitHub settings without explicit maintainer authorization.
```

### X. Conclusion and Next Steps

The local test/build/OpenAPI baseline is healthy and the audited documentation/Bruno/production-router slices are synchronized. Phase 04 remains open only for CI required-check enforcement and request-correlated repository logging; do not mark those criteria complete without new evidence or implementation.
