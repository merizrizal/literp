## Architectural Design Specification: Security Sequencing and Project Structure Gates

**Source:** `docs/implementation-plan/04-quality-contracts-observability.md`, tasks **04.5 Security Planning Gate** and **04.6 Project Structure Gate**.

**Continuity:** `literp-kotlin-vertx-upgrade-handoff.md` and its incorporated `literp-phase04-finalization-handoff.md` in the external handoff directory. Repository plans and current code take precedence over historical handoff status.

**Goal:** Decide security ownership, the first protected endpoint surface, and the package/API asset layout for Phase 05; publish those decisions and evidence-based Phase 04 closure without implementing authentication or moving source files in this ADS task.

**Status:** Proposed design for review. Decisions below are recommendations, not accepted policy or implemented controls. Creating this ADS does not complete either gate.

---

### I. Overview and Contract

#### Scope and gate outputs

These tasks deliver planning contracts, not new HTTP handlers, database models, or runtime middleware.

| Gate | Inputs | Required output | Acceptance contract |
|---|---|---|---|
| 04.5 | Current routes, stable order/inventory workflow, Phase 05 roadmap | Security sequencing decision, owner phase, first protected endpoint matrix, bounded follow-up scope | Maintainer approves timing and minimum controls; roadmap explicitly owns auth implementation |
| 04.6 | Existing package/assets layout, codegen/path consumers, test evidence | Keep-or-move decision for backend and API assets; concrete Phase 05 placement rules | Maintainer accepts layout; README and implementation plan agree; any actual move requires separate validated mechanical slices |
| Phase 04 closure | Accepted decisions plus current verification evidence | Per-criterion completion or explicit blocker | No unchecked requirement is silently converted into completion |

**Proposed document contracts:**

- `docs/knowledge/SECURITY_SEQUENCING.md` — status, approving maintainer/date, owner phase, before/after Phase 05 decision, endpoint/permission matrix, trust boundaries, auth follow-up acceptance criteria, deferred scope, and review triggers.
- `docs/knowledge/PROJECT_STRUCTURE_DECISION.md` — status, approving maintainer/date, alternatives, chosen package and asset layout, domain placement map, migration constraints, verification evidence, and revisit trigger.

These paths are proposed new files in the existing knowledge directory, not pre-existing ADRs. Keep one canonical decision per topic and link to it from the roadmap/README.

**Function Signature Contracts:** Not applicable: no new executable function, type, import, endpoint, payload, or schema is required by these decision gates. The stub-first equivalent is a document skeleton whose status is explicitly `Pending decision`; it cannot grant approval or claim a gate passed. No runtime no-op auth stub is permitted.

**Proposed state contract:** `Pending decision -> Proposed -> Accepted -> Gate recorded complete`. Record `Blocked` with reason whenever approval, evidence, or a prerequisite is missing. These are documentation states, not application error codes.

#### Recommended security decision, subject to approval

- **Owner phase:** Phase 05, through a proposed new **05.0 Authentication and Authorization Baseline**, executed before 05.1 and all Phase 05 expansion implementation. Phase 04.5 owns the sequencing decision only.
- Choose auth **before expansion**, now that the baseline business workflow and tests exist. This respects the overview's instruction not to build IAM before proving the workflow.
- No internet-facing or otherwise untrusted deployment of the currently unauthenticated business API while the baseline is pending. Network isolation is an interim operational constraint, not authorization.
- Prefer an existing identity provider and validated bearer credentials over building user/password administration. JWT placeholders in two specs do not establish an implemented provider or control. Provider, issuer/audience, credentials lifecycle, deployment topology, and location/organization access policy require confirmation in the follow-up security ADS.
- Implement a minimal authenticated principal and explicit permissions with deny-by-default authorization. Broad IAM, self-service registration, complex role administration, and unrelated tenancy redesign remain out of scope for the first slice.

**Proposed minimum first-auth matrix:** all business routes below are relative to `/api/v1`. Permission names are conceptual capabilities, not existing roles or token claims. Each grouped row must be expanded to exact method/path/operation IDs in the security decision record and reconciled against the three YAML contracts and router registrations.

| Endpoint group | First protection policy | Conceptual capability |
|---|---|---|
| GET list/detail UOM, products, variants, locations, and location-by-code | Authenticated and authorized read | Master-data read |
| POST/PUT/DELETE UOM, products, variants, locations | Authenticated and authorized maintenance | Master-data write |
| GET `/orders`, GET `/orders/{salesOrderId}` | Authenticated, authorized, resource-scoped read | Order read |
| POST `/orders`, POST `/orders/{salesOrderId}/lines` | Authenticated, authorized creation/editing | Order write |
| POST `/orders/{salesOrderId}/confirm` | Authenticated, authorized reservation command | Order confirm |
| POST `/orders/{salesOrderId}/payments` | Authenticated, authorized payment recording | Payment capture |
| POST `/orders/{salesOrderId}/fulfill` | Authenticated, authorized inventory mutation | Order fulfill |
| POST `/orders/{salesOrderId}/cancel` | Authenticated, authorized cancellation | Order cancel |
| GET `/stock/current`, GET `/stock/available` | Authenticated and authorized location/product read | Inventory read |
| GET `/metrics`, `/health/ready`, `/health/db` (root paths) | Operator/service identity or explicitly enforced private operator network; not public by default | Operations read |
| GET `/`, `/health/live` (root paths) | Explicit public exception only with minimal non-sensitive response; preserve probe access | Public probe |

The first deployable auth slice protects the entire business surface, not just write verbs. Internal implementation may proceed one command path at a time, but a partially protected build must not be exposed as the accepted baseline. Existing business constraints, transactions, and idempotency checks remain in force after authorization.

#### Recommended structure decision, subject to approval

Keep the current **layer-based Kotlin layout with domain-grouped Java service interfaces** through Phase 05. Keep OpenAPI in `api_collections/open_api_spec` and Bruno in `api_collections/Literp`. Avoid structural churn while adding security and the next domain contracts.

| Domain | Existing or proposed placement under retained layout |
|---|---|
| Catalog | Existing Kotlin `verticle/handler`, `repository`, `service/master/impl`; Java `service/master` |
| Location | Existing Kotlin `verticle/handler`, `repository`, `service/master/impl`; Java `service/master` |
| Order | Existing Kotlin `verticle/handler`, `repository`, `service/order/impl`; Java `service/order` |
| Inventory | Existing stock operations remain in the order-process handler/service/repository; do not extract them just to satisfy a layout diagram |
| POS | Proposed Kotlin handlers/repositories remain in the existing layers; proposed Kotlin `service/pos/impl` and Java `service/pos` for new services |
| Manufacturing | Proposed Kotlin handlers/repositories remain in the existing layers; proposed Kotlin `service/manufacturing/impl` and Java `service/manufacturing` for new services |

All Kotlin paths above are relative to `src/main/kotlin/com/literp`; Java paths are relative to `src/main/java/com/literp`. New package names are proposed; confirm proxy module/codegen conventions before creating them. Do not create empty packages or placeholder services in this gate. New OpenAPI bundles and Bruno requests use existing asset roots and are registered in the verifier/tests when implemented.

Alternatives to evaluate and record: domain-first packages for catalog/location/order/inventory/POS/manufacturing now, and asset relocation to `api/openapi` / `api/bruno`. Defer both under the recommended keep-layout decision. Revisit after Phase 05 or earlier if measured coupling, navigation problems, or contract-loading/deployment needs justify migration.

### II. Observed Evidence and Assumptions

#### Observed evidence

- Repository `HEAD` is `c4ad200 build(deps): upgrade Kotlin and Vert.x versions`; the working tree was clean before this ADS was created. `build.gradle.kts` confirms Kotlin `2.4.20`, Vert.x `5.1.8`, and Java 25. Do not repeat that upgrade.
- `04-quality-contracts-observability.md`, sections 04.5/04.6, leaves both gates unchecked. It explicitly permits retaining the current layout and requires README/plan updates. Its final Definition of Done also remains unchecked; optional Bruno linting in 04.3 is still open.
- `00-implementation-overview.md`, the phase-discipline/security paragraph, says to prove the workflow before IAM. `05-pos-manufacturing-expansion.md`, Entry Gate, already requires package and asset layout decisions but does not yet assign auth an owner task.
- `HttpServerVerticle.kt`, `loadApiContracts` and the three `register*Handlers` methods, loads three YAML bundles from `api_collections/open_api_spec` and registers 31 business operation IDs: 15 catalog, 6 location, 10 order/stock. It also registers five utility routes: `/`, `/metrics`, `/health/live`, `/health/ready`, `/health/db`.
- `api_collections/open_api_spec/order-process.yaml`, `/stock/current` and `/stock/available`, confirms the stock reads. `README.md` and `docs/ENDPOINTS_OVERVIEW.md` still describe 29 business endpoints and two utility routes; those counts are not an authoritative security inventory.
- `product-catalog.yaml`, `components.securitySchemes.bearerAuth`, explicitly describes future authentication. A matching placeholder exists in `locations.yaml`. No auth enforcement was found in the inspected router or the targeted source search. Do not interpret security scheme declarations alone as protection.
- `OrderProcessService.java` exposes order commands and stock reads. `createProxy`/`register` use the stable `service.order.process` address and generated proxy type. `service/master/package-info.java` and `service/order/package-info.java` contain `@ModuleGen` group packages. Package moves would affect more than Kotlin imports.
- `build.gradle.kts`, `sourceSets` and `annotationProcessing`, configures generated Java sources. `src/main/resources/META-INF/native-image/reflect-config.json` currently contains framework/JDK names; any future move must inspect reflection/build consumers rather than assuming application entries exist.
- `OpenApiOperationIdRegistrationTest.kt` compares YAML IDs with source-text `getRoute` registrations and hardcodes the YAML/server file paths. This is a useful drift check, not proof that every route can run.
- `OrderProcessHttpIntegrationTest.kt` includes current/available stock assertions. `MasterDataHttpIntegrationTest.kt` includes health/metrics tests; some utility tests use a test router, so they do not replace a production-router startup smoke check after a relocation.
- `.github/workflows/foundation-verification.yml` defines Build, Test Baseline, OpenAPI Verification, and Migration Verification jobs. `docs/CI_VERIFICATION.md` explains isolated DB setup and skip behavior. `TestDatabase.assumeAvailable` skips DB-backed tests when the DB is unavailable.
- `scripts/verify_openapi_assets.py`, `OPENAPI_BUNDLE_NAMES` and `verify_openapi_assets`, hardcodes three bundle names and the current asset directory. It parses YAML/JSON, checks mapping/version fields, and compares canonical content; it is not a full OpenAPI semantic/schema validator or Bruno linter.
- The upgrade handoff reports 35 tests, zero skipped/failures/errors. That is historical evidence, not a new test run or proof of current CI branch-protection settings.

#### Assumptions and Chunk 0 confirmations

- A maintainer can approve security timing, deployment trust boundaries, and the keep-layout recommendation. Do not invent an approver or mark approval from silence.
- Confirm whether location/organization restrictions are required; request parameters and caller-supplied actor IDs must not be treated as identity or authorization.
- Confirm the proposed 05.0 ownership and whether its estimate/scope changes the roadmap. If auth is instead deferred, require an explicit owner task, deployment restriction, rationale, risk acceptance, and deadline; do not leave it as unowned future work.
- Confirm current test execution, CI enforcement, and whether semantic OpenAPI validation is already supplied elsewhere before claiming the full Phase 04 contract gate is complete.
- No restructuring is authorized by this ADS. If the keep-layout recommendation is rejected, stop and revise the chunk ladder for the selected migration before moving anything.

### III. Required Technical Dependencies and Imports

- **Decision delivery:** Markdown files, Git, current plans, router/contracts, and maintainer review. No added runtime dependencies, imports, migrations, secrets, provider accounts, or network services.
- **Evidence checks:** Existing Gradle wrapper/JDK 25, JUnit tests, isolated PostgreSQL test database, and the existing Python OpenAPI verifier with dependencies declared in `python/requirements.txt`.
- Obtain and activate the user's Python virtual environment before executing Python verification commands. Do not install tools or dependencies globally as part of this gate.
- No Markdown formatter was identified in the inspected Gradle/workflow configuration. Use existing Markdown conventions and whitespace/structure checks unless an existing project formatter is confirmed; do not introduce a formatter just for this document.
- Future auth dependencies and contracts belong in the separate 05.0 security ADS after provider and policy confirmation.

### IV. Step-by-Step Procedure / Execution Flow

1. Re-read exact task requirements and check current repository state. Treat prior ADS proposals and handoff test counts as historical context.
2. Confirm the route inventory from YAML and production registrations, including stock and operational routes. Identify current authentication placeholders and actual enforcement separately.
3. Create decision-record skeletons with pending status, required fields, and unresolved questions. Do not add completion checkmarks.
4. Review security alternatives with the maintainer; accept a timing/owner decision and complete the first-protected endpoint matrix. Specify deny-by-default policy, identity/resource-scope constraints, operator exceptions, and acceptance tests for the follow-up implementation.
5. Publish the accepted security sequencing in the overview and Phase 05 entry gate/ordered tasks. A planned auth task is not an implemented auth control.
6. Review keep-versus-move alternatives. Under the recommended path, accept the layer-based layout and existing asset roots, specify placement for all six domains, and record revisit triggers. No files move.
7. Update README and Phase 05 placement guidance to reference the canonical structure decision; make shared entry gates consistent without marking future auth work complete.
8. Reconcile Phase 04 acceptance criteria with current evidence. Record validation commands/results, skipped checks, and blockers. Optional Bruno linting can remain explicitly deferred; required semantic contract validation or CI enforcement gaps cannot be silently waived.
9. Mark 04.5/04.6 complete only after their decisions are accepted and published. Evaluate the overall Phase 04 Definition of Done separately; leave failed/unverified criteria open and assign bounded follow-up work.

**If a move is chosen:** interrupt this flow and produce a revised migration ADS. Inventory old/new packages, generated proxies, `@ModuleGen`, service addresses, imports/callers, reflection metadata, OpenAPI loader paths, verifier/test paths, Bruno manifests/environments, documentation, Docker/build and CI consumers. Define one coherent mechanical slice at a time with old-to-new mappings and tests. Do not combine auth behavior changes with file moves or edit generated proxy output manually.

### V. Failure Modes and Resilience

| Stage | Failure Mode | Agent/System Action | Next State/Error Report |
|---|---|---|---|
| Approval | Timing, owner phase, or layout lacks agreement | Record options and request maintainer decision; do not advance dependent chunks | Blocked decision, unresolved fields listed |
| Inventory | Old 29-endpoint docs omit stock/utility routes | Reconcile exact method/path/operation IDs from YAML and router | Proposed matrix corrected before acceptance |
| Security | Placeholder bearer scheme is mistaken for enforcement | State current protection gap and prohibit untrusted deployment until real baseline passes | Security implementation remains pending |
| Policy | Broad role names hide unauthorized object/location access | Require permission and resource-scope rules in the follow-up ADS | Auth design blocked on access boundary decision |
| Structure | Maintainer chooses relocation | Stop documentation-only ladder and design tested mechanical slices | Revised migration ADS required |
| Verification | DB unavailable or tests skipped | Record incomplete evidence and rerun with isolated test DB; do not use development data | Validation blocked; no coverage-based move approval |
| Contracts | Drift verifier passes but semantic validity is unproven | Confirm independent validation evidence or create explicit corrective follow-up | Required Phase 04 criterion stays open |
| CI | Workflow exists but required checks/remote run are unverified | Obtain current run/enforcement evidence from maintainer or CI access | Closure criterion unverified, not automatically complete |
| Publication | README, overview, and phase gates disagree | Resolve against accepted canonical decisions in the current chunk | Gate not complete until references agree |
| Optional tooling | No approved practical Bruno lint command exists | Document deferral and inspect collection paths without claiming lint passed | Optional item remains deferred |

### VI. Security, Integrity, Idempotency, and Cleanup

- **Security:** Never copy database credentials, tokens, auth headers, request bodies, or personal data into decisions/evidence. Document provider configuration by key name only. Preserve minimal liveness access while restricting operational details.
- **Future trust boundary:** Authentication and authorization must run before protected handlers/service mutations. Reject invalid/missing credentials (proposed HTTP 401) and insufficient permissions (proposed HTTP 403) without database side effects. These are future acceptance requirements, not current response contracts.
- **Resource integrity:** Define location/object authorization and actor derivation in the auth follow-up. Do not trust body fields such as `createdBy`, customer IDs, or location IDs as authenticated identity. Preserve existing idempotency semantics and re-check authorization before replaying protected results.
- **Planning integrity:** Keep proposed, accepted, implemented, and validated states distinct. Record approver, date, source revision, evidence and unresolved items. Do not make current OpenAPI security requirements stricter until runtime enforcement is implemented in its own change.
- **Idempotency:** Re-running a documentation chunk updates the existing canonical decision, not duplicate records/tasks. Preserve accepted choices unless explicitly superseded; do not renumber existing Phase 05 tasks to insert the proposed prerequisite.
- **Cleanup and rollback:** No file moves, generated output edits, database resets, or deployment changes. Remove only temporary artifacts created by the current session. Roll back documentation through a scoped patch/revert without discarding unrelated work.

### VII. Validation Strategy

Validation is chunk-aware. Documentation-only chunks do not need repeated application builds. Before editing, discover/read the exact targets; after each chunk review the complete diff and verify all new links/paths. For untracked files, ordinary `git diff` omits content: use `rtk proxy git diff --no-index -- /dev/null <new-file>` to retain the complete output (exit 1 means differences, not a validation error).

**Documentation checks after each writing chunk:**

```bash
rtk git status --short
rtk git diff --check
rtk git diff --stat
rtk git diff -- docs/knowledge docs/implementation-plan README.md
```

Inspect heading hierarchy, table columns, balanced code fences, accepted/pending status, approval fields, and relative links. Verify existing references with `rtk ls <referenced-path>` and label future paths as proposed. If a repository Markdown formatter is discovered, run its confirmed command on changed Markdown only; otherwise report manual formatting and whitespace validation, not a fictitious formatter pass.

**Inventory and symbol/path checks:**

```bash
rtk grep -n 'operationId:' api_collections/open_api_spec/product-catalog.yaml api_collections/open_api_spec/locations.yaml api_collections/open_api_spec/order-process.yaml
rtk grep -n 'getRoute\|get("/\|rxFrom' src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt
rtk find src/main/java/com/literp/service src/main/kotlin/com/literp/service -type f
rtk find api_collections -maxdepth 3 -type f
```

**Final evidence run (or current equivalent CI evidence tied to the same source revision):**

```bash
rtk ./gradlew test --rerun-tasks --tests com.literp.contract.OpenApiOperationIdRegistrationTest
rtk ./gradlew test --rerun-tasks --tests com.literp.repository.MasterDataRepositoryTest --tests com.literp.repository.OrderProcessRepositoryTransactionTest --tests com.literp.verticle.MasterDataHttpIntegrationTest --tests com.literp.verticle.OrderProcessHttpIntegrationTest
rtk ./gradlew build
rtk python scripts/verify_openapi_assets.py
```

Run Python only after the user-provided environment is activated. Use the isolated test DB setup from `docs/CI_VERIFICATION.md`; inspect JUnit XML/HTML reports under `build/test-results/test` / `build/reports/tests/test` after each run for executed/skipped/failure/error counts. Do not rely on `BUILD SUCCESSFUL` alone or assume the historical count of 35 is fixed. The broader build is justified at final phase closure, not every documentation chunk.

- **Contract validation:** The Python verifier proves tracked YAML/JSON and application-version consistency, not full OpenAPI schema validity. Record separate semantic validation evidence or leave the required contract-validity gate blocked pending a corrective ADS/chunk.
- **Bruno paths:** Check collection root, `bruno.json`, `.bru` request files, relative references, environments and base URL variable usage without printing secrets. This is a manual asset/path check, not execution or lint coverage.
- **Integration smoke:** No runtime smoke is required just to write decisions. For any later relocation, require actual `HttpServerVerticle` startup from the supported launch context, contract loading, utility probes, and one master-data/order/stock path on the isolated test instance in addition to build/codegen and all impacted tests. An in-test router alone is insufficient.
- **Final review:** Map each Phase 04 criterion to evidence, separate optional deferrals from required blockers, and inspect the final diff after fixes. No authentication acceptance tests run in these gates; they are requirements for the proposed 05.0 ADS.

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full feature in one pass.

Here, implementation means **decision delivery and documentation**, not auth code. Each writing chunk changes at most two Markdown files, preserves compilation by leaving executable code untouched, and delivers one reviewable decision-to-consumer path. Perform Section VII post-work after every chunk: changed-file discovery, syntax/format/reference checks, complete diff review, risk assessment, and skipped-check reporting. In single-chunk mode, stop and ask before creating a handoff or continuing; in continuous-loop mode, the coordinator requires a handoff and a fresh session per chunk.

#### Chunk 0: Discovery and Integration Confirmation

- **Goal:** Reconfirm current evidence and obtain decisions needed for the recommended ladder.
- **Files to read:** This ADS; Phase 04/05 plans; overview security paragraph; README structure; the two external handoffs when available; router registrations/loaders; three OpenAPI YAMLs; service package-info files; contract test; `TestDatabase.kt`; workflow and verifier.
- **Commands:** `rtk git status --short`; `rtk git log -1 --oneline`; inventory/path commands from Section VII; `rtk ls docs/knowledge docs/implementation-plan/ads`.
- **Evidence to confirm:** Exact route matrix, current unauthenticated surface, auth owner/timing, approver, keep-layout acceptability, test/CI limitations, and whether decision records already exist.
- **Stop condition:** Read-only evidence report and approval questions delivered. No edits. Block later acceptance/publication chunks if decisions remain unresolved.

#### Chunk 1: Contracts and Compile-Safe Stubs

- **Goal:** Establish explicit pending decision records without pretending controls exist.
- **Files to change:** Proposed `docs/knowledge/SECURITY_SEQUENCING.md`; proposed `docs/knowledge/PROJECT_STRUCTURE_DECISION.md`.
- **Symbols to add/change:** Documentation headings/status/approval fields from Section I; no executable symbols.
- **Implementation shape:** Create small record skeletons with source references, required decisions, and `Pending decision` status. Inputs are evidence and maintainer review; output is an incomplete review artifact, never a successful gate result. Reuse existing records if Chunk 0 discovers them.
- **Validation:** `rtk git diff --check`; `rtk git diff --no-index -- /dev/null docs/knowledge/SECURITY_SEQUENCING.md`; equivalent new-file diff for `PROJECT_STRUCTURE_DECISION.md`; manual Markdown/reference checks.
- **Stop condition:** Both records are readable, pending, and linked to real plan paths; no roadmap checkboxes changed.

#### Chunk 2: Security Decision and First Protected Surface

- **Goal:** Deliver one complete security decision from observed routes to accepted minimum protection requirements.
- **Files to change:** `docs/knowledge/SECURITY_SEQUENCING.md` (proposed in Chunk 1).
- **Symbols to add/change:** Decision status, owner phase, approval fields, exact endpoint/operation-ID matrix, permission/resource boundaries, follow-up acceptance criteria.
- **Implementation shape:** Evaluate before/after expansion; record the maintainer-approved choice. Recommended owner is proposed 05.0 before 05.1. Expand Section I's grouped matrix to all 31 current business operations and five utility routes, adjusting for current evidence. Require future tests for missing/invalid/expired credentials, denied permissions/resource scope, valid authorized requests, operator-route restriction and public probe access. Require existing lifecycle/idempotency behavior to remain covered.
- **Validation:** Section VII operation-ID/router searches; `rtk git diff --check`; `rtk git diff -- docs/knowledge/SECURITY_SEQUENCING.md`; manually reconcile every method/path/ID and explicit exception.
- **Stop condition:** Accepted timing/owner/matrix and bounded follow-up requirements are recorded with actual approver/date, or report blocked and stop. No auth is implemented.

#### Chunk 3: Security Decision to Roadmap Entry Gate

- **Goal:** Make accepted security sequencing actionable for the next implementation agent.
- **Files to change:** `docs/implementation-plan/00-implementation-overview.md`; `docs/implementation-plan/05-pos-manufacturing-expansion.md`.
- **Symbols to add/change:** Security sequencing paragraph, Phase 05 Entry Gate, proposed 05.0 prerequisite task and its Done when criteria.
- **Implementation shape:** Link the accepted security record, explicitly state auth precedes expansion, add owner/scope and requirement for a separate auth ADS. Preserve existing 05.1–05.6 numbering and keep future auth completion checkboxes unchecked. Reconcile only conflicting security/entry-gate statements, not unrelated overview history.
- **Validation:** `rtk grep -n '05.0\|Security\|security\|auth\|Entry Gate' docs/implementation-plan/00-implementation-overview.md docs/implementation-plan/05-pos-manufacturing-expansion.md`; `rtk git diff --check`; scoped diff for both files.
- **Stop condition:** Overview and Phase 05 agree with the accepted owner/timing; no implication that auth already exists.

#### Chunk 4: Structure Decision and Domain Placement

- **Goal:** Resolve package and asset choices in one bounded decision slice.
- **Files to change:** `docs/knowledge/PROJECT_STRUCTURE_DECISION.md` (proposed in Chunk 1).
- **Symbols to add/change:** Decision status/approval, keep-versus-move rationale, six-domain placement map, asset policy, test prerequisites and revisit triggers.
- **Implementation shape:** Record accepted layer-based layout and retained asset roots under the recommended path. Distinguish existing paths from future POS/manufacturing packages. Include codegen/path-consumer evidence and requirements for a future mechanical migration. If relocation is chosen, stop and revise this ADS; do not improvise moves.
- **Validation:** `rtk find src/main/java/com/literp/service src/main/kotlin/com/literp/service -type f`; `rtk ls api_collections/Literp api_collections/open_api_spec`; `rtk git diff --check`; scoped decision-record diff and manual placement-map review.
- **Stop condition:** Maintainer-approved layout gives all six domains clear placement and assets clear ownership, with no source or asset relocation.

#### Chunk 5: Structure Decision to Contributor Guidance

- **Goal:** Give contributors and Phase 05 implementers the same accepted placement rules.
- **Files to change:** `README.md`; `docs/implementation-plan/05-pos-manufacturing-expansion.md`.
- **Symbols to add/change:** README Project Structure/decision links and security caveat; Phase 05 layout guidance and existing structure entry checkboxes.
- **Implementation shape:** Link both canonical decisions; document retained roots and proposed new service domain groups. Update structure entry criteria only when supported by Chunk 4 approval. Keep security implementation entry criteria pending. Avoid unrelated stale README cleanup; surface discovered historical discrepancies separately unless needed for gate clarity.
- **Validation:** `rtk git diff --check`; `rtk git diff -- README.md docs/implementation-plan/05-pos-manufacturing-expansion.md`; verify relative links and six-domain placement consistency.
- **Stop condition:** README and Phase 05 agree on the layout, the auth prerequisite is preserved, and no future package is described as already implemented.

#### Chunk 6: Gate Evidence and Phase 04 Closure Review

- **Goal:** Close only demonstrated requirements and identify remaining mandatory blockers.
- **Files to change:** `docs/implementation-plan/04-quality-contracts-observability.md`; `docs/implementation-plan/00-implementation-overview.md`.
- **Symbols to add/change:** 04.5/04.6 task and Done when checkboxes, decision/evidence links, Phase 04 Definition of Done and overview status/entry-gate summary where verified.
- **Implementation shape:** Record current revision, approvals, commands/results, test counts/skips, asset path checks, CI evidence and unresolved gaps. For the retain-layout path, annotate move-only requirements as not applicable with rationale rather than claiming moves occurred. Mark the two planning gates complete when accepted/published; do not mark the entire phase complete if semantic OpenAPI validation, required CI enforcement or other required evidence remains unverified. Keep optional Bruno linting explicitly deferred if still unresolved.
- **Validation:** Run the Section VII final evidence commands or cite current equivalent CI evidence; inspect test reports; `rtk git diff --check`; complete scoped diff review. If verification is unavailable, record the blocker and leave affected closure checkboxes open.
- **Stop condition:** A per-criterion closure report separates completed gates, pending auth implementation, optional deferrals and mandatory Phase 04 follow-ups. Stop; do not begin Phase 05, migrations, or auth implementation.

### IX. Handoff to `chunked-implementation`

Recommended agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, pre-edit-discipline, safe-python-edit, and post-edit-discipline if available.
Keep executable shell commands RTK-prefixed where supported.

Task:
Deliver the decision gates for Phase 04.5 and 04.6 using
docs/implementation-plan/ads/phase-04-task-5-6.md.
This is decision/documentation delivery, not authentication implementation or source relocation.

Mode:
Execute Chunk 0 only. Do not edit files. Confirm repository evidence,
identify approval questions and validation gaps, and stop.
```

After Chunk 0 is accepted:

```text
Use the chunked-implementation skill.
Use docs/implementation-plan/ads/phase-04-task-5-6.md as the ladder.
Execute Chunk 1 only. Do not continue to Chunk 2.
After editing, run targeted validation, review complete diffs (including new files),
report risks/skipped checks, and stop. Ask before creating a handoff or proceeding.
Do not mark pending decisions accepted or implementation tasks complete.
```

Continue one accepted chunk at a time. If Python execution is needed, first obtain and activate the user's environment. Do not enter continuous-loop mode without explicit authorization; unresolved maintainer decisions block dependent chunks in either mode.

### X. Conclusion and Next Steps

Review this ADS, then execute read-only Chunk 0. The proposed outcome is security owned by a pre-expansion Phase 05 prerequisite and a retained package/API asset layout through Phase 05. Both recommendations require explicit approval.

This document neither implements authentication nor completes Phase 04.5/04.6. After accepted decision records and evidence-based gate closure, produce the separate auth implementation ADS and its own compile-safe ladder before any expansion work. If structural migration is selected instead, replace the documentation-only migration assumption with an approved mechanical-move ADS before touching source or assets.
