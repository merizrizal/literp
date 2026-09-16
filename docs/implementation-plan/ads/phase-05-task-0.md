## Architectural Design Specification: Authentication And Authorization Baseline

**Source:** [Phase 05, task 05.0](../05-pos-manufacturing-expansion.md#050-authentication-and-authorization-baseline), governed by [Security Sequencing Decision](../../knowledge/SECURITY_SEQUENCING.md).

**Status:** Maintainer-approved for Phase 05 development under the deferred-deployment exception recorded in `AUTHENTICATION_BASELINE.md`. This does not approve untrusted deployment, production release, or internet exposure.

**Evidence revision:** `6acd45b`; inspected during ADS preparation. Reconfirm at Chunk 0.

**Goal:** Protect all 31 current business operations with authenticated identity, explicit capabilities, and resource scope; restrict three operational routes while preserving two public exceptions and existing business contracts. The maintainer-approved deferred-deployment exception permits 05.1 development before genuine provider and deployment acceptance, but does not authorize untrusted deployment.

---

### I. Overview and Contract

#### Proposed baseline decisions requiring approval

1. **Identity provider:** Select the existing product **Keycloak**, using a dedicated realm and a `literp-api` audience. No provider installation was found in the inspected repository. This selects a provider product, not an already-provisioned organizational deployment. The maintainer must confirm the actual realm, issuer URL, provider operator, and onboarding process before implementation.
2. **Resource server only:** Literp accepts signed OAuth access tokens in `Authorization: Bearer …`. It does not implement login, token issuance, refresh, registration, password administration, or a general IAM database. Human clients use provider-managed authorization code with PKCE; trusted service clients use provider-managed client credentials. No password grant.
3. **Verification:** RS256 only, using an operator-provisioned, read-only public JWKS file exported from the selected realm. Exact issuer and API audience validation are mandatory. This initial baseline deliberately avoids runtime discovery/JWKS network fetching. Never trust `jku`, `x5u`, embedded token keys, or token-selected endpoints.
4. **Organization:** One organization per Literp deployment/database. Every token must carry a provider-administered organization claim matching deployment configuration. Existing master data is organization-wide; location grants constrain orders and stock, not catalog or location administration. This is not multi-tenant row isolation. Shared-database multi-organization use blocks this design and requires a separate approved schema design.
5. **Permissions:** Provider-administered capability and location arrays, not automatic role expansion. Unknown capabilities confer no authority; no wildcard locations, implicit administrator bypass, or capability inheritance.
6. **Operations:** `/metrics`, `/health/ready`, and `/health/db` require an authenticated operator/service principal with proposed `operations.read`. Do not implement a network-header bypass. `/` and `/health/live` remain explicit GET-only public exceptions.
7. **Release:** Intermediate slices are compile-safe development work, not separately deployable security baselines. No auth-disable fallback; no partially protected release. Existing network isolation remains mandatory until final acceptance.

#### Principal and credential contract (proposed)

| Field | Source and validation | Meaning |
|---|---|---|
| issuer | Verified `iss`, exact configured HTTPS realm URL | Identity namespace |
| subject | Required nonblank `sub`, bounded to 255 characters | Stable actor within the deployment's single issuer; never a display name |
| organizationId | Required string `literp_org`, exact configured organization | Deployment boundary |
| capabilities | `literp_permissions`, array of bounded strings | Exact capabilities from the accepted operation matrix; absent means empty; wrong type rejects token |
| locationIds | `literp_locations`, array of canonical UUID strings | Explicit authorized locations; absent means empty; malformed entries reject token |
| principalKind | `literp_kind`, exactly `human` or `service` | Provider-administered identity category |
| operator | `literp_operator`, boolean; absent means false | Additional eligibility for operational access; wrong type rejects token |
| expiresAt | Required numeric `exp` | Access-token expiry |

Require `iat`, `exp`, and correct `aud` (string or string array containing the configured audience). Validate `nbf` when present. Proposed maximum token lifetime is five minutes, with at most 30 seconds clock skew; reject future issuance beyond skew and `exp <= iat`. Preserve `typ=Bearer` for the deterministic development fixture, accept standard Keycloak access tokens with `typ=JWT` only when a nonblank `scope` claim is present, and accept RFC 9068 access tokens with `typ=at+jwt`; verify the provider profile at Chunk 0. Provider mappers must keep the Literp claims and API audience on access tokens only so ID tokens fail even if otherwise cryptographically valid. Require a bounded, known `kid`; reject `none`, HS256, algorithm confusion, duplicate/ambiguous authorization headers, oversized tokens, and malformed claim types. Proposed header/token limit: 16 KiB; location grant limit: 256. These limits require approval against expected deployments.

Token claims must be mapped by provider administrators and not writable through user-editable profile attributes. A valid token with the wrong organization is authenticated but unauthorized (403). Invalid identity/credential claims yield 401. Missing permission/location arrays yield no grants, not unrestricted access.

**Function Signature Contract (Concrete):**

- `Config()` currently requires HTTP and database configuration; it does not configure identity.
- `HttpServerVerticle.start(startFuture: Promise<Void>?)` creates the DB pool and registers services; `registerProductCatalogHandlers`, `registerLocationHandlers`, and `registerOrderProcessHandlers` attach business handlers.
- `OrderProcessService.listSalesOrders(int page, int size, String sort, String status, String salesChannel, String locationId): Future<JsonObject>` currently permits unrestricted listing.
- `OrderProcessService.fulfillSalesOrder(String salesOrderId, String createdBy, String notes, String idempotencyKey): Future<JsonObject>` forwards caller attribution today.
- `ErrorCodes.fromStatus(statusCode: Int): String` currently has no 401/403 cases.

**Function Signature Contract (Conceptual):** All following names and new paths are proposed, not existing symbols. Confirm Vert.x 5.1.8 API signatures before coding.

| Proposed symbol | Input → output | Initial safe stub |
|---|---|---|
| `AuthenticatedPrincipal` | Validated immutable identity, grants, and expiry → request-local value | Data contract only; never construct from unverified JWT claims |
| `CredentialVerifier.verify` | Raw bearer credential → asynchronous principal or typed authentication failure | Explicit failed result; never anonymous or successful placeholder |
| `SecurityPolicy.require` | Principal + operation ID → allow/deny requirement | Deny every operation until explicit policy exists |
| `SecurityHandler.authenticate` | Routing context → verified principal stored in context or terminal failure | Fail closed; no `next()` on failure |
| `SecurityHandler.authorizeOperation` | Context + operation ID → capability/org decision and continuation | Deny unless explicit mapped policy succeeds |
| `OrderScopeRepository.findLocation` | Order ID → asynchronous location-only result/not-found | Failed result, not null-as-allow; no order payload loading |
| `OrderScopeRepository.listAuthorizedOrders` | Existing list filters + nonempty authorized location set → existing list envelope | Explicit unavailable error; never delegate to unrestricted listing |
| `OrderScopeHandler.authorize` | Verified principal + operation + validated resource input → continuation or terminal denial | Deny; temporary failure must be handled by the router |

New cross-cutting files are proposed under `src/main/kotlin/com/literp/security/`; SQL stays under `repository/`, HTTP adapters under `verticle/handler/`. The new security package is a concrete cross-cutting capability, not a domain-first layout migration.

### II. Observed Evidence and Assumptions

#### Observed evidence

| File/section | Evidence and design consequence |
|---|---|
| `docs/implementation-plan/05-pos-manufacturing-expansion.md`, Entry Gate and 05.0 | Requires approved ADS, all current operations protected, negative/positive validation, and preserved lifecycle/idempotency contracts before expansion. |
| `docs/knowledge/SECURITY_SEQUENCING.md`, First Protected Surface and Utility-Route Policy | Authoritative 31-operation capability matrix and five utility policies; provider and scope implementation were intentionally unresolved. |
| `docs/knowledge/PROJECT_STRUCTURE_DECISION.md`, Accepted Structure and API Asset Policy | Preserve layer-based source layout and current OpenAPI/Bruno roots. |
| `build.gradle.kts:1–69` | Kotlin 2.4.20, Java 25, Vert.x 5.1.8, RxJava 3, JUnit 5; no explicit JWT dependency or formatter plugin in inspected build. |
| `src/main/kotlin/com/literp/config/Config.kt:16–88` | Environment-first resolution with local `cfg.properties` fallback; missing required values fail construction. |
| `src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt:75–174,182–225` | Runtime OpenAPI loading, three subrouters, 31 explicit `getRoute` registrations, no auth gate in that flow. |
| Same file, `getIndex`, `getLiveness`, `getDatabaseHealth`, `handleFailure`, `putResponse` | Five utility endpoints; error envelope and request-ID response path already exist. Preserve operational health DOWN/503 semantics for authorized callers. |
| `src/main/kotlin/com/literp/common/ErrorCodes.kt` and `verticle/handler/BaseHandler.kt`, response helpers | Errors use `error`, `errorCode`, `status`, `errorId`; request ID is a response header. New 401/403 must not fall through to `INTERNAL_ERROR`. |
| `verticle/handler/OrderProcessHandler.kt`, list/create/stock/fulfill functions | Optional list location filter, client-supplied create/stock location, fulfillment `createdBy` from body. |
| `repository/OrderProcessRepository.kt:17–173,563–650` | Unscoped list count/data queries; get loads nested lines/reservations/payments; fulfillment fingerprint includes actor and notes, then reads idempotency before lifecycle processing. Authorization must precede entry into this path, including replay. |
| Same repository, SQL location references and UPDATE statements | Inspected HTTP-backed order operations insert a location at creation but do not reassign it. This is an explicit scope-check stability assumption, not a database constraint. |
| `src/main/java/com/literp/service/order/OrderProcessService.java` | Generated service-proxy boundary has no principal parameter. Do not serialize a Kotlin principal into the event bus accidentally. |
| `python/database/migration/alembic/versions/b8f4c1d9a2e7_03_order_command_contracts.py:44–88` | Idempotency uniqueness is order + command + key; event actor storage is nullable string(255). Do not silently rescope keys per token. |
| Initial migration location/order definitions; `docs/knowledge/MODEL_DESIGN.md`, Tenancy note | Location-based schema; model notes defer tenant IDs. No organization row scope was found in inspected migration definitions. |
| `src/test/kotlin/com/literp/contract/OpenApiOperationIdRegistrationTest.kt` | Current parity test scans literal `getRoute` calls; preserve those calls or deliberately update the detector in the same wiring slice. |
| `src/test/kotlin/com/literp/verticle/MasterDataHttpIntegrationTest.kt`, production router test | Deploys real verticle and expects port 8010; most HTTP fixture tests construct separate routers. Security acceptance must exercise the production router, not only replicas. |
| `.github/workflows/foundation-verification.yml`, Test Baseline; `src/test/kotlin/com/literp/test/TestDatabase.kt` | Runtime and test DB configuration are separate. CI authentication fixtures must not depend on ignored local files; skipped DB tests cannot establish security acceptance. |
| `api_collections/open_api_spec/{product-catalog,locations,order-process}.yaml`, security/operation sections | Catalog/location declare bearer schemes; declaration is not enforcement. Order contract and all runtime policies need synchronized authenticated requirements. |
| `api_collections/Literp/collection.bru` | Collection variables exist; no collection authentication block in inspected content. |

Discovery used targeted `rtk ls`, `rtk find`, and `rtk grep` searches followed by file/section reads. No `CONTEXT.md` or `docs/adr/` artifacts were found in the bounded discovery; accepted knowledge decisions above are the applicable references.

#### Approval blockers / Chunk 0 confirmations

- Confirm Keycloak adoption or substitute the organization's actual existing provider and revise the token profile before approval. Supply non-secret issuer/audience/organization identifiers, not credentials in this document.
- Approve one organization per database and organization-wide master-data permissions. If location visibility itself must be restricted, revise the accepted matrix/design first.
- Confirm order location cannot change through any concurrent writer, import, integration, or administrative process during API service. If this invariant cannot be enforced operationally, stop: scope must instead be checked inside the same DB transaction/lock as each command, requiring revised service/repository contracts and an expanded chunk ladder.
- Confirm claim administration, actor subject length, token expiry/revocation tolerance, client flows, key-rollover ownership, TLS topology, and audit retention/access ownership.
- Verify Vert.x JWT public-JWKS loading, RS256 pinning, claim validation, and RouterBuilder handler ordering with a compile/test spike before committing exact APIs to implementation. Validation of request bodies may precede operation scope checks, but authentication/capability denial must precede protected business execution.
- Confirm bounded filters/sorting and scoped-list pagination retain the existing envelope; no post-pagination filtering.
- Phase 04 closure/structure gates remain separately owned. Do not mark external CI enforcement or request-correlated repository logging complete because this ADS exists.

### III. Required Technical Dependencies and Imports

- **Proposed:** `io.vertx:vertx-auth-jwt:$vertxVersion`, aligned with the existing version. Verify supported JWK/JWKS API and transitive dependencies; use library signature verification, not custom cryptography.
- Existing Vert.x `Future`, RxJava `Single`, `RoutingContext`, `JsonObject`, PostgreSQL pool/prepared queries, and JUnit are sufficient for orchestration and scope checks. Concrete import lists depend on the verified core/Rx adapter API.
- **Proposed `SecurityConfig`:** separate fail-fast security configuration, loaded once before accepting HTTP. Required environment names: `LITERP_AUTH_ISSUER`, `LITERP_AUTH_AUDIENCE`, `LITERP_AUTH_ORGANIZATION_ID`, `LITERP_AUTH_JWKS_PATH`. No development defaults or production bypass switch. Read a bounded public-key file; reject missing, malformed, duplicate-kid, private-key, or unsupported-key content before serving protected traffic.
- Keep HTTP/database configuration intact. CI must provide runtime `LITERP_PG_*`, test `LITERP_TEST_PG_*`, and security fixture configuration explicitly; use isolated test issuer/keys, never real provider signing material.
- Runtime application stores only public keys. Actual Keycloak hosting and client administration are provider/operator responsibilities documented in the runbook, not new IAM endpoints in Literp.
- No schema migration is proposed, conditional on single-org and immutable-order-location approval. No new Java proxy module/package or generated-source edits.

### IV. Step-by-Step Procedure / Execution Flow

#### Startup and credentials

1. Validate security settings and approved public keys before HTTP listen; build one immutable verifier/policy instance per deployment. Invalid setup fails startup, never switches to anonymous access.
2. Establish request ID and request metrics before security decisions; retain existing header behavior and use bounded/escaped values in logs.
3. Match only the two explicit public GET routes as exceptions. Every business path and restricted utility authenticates first. Unknown paths/methods never fall through into an unguarded business handler.
4. Parse exactly one bounded Bearer credential. Verify signature/algorithm/key ID, issuer, audience, access-token profile, times, and claim types before constructing the principal. Never accept identity from query strings, cookies, proxy headers, `createdBy`, customer IDs, or submitted location IDs.
5. Check configured organization and exact operation capability. Policy is keyed by the 31 approved operation IDs; missing registration/policy is a startup/test parity failure and runtime denial, not permission inference from method/path text.
6. Perform location/resource checks below, then call the unchanged business operation. Capture the allow/deny decision before any command or idempotency lookup.

#### Capability matrix

The exact 31-row matrix in [Security Sequencing Decision](../../knowledge/SECURITY_SEQUENCING.md#first-protected-surface) is normative and must be represented explicitly in code/tests. Required counts: 21 master-data operations, eight order operations, two stock operations. Use exact `master-data.read`, `master-data.write`, `order.read`, `order.write`, `order.confirm`, `payment.capture`, `order.fulfill`, `order.cancel`, and `inventory.read`; do not collapse them into a write/admin role. Parity must assert equality among OpenAPI IDs, registered handlers, and policies, not counts alone.

#### Resource rules

| Operation group | Required resource check before business execution |
|---|---|
| 21 catalog/UOM/variant/location operations | Matching organization and exact master-data capability; resources are organization-wide by explicit design. Location grants do not turn a read into write authority. |
| `createSalesOrderDraft` | Validated body `locationId` must be in verified grants; customer ID remains business data, never identity. |
| `listSalesOrders` | Apply grants in SQL to BOTH count and data queries. Optional requested location must belong to grants and narrows the query. Omitted filter means all granted locations, never all database locations. Empty grants return 403. |
| `getSalesOrder`, add line, confirm, payment, fulfill, cancel | Read only order ID/location from trusted DB, require granted persisted location, then call business service. Missing and out-of-scope IDs both return 404 `RESOURCE_NOT_FOUND` to avoid existence disclosure. Never load nested order/payment data before scope succeeds. |
| `getCurrentStock`, `getAvailableStock` | Validated query `locationId` must be granted; product remains organization-wide. Missing/invalid required inputs retain 400 after authentication/capability checks. |
| `/metrics`, `/health/ready`, `/health/db` | Matching organization + `operations.read` + (`operator=true` OR `principalKind=service`). No business permission is implied. |

Implement scoped listing in the proposed `OrderScopeRepository`, retaining the existing filters, sorting and pagination shape with prepared location parameters. The HTTP scoped-list path must not call the legacy unrestricted list service. Keep that service internal/trusted; test that production HTTP cannot reach it. Avoid duplicating broader order business logic in the scope repository.

The lightweight preflight lookup is safe only under the approved immutable-location and trusted-internal-writer boundary. It does not claim to solve mutable-resource TOCTOU. If location reassignment is added later, authorization must move into command transactions before idempotency reads, with lock/conditional SQL and principal context propagation. This is a mandatory review trigger, not an optional optimization.

#### Actor, idempotency, and response behavior

- On HTTP fulfillment, ignore legacy body `createdBy` and pass verified `sub` to the existing service parameter. Document it as deprecated/ignored. Provider issuer is fixed for this database; do not silently rebind the issuer to another identity namespace.
- Existing fulfillment fingerprint includes actor and notes. Same actor + same key + same semantic request retains replay behavior. A different actor is not allowed to impersonate the original actor to obtain replay; actor-sensitive fingerprint conflict is preserved. Other command keys retain current order/command/key semantics.
- Reauthorize every retry before any stored response is returned; revoked/expired/out-of-scope callers do not gain replay access. No new idempotency record or event is written on denial.
- Preserve business status transitions, transaction boundaries, inventory movement semantics, payment conflict handling, envelopes, request IDs, and existing 400/404/409/503 meanings.
- Add proposed `UNAUTHENTICATED` for 401 and `FORBIDDEN` for 403 to `ErrorCodes.fromStatus`. Return the existing four-field error envelope and `X-Request-ID`. 401 includes `WWW-Authenticate: Bearer`; do not expose token/claim/key details. Route typed security failures through safe fixed messages, not raw verifier exception text.
- Unmatched authenticated routes retain 404. Unauthenticated unknown protected paths may return 401 before route discovery. Unsupported methods on public route names are not new public exceptions.

### V. Failure Modes and Resilience

| Stage | Failure Mode | Agent/System Action | Next State/Error Report |
|---|---|---|---|
| Startup | Missing issuer/audience/org/key file; invalid keys | Fail before serving HTTP; sanitized configuration diagnostic | Deployment not ready; no auth bypass |
| Credential parse | Missing, duplicate, malformed, oversized bearer | Stop before scope query/business handler | 401 `UNAUTHENTICATED` (proposed), request ID and challenge |
| Verification | Unknown kid, forged signature, wrong alg/issuer/audience, ID token, expired/future token | Reject; do not fetch token-directed URLs | 401, sanitized reason in restricted security log |
| Claim normalization | Invalid type/UUID/length/token lifetime | Reject principal construction | 401; no DB side effects |
| Authorization | Wrong organization, absent capability, empty location grants, forbidden explicit location | Stop before mutation | 403 `FORBIDDEN` (proposed) |
| Order scope | Nonexistent or ungranted order | Use identical outward response; no nested data fetch | 404 `RESOURCE_NOT_FOUND` |
| Scope storage | Query timeout or database failure | Fail closed; no service dispatch; map typed timeout/internal failures | Existing 503 `DB_TIMEOUT` for timeout; sanitized 500 `INTERNAL_ERROR` otherwise |
| Policy coverage | Missing policy for new operation | Block startup/validation; runtime deny if reached | No implicit access; policy configuration failure |
| Key rotation | New key not provisioned on all instances | Unknown kid rejected; operators reconcile key rollout | 401 for affected tokens; no insecure fallback |
| Provider outage | Issuance unavailable | Previously issued valid tokens remain usable until expiry against provisioned keys | No runtime provider dependency; new login/token renewal unavailable |
| Replay | Credentials/grants no longer valid | Authorize again before stored response lookup | Denied without idempotency/event mutation |
| Business execution | Existing validation/conflict/DB failure | Preserve business rollback and error contracts | Existing error response, request ID preserved |

### VI. Security, Integrity, Idempotency, and Cleanup

- **Deployment trust:** TLS terminates at an approved reverse proxy or application listener. If proxy termination is used, backend access is private/firewalled to that proxy; bearer tokens must not traverse an untrusted plaintext hop. Forwarded identity or source-IP headers never authorize. Event bus/services/repositories remain private in-process trusted boundaries; no public bridge or untrusted plugin access. If that boundary changes, propagate and enforce principal context at services first.
- **Credential lifecycle:** Provider owns user/service enrollment, client secrets, MFA policy, disablement and refresh revocation. API accepts only access tokens, not refresh tokens. Disablement/permission changes take effect no later than remaining token lifetime plus skew (proposed maximum 330 seconds); immediate per-user revocation is not promised. If immediate revocation is required, revise the design for introspection or a bounded revocation mechanism before implementation.
- **Key rotation:** Provision old+new public keys on every instance, restart/roll them, then begin signing with the new key. After all old tokens expire plus skew, remove old keys and roll again. Emergency key compromise requires removing the key and restarting all instances; temporary outage is preferable to accepting a compromised key. Keep private keys solely at provider; never commit real keys or tokens.
- **Audit:** Structured security decision events include request ID, operation ID, issuer identity label, verified subject when available, organization, decision, fixed reason code, and status. No Authorization headers, JWTs, customer/payment bodies, raw keys, or unbounded attacker strings. An authorization allow event is not a transaction-success audit. Existing order events remain authoritative for business changes; fulfillment movement actor becomes authenticated. Adding actors to every historical event/proxy is outside this minimal baseline and must not be claimed complete.
- **Audit ownership:** Maintainer must name log sink, retention period and access owner at approval. Security log/request ID work does not silently close Phase 04 repository correlation work.
- **Integrity:** No mutation or idempotency allocation before authorization. Scoped counts and pages use identical location restrictions. No wildcard, omitted-filter, unknown-operation, alias-path, or caller-supplied actor bypass.
- **Cleanup:** Verifier resources close with the verticle if the chosen library allocates them. Test keys/servers/temporary configuration use isolated temporary directories and teardown; do not rename or overwrite developer `cfg.properties` in permanent tests. Never weaken production config for fixture convenience.
- **Rollback:** Keep deployment isolated or take it offline when reverting authentication. Never restore unauthenticated public availability as a recovery measure.

### VII. Validation Strategy

All commands below are proposed implementation checks, not evidence that this ADS has implemented authentication. Re-run affected checks after each chunk; final completion requires zero skipped security/DB integration tests.

- **Compile/syntax:** `rtk ./gradlew compileKotlin compileJava compileTestKotlin`. Generated Java proxies must still compile; never edit generated output.
- **Formatting:** No repository Kotlin formatter was identified in the inspected build. Match existing formatting and run `rtk git diff --check`; confirm repository formatter availability at Chunk 0 rather than introducing one implicitly. Parse changed workflow/YAML with `rtk yq '.' <file>`; no Markdown formatter dependency is proposed.
- **Symbols:** `rtk grep -Rni 'AuthenticatedPrincipal\|SecurityHandler\|OrderScopeRepository' src/main src/test` after corresponding symbols exist; compiler is authoritative for exact API compatibility.
- **Narrow tests:** Proposed `rtk ./gradlew test --tests 'com.literp.security.*' --rerun-tasks`; scope tests under repository package; production-router security tests under verticle package. Chunk entries identify expected groups.
- **Credential matrix:** Missing/malformed/duplicate header, bad signature, unknown kid, HS256/none, wrong issuer/audience, ID token, expired/not-yet-valid/future-issued token, excessive lifetime, invalid claims, wrong organization, empty grants, and token size bounds. Validate overlapping rotated keys and removed-key rejection using ephemeral test key pairs.
- **Route matrix:** For each of all 31 operations: authorized success fixture, no credential 401, insufficient capability 403, and applicable scope denial. Check no business invocation for missing/invalid/expired credentials. Use real production-router HTTP tests and assert effects in DB, not only response status.
- **Scope:** Two locations, allowed/unallowed orders, multi-location list without explicit filter, narrowed allowed filter, forbidden filter, pagination totals, empty grants, nonexistent IDs, malformed IDs, forged location/customer/actor values, and empty scope. Assert no out-of-scope nested data or list counts leak.
- **Side effects:** Compare order/payment/reservation/movement/event/idempotency state before/after denial. Include replays after scope loss and token expiry, same-actor idempotent fulfillment, different-actor fingerprint behavior, and spoofed `createdBy` ignored in stored movement actor.
- **Utilities:** Both public GET routes return minimal current responses without credentials. Three operational endpoints reject anonymous/business-only identities and allow properly granted operators/services; authorized DB-down responses retain 503 contract. Test methods/paths/trailing-slash variants do not create bypasses.
- **Parity:** Extend `OpenApiOperationIdRegistrationTest` to compare explicit policy membership and the exact operation sets. New routes without approved policy must fail the gate.
- **Regression:** `rtk ./gradlew test --rerun-tasks` and `rtk ./gradlew build`; retain repository lifecycle/transaction tests and authenticate HTTP fixtures. A successful build with skipped DB tests is not acceptance.
- **Assets:** Run `rtk python scripts/verify_openapi_assets.py` only after the user provides/approves the Python virtual environment and it is activated. Preserve YAML/JSON parity and selected API version consistently; add 401/403 plus conditional scoped 404 documentation. Exercise Bruno auth inheritance and no-auth public probes without committing tokens.
- **CI reproduction:** Clean checkout, no `cfg.properties`, explicit test/runtime/security environment, isolated migrated PostgreSQL, generated test signing keys. Run real-router matrix and full baseline; archive sanitized test reports on failure. No live Keycloak service is required for deterministic automated JWT tests.
- **Provider smoke:** Separately obtain genuine non-production Keycloak human/service access tokens through approved client flows. Verify API acceptance, ID-token rejection, claim mapper restrictions, audience, logout/expiry behavior, and key rollover. This cannot be replaced by self-signed test fixtures alone.
- **Final review:** `rtk git status --short`, `rtk git diff --check`, `rtk git diff -- <changed-files>`; explicitly inspect newly created/untracked files too. Check credentials, bypasses, generated artifacts, and unrelated changes.

### VIII. Thin Vertical Slice Chunk Design

The implementation must proceed through `chunked-implementation`. Do not implement the full feature in one pass.

All new names/paths below are proposed. Paths prefixed `main/` mean `src/main/kotlin/com/literp/`; `test/` means `src/test/kotlin/com/literp/`. Each chunk requires compile, targeted tests, diff review, risk report and a stop. Prefer one or two files; explicit larger exceptions below connect one narrow production behavior or synchronized asset contract. If a listed chunk cannot remain reviewable, approve numbered subchunks before editing rather than performing a giant pass. No slice is independently approved for untrusted deployment.

#### Chunk 0: Discovery and Integration Confirmation
- **Goal:** Resolve approval blockers and confirm framework ordering/config/test seams.
- **Files to read:** This ADS; source plan; security/layout decisions; `build.gradle.kts`; `main/config/Config.kt`; `main/verticle/HttpServerVerticle.kt`; order handler/repository/service and HTTP test support; workflow; current API contracts.
- **Commands:** `rtk git status --short`; `rtk grep -n 'getRoute\|createRouter\|handleFailure' src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt`; `rtk ./gradlew compileKotlin compileJava`.
- **Evidence to confirm:** Actual provider profile/config ownership, single-org and immutable-location assumptions, safe JWT library API, request processing order, test isolation, no unintended worktree changes. Any spike requiring file edits needs separate authorization.
- **Stop condition:** Report evidence and approval questions only. Do not edit files or proceed while product/security decisions are unresolved.

#### Chunk 1: Contracts and Compile-Safe Stubs
- **Goal:** Define verified identity and deny-only policy contracts without a false success path.
- **Files to change:** Proposed `main/security/SecurityPolicy.kt`, `test/security/SecurityPolicyTest.kt`.
- **Symbols to add/change:** Conceptual `AuthenticatedPrincipal`, `CredentialVerifier`, typed failures and `SecurityPolicy.require` in one minimal module.
- **Implementation shape:** Immutable principal; failed verifier stub; all-deny policy. Tests prove unknown/missing policy cannot allow. No callers wired yet; existing isolated application behavior remains unchanged.
- **Validation:** Compile commands in VII; `rtk ./gradlew test --tests 'com.literp.security.SecurityPolicyTest' --rerun-tasks`.
- **Stop condition:** Contracts compile, deny-only tests pass; no token is accepted by a stub.

#### Chunk 2: Verified Access Token to Principal
- **Goal:** Complete one credential verification path including rejection cases, independent of HTTP wiring.
- **Files to change:** `build.gradle.kts`; proposed `main/security/JwtCredentialVerifier.kt` (including tightly scoped `SecurityConfig`); `test/security/JwtCredentialVerifierTest.kt`. Three-file exception is needed for dependency + implementation + verification.
- **Symbols to add/change:** Conceptual `SecurityConfig`, `JwtCredentialVerifier.verify`, claim normalization; implement Chunk 1 interface.
- **Implementation shape:** Load configured public JWKS, verify through pinned library, enforce token profile and normalize grants; local generated-key tests exercise actual signature verifier. No real secrets or production bypass.
- **Validation:** Compile; `rtk ./gradlew test --tests 'com.literp.security.JwtCredentialVerifierTest' --rerun-tasks`; verify valid/expired/forged/profile/rotation tests.
- **Stop condition:** Deterministic token verification/rejection passes; no unverified principal escapes.

#### Chunk 3: Explicit Operation Capability Decisions
- **Goal:** Replace deny-only policy with the approved operation/capability and utility matrix.
- **Files to change:** `main/security/SecurityPolicy.kt`, `test/security/SecurityPolicyTest.kt`.
- **Symbols to add/change:** Conceptual `SecurityPolicy.require`, utility eligibility and organization checks.
- **Implementation shape:** Explicit 31 operation entries and five utilities; no inferred wildcard permissions. Scope-required operations return a requirement, not final authorization. Tests cover wrong organization, missing capabilities, operator/service distinctions and unknown operations.
- **Validation:** `rtk ./gradlew test --tests 'com.literp.security.SecurityPolicyTest' --rerun-tasks`; compile and diff review.
- **Stop condition:** Exact capability decisions pass; resource-required results cannot be confused with final allow.

#### Chunk 4: Database-Backed Order Visibility
- **Goal:** Implement read-only persisted scope lookup and SQL-scoped list/count behavior.
- **Files to change:** Proposed `main/repository/OrderScopeRepository.kt`, `test/repository/OrderScopeRepositoryTest.kt`.
- **Symbols to add/change:** Conceptual `findLocation`, `listAuthorizedOrders`.
- **Implementation shape:** Minimal location-only lookup; prepared authorized-location predicates in both count/data queries, existing pagination/filter/sort contract, reject empty grants. DB tests seed two scopes. No changes to transaction/idempotency business methods.
- **Validation:** Compile; `rtk ./gradlew test --tests 'com.literp.repository.OrderScopeRepositoryTest' --rerun-tasks` against migrated test DB, zero skips.
- **Stop condition:** No out-of-scope rows/counts returned; unscoped fallback absent; immutable-location approval is recorded.

#### Chunk 5: Request Guards and Authenticated Actor Adapter
- **Goal:** Connect principal/policy/scope to HTTP continuations without production activation yet.
- **Files to change:** Proposed `main/verticle/handler/SecurityHandler.kt`, `main/verticle/handler/OrderScopeHandler.kt`, `test/verticle/SecurityHandlerTest.kt`. Three-file exception joins generic auth and domain scope in one testable request path.
- **Symbols to add/change:** Conceptual `authenticate`, `authorizeOperation`, `OrderScopeHandler.authorize`; private safe denial responses matching existing envelope.
- **Implementation shape:** Authenticate first; capability/org first; scoped list terminates with scoped repository response; other scoped operations authorize before continuation. Resource stubs deny. Prepare fulfillment adapter to replace legacy actor with verified subject before existing business dispatch; verify validated-body handling rather than assuming raw-body edits affect it. Do not register handlers in production until complete.
- **Validation:** Compile; `rtk ./gradlew test --tests 'com.literp.verticle.SecurityHandlerTest' --rerun-tasks`; assert no continuation on denial and no unrestricted list delegation.
- **Stop condition:** Request adapter tests pass for capability and resource denial, scoped list and actor derivation; production activation remains pending.

#### Chunk 6: Complete Production-Router Enforcement
- **Goal:** Activate security across all business/operational routes in a single inseparable wiring slice.
- **Files to change:** `main/verticle/HttpServerVerticle.kt`, `main/common/ErrorCodes.kt`, `main/verticle/handler/OrderProcessHandler.kt` if actor dispatch needs an explicit method seam; proposed `test/verticle/AuthenticationHttpIntegrationTest.kt`; existing `test/verticle/MasterDataHttpIntegrationTest.kt`, `test/test/HttpTestSupport.kt`, and `test/contract/OpenApiOperationIdRegistrationTest.kt` as necessary. This larger exception is required to avoid partially wired protection and to keep the existing real-router test passing; do not add unrelated refactors.
- **Symbols to add/change:** Existing `start`, route registration functions, `handleFailure`, `ErrorCodes.fromStatus`, fulfillment actor dispatch seam; proposed security HTTP fixtures and three-way policy parity check.
- **Implementation shape:** Initialize verified config before listen; capture request ID before rejection; wire global authentication then explicit operation guards; scoped list never reaches unscoped handler. Preserve literal route registrations where possible. Public probes are explicit exceptions; all other known utility routes restricted. Add 401/403 mappings and sanitized failures. Tests generate temporary public/private fixture keys with private portion confined to tests; no production trust bypass.
- **Validation:** Compile; `rtk ./gradlew test --tests 'com.literp.verticle.AuthenticationHttpIntegrationTest' --tests 'com.literp.verticle.MasterDataHttpIntegrationTest' --tests 'com.literp.contract.OpenApiOperationIdRegistrationTest' --rerun-tasks`.
- **Stop condition:** Real router protects all 31 operations and restricted utilities; parity test proves exact membership; existing router contracts pass with explicit security fixtures. Still no release until final matrix/provider/CI acceptance.

#### Chunk 7: Authorization Regression and Clean-CI Evidence
- **Goal:** Lock down side effects, replay, actor attribution and CI-independent configuration.
- **Files to change:** Proposed security integration test from Chunk 6; existing `test/verticle/OrderProcessHttpIntegrationTest.kt` and shared HTTP fixture support as required; `.github/workflows/foundation-verification.yml`. Cross-file exception is for the same authenticated regression path and clean CI execution; split fixture/CI work into approved subchunks if necessary.
- **Symbols to add/change:** Security matrix cases, authorized HTTP fixtures, workflow test configuration/report publication. Do not rewrite repository business tests merely to force a green build.
- **Implementation shape:** 31 authorized cases plus credential/capability/scope denials; DB before/after assertions; scoped pages/counts, operator/public routes, actor spoof/replay cases. Run without ignored local config. Test-generated keys, no live provider dependency, no skipped integration acceptance.
- **Validation:** Target security/order HTTP tests, then `rtk ./gradlew test --rerun-tasks` and `rtk ./gradlew build`; `rtk yq '.' .github/workflows/foundation-verification.yml`; inspect JUnit counts/skips and sanitized CI reports.
- **Stop condition:** Full suite and clean-CI matrix pass with zero skipped security/DB tests; all failure modes have explicit evidence or blockers.

#### Chunk 8: Published Contracts and Deployment Acceptance
- **Goal:** Publish only the runtime-proven security contract and verify real-provider/deployment readiness.
- **Files to change:** Existing three OpenAPI YAML/JSON pairs and supporting READMEs under `api_collections/open_api_spec/`; `api_collections/Literp/collection.bru` and affected request auth settings; `README.md`; proposed `docs/knowledge/AUTHENTICATION_BASELINE.md`; source plan checkboxes only after maintainer acceptance, including any documented exception. Asset-count exception is required for synchronized contract copies, not permission to make one large patch: use paired-bundle substeps and review each diff.
- **Symbols to add/change:** Bearer security requirements, 401/403/scoped-404 responses, deprecated ignored `createdBy` description, Bruno secret variables/auth inheritance, operator/public probe examples, provider/rotation/revocation runbook and acceptance evidence.
- **Implementation shape:** Describe implemented behavior, not future bearer placeholders; no saved credentials. Retain approved asset locations, schemas and response contracts. Run a genuine provider token/rotation smoke and record non-secret results and maintainer approval. If provider/deployment access is unavailable, keep those evidence items explicitly pending; do not represent local evidence as deployment acceptance.
- **Validation:** Approved-venv `rtk python scripts/verify_openapi_assets.py`; compile and full Gradle regression after contract changes; authenticated Bruno smoke; provider smoke from VII; `rtk git diff --check` and final per-file diff review.
- **Stop condition:** Normal completion requires runtime, assets, CI, provider, and deployment evidence to agree. The documented maintainer-approved deferred-deployment exception permits 05.1 development before that normal completion; it does not authorize untrusted deployment and does not satisfy the separate Phase 04 entry gate.

### IX. Handoff to `chunked-implementation`

Recommended agent prompt:

```text
Use the chunked-implementation skill.
Use pre-read-discipline, safe-python-edit, and post-edit-discipline if available.

Task:
Phase 05 task 05.0 Authentication And Authorization Baseline.
Read docs/implementation-plan/ads/phase-05-task-0.md and its source decisions.

Mode:
Execute Chunk 0 only. Do not edit files. Confirm repository evidence and stop.
Report unresolved provider, organization, immutable-location, credential lifecycle,
framework API/order, and deployment/audit approval questions. Do not assume approval.
```

After Chunk 0 and this ADS are accepted:

```text
Use the chunked-implementation skill.
Execute Chunk 1 only from docs/implementation-plan/ads/phase-05-task-0.md.
Do not continue to Chunk 2. Keep stubs fail-closed and runtime isolated.
After editing, run targeted validation, show git diff, inspect new files,
and report security/integration risks. Ask whether to create a handoff or
proceed before doing either in single-chunk mode.
```

For every later chunk, reconfirm the preceding chunk's report and blockers. Do not enter continuous-loop mode without explicit authorization. In authorized continuous-loop mode, one session executes one chunk, validates, writes its handoff and stops; the coordinator starts the next session. No agent may mark provider smoke, external deployment controls, or approval complete based on local unit tests.

### X. Conclusion and Next Steps

This is an implemented local authentication and authorization baseline. It uses Keycloak-based bearer verification, explicit capabilities, a single-organization deployment boundary, location-scoped order/stock access, authenticated fulfillment attribution, and identity-restricted operational endpoints without broad IAM or tenancy redesign.

**Next step:** The maintainer-approved deferred-deployment exception permits 05.1 development. Complete the pending Bruno, genuine-provider, timed-rotation, deployment/network, and audit-ownership acceptance before untrusted deployment. The separate Phase 04 entry gate remains required.
