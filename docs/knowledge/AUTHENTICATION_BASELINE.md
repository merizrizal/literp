# Literp Authentication Baseline

## Status

The application-side authentication and authorization baseline is implemented
and covered by deterministic local tests. Provider and deployment acceptance is
still pending; this document does not authorize an untrusted deployment or mark
05.0 accepted.

The selected provider product in the design is Keycloak. The actual realm,
issuer URL, audience registration, claim mappers, provider operator, TLS
termination, and audit-log owner must be confirmed for each deployment.

A disposable real-Keycloak environment for local integration checks is provided
in [`../../docker/keycloak`](../../docker/keycloak/README.md). It uses the same
resource-server boundary and claim names, but local provider smoke does not
close the genuine non-production provider or deployment acceptance items below.

## Runtime configuration

The server fails before HTTP listen when any required setting is missing or
invalid. Configure these values through the deployment environment; do not add
them to `cfg.properties`, source control, or API collections:

| Setting | Meaning |
|---|---|
| `LITERP_AUTH_ISSUER` | Exact HTTPS issuer URL, without query or fragment |
| `LITERP_AUTH_AUDIENCE` | Expected API audience |
| `LITERP_AUTH_ORGANIZATION_ID` | Organization identifier for this database/deployment |
| `LITERP_AUTH_JWKS_PATH` | Read-only local file containing the provider's public JWKS |

The runtime stores public keys only. Private signing keys remain with the
provider. Provision a bounded JWKS file containing the active public `kid`
values before starting every instance.

## Access-token contract

Clients send exactly one `Authorization: Bearer <access-token>` header. The
runtime accepts RS256 access tokens with a known `kid`, exact issuer and
audience, required `iat`/`exp`, optional `nbf`, bounded lifetime, and valid
claim types. `typ=Bearer` remains supported for the deterministic development
fixture; standard Keycloak access tokens use `typ=JWT` and must carry a
nonblank `scope`, while RFC 9068 access tokens may use `typ=at+jwt`. ID tokens,
malformed or oversized tokens, unknown keys, algorithm changes, embedded key
material, and invalid time claims are rejected with `401 UNAUTHENTICATED` and
`WWW-Authenticate: Bearer`. Provider mappers must keep the Literp claims and
API audience on access tokens only so ID tokens cannot be used as API
credentials.

Provider-administered claims are:

| Claim | Required value |
|---|---|
| `sub` | Nonblank actor subject, at most 255 characters |
| `literp_org` | Exact configured organization identifier |
| `literp_permissions` | Array of explicit capability strings; absent means no capabilities |
| `literp_locations` | Array of canonical UUID grants; absent means no locations |
| `literp_kind` | Exactly `human` or `service` |
| `literp_operator` | Boolean when present; absent means false |

Profile fields must not be user-writable substitutes for these claims. The API
never treats `createdBy`, customer IDs, submitted location IDs, proxy headers,
cookies, or query parameters as identity.

## Authorization surface

All 31 business operation IDs require bearer authentication and an explicit
capability. Location-scoped order and stock operations additionally use the
verified location grants. An order's persisted location is checked before
nested order reads, command dispatch, idempotency lookup, or mutation. Missing
and out-of-scope order IDs use the same `404 RESOURCE_NOT_FOUND` response.

`/metrics`, `/health/ready`, and `/health/db` require `operations.read` plus an
operator or service principal. `GET /` and `GET /health/live` are the only
public exceptions and return minimal responses. There is no network-header
bypass or anonymous fallback.

Fulfillment ignores request-body `createdBy` and records the verified `sub` as
the inventory movement actor. Retries are reauthorized before replay lookup.
Existing lifecycle, idempotency, response-envelope, and request-ID contracts
remain unchanged.

## Bruno usage

The collection at [`../../api_collections/Literp`](../../api_collections/Literp)
uses collection-level bearer inheritance through the empty `accessToken`
variable. Supply an approved, non-production access token through Bruno's
local environment/session without committing it. `Index.bru` and
`Live-Health.bru` explicitly use `auth: none`; all other requests inherit the
bearer configuration.

The OpenAPI bundles under `../../api_collections/open_api_spec/` are the
published contract and keep YAML/JSON pairs synchronized.

## Non-production provider acceptance

Complete this procedure against a non-production deployment before treating the
baseline as deployable. Local generated-key tests prove Literp verification
logic; they do not prove that the deployed Keycloak realm, proxy, audience, or
claim mappers are configured correctly.

### Deployment input

Record a non-production HTTPS base URL and the deployment identity as
non-secret acceptance evidence. The deployment must use the same required
runtime configuration shape as production: `LITERP_AUTH_ISSUER`,
`LITERP_AUTH_AUDIENCE`, `LITERP_AUTH_ORGANIZATION_ID`, and
`LITERP_AUTH_JWKS_PATH`. The URL must identify the deployed acceptance target,
not a local fixture or a production endpoint.

### Approved token matrix

Obtain short-lived OAuth **access tokens** through approved Keycloak flows;
do not use ID tokens or refresh tokens as substitute credentials. Keep token
values outside Git, documentation, Bruno files, chat transcripts, shell
history, and CI logs. A maintainer may execute the checks and record only
sanitized results instead of disclosing credentials.

| Test identity | Required characteristics | Required outcome |
|---|---|---|
| Human reader | `literp_kind=human`, matching `literp_org`, correct audience, read capability, and an allowed location UUID | Allowed reads succeed within capability and location scope |
| Human writer/commander | Matching organization plus the exact write/command capability and location grant | Authorized command succeeds without changing lifecycle/idempotency semantics |
| Insufficient-capability human | Otherwise valid token missing the requested capability | `403 FORBIDDEN`; no business side effect |
| Wrong-location human | Otherwise valid token with an ungranted location | Location/stock denial is `403`; an out-of-scope order is indistinguishable from missing with `404 RESOURCE_NOT_FOUND` |
| Operator or service | `literp_kind=service` or `literp_operator=true`, matching organization, and `operations.read` | Can reach restricted metrics/readiness/database-health routes |
| ID-token negative case | Provider-issued ID token, even if signed by the same realm | `401 UNAUTHENTICATED` |

Validate that provider-issued tokens contain the runtime claim contract: a
bounded nonblank `sub`; exact `literp_org`; array-valued
`literp_permissions` and `literp_locations`; `literp_kind` of `human` or
`service`; optional boolean `literp_operator`; exact issuer/audience; and
valid access-token time claims. Provider claim mappers—not user-editable
profile fields—must administer these values.

### Smoke evidence

For the selected human and service identities, record the non-secret test
date, deployment identifier, token category, route/operation, expected status,
actual status, and responsible maintainer. Include public root/liveness,
anonymous protected-route rejection, authorized business access, denied
capability/location access, restricted operational-route access, expiry, and
ID-token rejection. Revoke test credentials or allow their short expiry after
the exercise.

## JWKS key rotation and revocation runbook

Literp verifies against the locally provisioned public JWKS at
`LITERP_AUTH_JWKS_PATH`; it does not perform token-selected or runtime JWKS
discovery. A Keycloak signing-key rotation therefore requires an explicit
public-key rollout to every instance.

1. **Establish baseline:** Record the active non-sensitive `kid`; confirm an
   access token signed by that key succeeds against every acceptance instance.
2. **Create overlap:** Generate/enable the new Keycloak signing key. Export a
   JWKS containing both old and new public keys, provision that same bounded
   file to every Literp instance, and roll/restart all instances.
3. **Prove overlap:** Before changing Keycloak's active signing key, verify
   that both old-key and new-key access tokens succeed. A new `kid` must never
   be accepted by only a subset of instances.
4. **Switch issuer signing:** Configure Keycloak to sign newly issued access
   tokens with the new key and verify a newly issued token succeeds through the
   deployed HTTPS path.
5. **Retire safely:** After the maximum accepted access-token lifetime plus
   clock skew has elapsed, remove the old public key from the JWKS and again
   roll/restart every instance. Verify new-key tokens succeed and a retained
   old-key test token fails with `401 UNAUTHENTICATED`.
6. **Handle compromise:** Remove a compromised public key, roll all instances,
   and accept temporary authentication failures rather than retaining an
   unsafe key. Restore service only after the replacement-key rollout is
   verified.

For each rotation, record the non-secret old/new key identifiers, deployment
identifier, JWKS rollout time, Keycloak signing-switch time, old/new-token
outcomes, retirement result, and operator responsible. Never record private
keys, complete bearer tokens, client secrets, or private JWKS material.

Permission or disablement changes take effect no later than the remaining
accepted token lifetime plus clock skew. Immediate revocation is not provided
by this local-JWKS design; require an approved introspection or bounded
revocation design if it is needed.

Never place provider tokens, client secrets, private keys, or personal test data
in this repository or in Bruno files.

## Deployment topology and audit ownership

Complete these controls for the non-production acceptance deployment, then
repeat them for production before exposing the service to an untrusted network.
A private network is defense in depth; it does not replace Literp's bearer-token
authentication and authorization checks.

### TLS and network boundary

Document the actual request path, for example:

```text
client -- HTTPS --> approved reverse proxy or ingress -- private network or mTLS --> Literp
```

The acceptance record must identify the TLS termination point, certificate
owner, proxy/ingress owner, deployment environment, and backend exposure rule.
If TLS terminates at a reverse proxy, the Literp listener must be reachable only
from that proxy or other explicitly approved private operators; it must not be
directly internet-reachable. Bearer credentials must never cross an untrusted
plaintext network hop.

Do not treat source IP, `X-Forwarded-*`, forwarded-user headers, cookies, or
query parameters as identity. The two public probes remain `GET /` and
`GET /health/live`; `/metrics`, `/health/ready`, and `/health/db` still require
the authorized operator/service principal described above, even when the
network is private.

### Deployment configuration and JWKS ownership

Name the role/team that owns each deployment environment, Keycloak realm,
issuer/audience registration, claim mappers, JWKS export, JWKS file
provisioning, restart/roll procedure, and emergency key-compromise response.
Confirm that the runtime settings and public JWKS file are injected through the
deployment secret/configuration mechanism rather than `cfg.properties`, source
control, API collections, or application logs. Only public verification keys
belong on Literp instances; Keycloak retains private signing keys.

### Audit-log ownership

Name the log sink and responsible role/team, its access-control owner, retention
period, alert recipient, and incident-response contact. Security records may
include request ID, operation ID, status, fixed decision reason, and a verified
subject after successful verification. They must not include Authorization
headers, full JWTs, refresh tokens, client secrets, private keys, raw payment or
customer request bodies, or unbounded attacker-controlled values.

The acceptance evidence should record the topology/configuration review date,
environment identifier, responsible roles, and outcomes without including
credentials, internal addresses, firewall rules, or other sensitive deployment
details.

## Acceptance ledger

| Evidence | Status | Notes |
|---|---|---|
| Production-router authentication and authorization tests | Complete locally | Deterministic generated-key fixture; 31 anonymous rejections and authorized/denied scope cases are covered |
| Full Gradle regression/build | Complete locally | Chunk 7 recorded 65 tests, zero skips/failures/errors |
| OpenAPI YAML/JSON synchronization | Required for this publication | Run `rtk python scripts/verify_openapi_assets.py` in the approved Python environment |
| Bruno authenticated smoke | Pending | Requires an approved non-production access token supplied outside repository artifacts |
| Genuine provider token smoke | Pending | Confirm human/service flows, audience, claim mappers, ID-token rejection, expiry, and logout behavior with the actual provider |
| Key rollover smoke | Pending | Exercise active/old key overlap and removed-key rejection in the deployment |
| Deployment/network acceptance | Pending | Confirm HTTPS/private proxy topology, environment ownership, JWKS provisioning, and audit sink/retention owner |
| Maintainer acceptance of 05.0 | Pending | Do not check the ADS approval, Done-when, or Phase entry-gate boxes until the pending evidence exists |
