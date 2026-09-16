# Local Keycloak integration

This directory provides a disposable, real Keycloak environment for local
provider-integration checks. It is not a mock provider and it does not replace
the required non-production provider/deployment acceptance for task 05.0.

The environment verifies the boundary:

```text
local client or service -> Keycloak-issued access token -> Literp resource server
```

It does not add a Literp login endpoint, token issuer, refresh-token endpoint,
or application-side identity store.

## Prerequisites

- Docker and Docker Compose
- OpenSSL
- `curl` and `jq`
- The Literp PostgreSQL database running according to
  [Quick Start](../../docs/QUICK_START.md)

The repository currently has no SPA. The `literp-spa` client is imported so a
future frontend can use authorization-code + PKCE without changing the API
boundary. The `literp-service` client supports a local client-credentials
smoke without committing a client secret.

## Start Keycloak

Run these commands from the repository root.

### 1. Generate a local certificate

```bash
bash docker/keycloak/generate-local-certificate.sh
```

Trust `docker/keycloak/certs/localhost-cert.pem` in the browser or local trust
store before using a browser-based client. The certificate and private key are
ignored by Git.

### 2. Set a process-scoped administrator password

Do not put this value in the repository, command history, or documentation.

```bash
export KC_BOOTSTRAP_ADMIN_USERNAME=admin
read -r -s -p "Local Keycloak admin password: " KC_BOOTSTRAP_ADMIN_PASSWORD
printf '\n'
export KC_BOOTSTRAP_ADMIN_PASSWORD
docker compose -f docker/keycloak/docker-compose.yml up -d
```

Keycloak is bound to loopback at `https://localhost:8443`. Realm import creates:

- realm `literp-local`
- public client `literp-spa`, with standard flow and S256 PKCE
- confidential service client `literp-service`, with client credentials enabled

No user password or client secret is stored in the realm file.

### 3. Create a local human user

Open `https://localhost:8443`, sign in with the bootstrap administrator, switch
to realm `literp-local`, and create a development user such as `alice`. Set the
password interactively in Keycloak; do not commit it. No Literp-specific role is
required for this local fixture because the client mappers provide the
provider-administered test claims.

The human client is intentionally configured with:

- redirect URI: `http://localhost:5173/auth/callback`
- web origin: `http://localhost:5173`
- implicit flow disabled
- direct access grants/password flow disabled
- S256 PKCE required

## Verify discovery and provision the public JWKS

The API does not perform runtime JWKS discovery. Fetch the public keys
explicitly after Keycloak starts:

```bash
curl \
  --cacert docker/keycloak/certs/localhost-cert.pem \
  https://localhost:8443/realms/literp-local/.well-known/openid-configuration \
  | jq '{issuer, authorization_endpoint, token_endpoint, jwks_uri}'

bash docker/keycloak/fetch-local-jwks.sh
```

The JWKS is written to the ignored path:

```text
docker/keycloak/runtime/keycloak-jwks.json
```

The raw Keycloak endpoint can also publish encryption keys. The helper filters
those out and writes only public RS256 signing keys (`kid`, `n`, and `e`) so the
result matches Literp's intentionally strict verifier contract. Literp loads
this file when its verifier is constructed; after a key update, provision the
new file and restart Literp.

## Configure and run Literp

Use the existing database configuration, then add the local provider settings:

```bash
export LITERP_AUTH_ISSUER=https://localhost:8443/realms/literp-local
export LITERP_AUTH_AUDIENCE=literp-api
export LITERP_AUTH_ORGANIZATION_ID=literp-local
export LITERP_AUTH_JWKS_PATH="$PWD/docker/keycloak/runtime/keycloak-jwks.json"
export LITERP_HTTP_PORT=8010
./gradlew run
```

The server remains at `http://localhost:8010`; Keycloak HTTPS is only the local
identity-provider endpoint.

## Service-token smoke

Keycloak generates the `literp-service` client secret during realm import. Read
it from the local Keycloak admin console under **Clients → literp-service →
Credentials**. Keep it only in a process-scoped environment variable.

```bash
read -r -s -p "Local service client secret: " LITERP_LOCAL_SERVICE_SECRET
printf '\n'
export LITERP_LOCAL_SERVICE_SECRET
LITERP_ACCESS_TOKEN="$(curl \
  --cacert docker/keycloak/certs/localhost-cert.pem \
  --fail --silent --show-error \
  --request POST \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode client_id=literp-service \
  --data-urlencode client_secret="${LITERP_LOCAL_SERVICE_SECRET}" \
  https://localhost:8443/realms/literp-local/protocol/openid-connect/token \
  | jq -er .access_token)"
export LITERP_ACCESS_TOKEN

curl \
  --fail-with-body \
  -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" \
  "http://localhost:8010/api/v1/uom?page=0&size=20&sort=code,asc" \
  | jq

curl \
  --fail-with-body \
  -H "Authorization: Bearer ${LITERP_ACCESS_TOKEN}" \
  http://localhost:8010/health/db \
  | jq
```

The service token should contain the exact Literp claims and capabilities:

- `literp_org=literp-local`
- `literp_kind=service`
- `literp_permissions` including `master-data.read` and `operations.read`
- `literp_locations` containing the seeded warehouse, store, and production IDs
- `aud` containing `literp-api`

Do not print or paste the complete access token. Inspect only selected header or
claim fields with a local tool if needed.

## Token-header compatibility check

Keycloak 26.7.3 local service-token smoke emits `alg=RS256` and `typ=JWT`, with
the expected issuer, `literp-api` audience, `scope`, and Literp claims. The
verifier therefore accepts:

- `typ=Bearer` for the existing deterministic development fixture;
- `typ=JWT` for standard Keycloak access tokens only when `scope` is nonblank;
- `typ=at+jwt` for RFC 9068 access tokens.

All other JOSE types remain rejected. ID-token rejection still depends on exact
API audience validation and provider mappers keeping the Literp claims and API
audience on access tokens only. If a future provider uses another profile,
record only non-secret header/claim metadata and update the verifier, tests, and
baseline documentation together; do not silently broaden the allowlist.

## SPA / PKCE boundary

When a frontend exists, it should use the imported `literp-spa` public client
with authorization-code + S256 PKCE and send the **access token**, not the ID
token, to Literp:

```http
Authorization: Bearer <access-token>
```

The frontend must keep tokens in memory and must not persist them in
`localStorage`, `sessionStorage`, or IndexedDB. Browser CORS for the actual SPA
origin is a separate API configuration decision and is not enabled by this
backend-only fixture.

## Stop and reset

Stop the local provider without touching Literp or PostgreSQL:

```bash
docker compose -f docker/keycloak/docker-compose.yml down
```

To recreate the imported realm, remove the container and run `up` again. Remove
only local generated material when it is no longer needed:

```bash
rm -rf docker/keycloak/certs docker/keycloak/runtime
```

Never use this `start-dev` provider, its bootstrap administrator, or its local
claims as production configuration. Local provider smoke is evidence of
integration behavior only; genuine non-production provider tokens, deployment
TLS/network ownership, key rotation, audit ownership, and maintainer acceptance
remain separate requirements.
