#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
KEYCLOAK_URL="${KEYCLOAK_URL:-https://localhost:8443}"
KEYCLOAK_URL="${KEYCLOAK_URL%/}"
REALM="${REALM:-literp-local}"
CERT_PATH="${CERT_PATH:-${SCRIPT_DIR}/certs/localhost-cert.pem}"
OUTPUT_PATH="${OUTPUT_PATH:-${SCRIPT_DIR}/runtime/keycloak-jwks.json}"
TEMP_PATH=""
FILTERED_PATH=""

cleanup() {
  if [[ -n "${TEMP_PATH}" ]]; then
    rm -f "${TEMP_PATH}"
  fi
  if [[ -n "${FILTERED_PATH}" ]]; then
    rm -f "${FILTERED_PATH}"
  fi
}
trap cleanup EXIT

for command_name in curl jq; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "${command_name} is required to fetch the local Keycloak JWKS" >&2
    exit 1
  fi
done

if [[ ! -r "${CERT_PATH}" ]]; then
  echo "Certificate not found: ${CERT_PATH}; run generate-local-certificate.sh first" >&2
  exit 1
fi

mkdir -p "$(dirname -- "${OUTPUT_PATH}")"
TEMP_PATH="$(mktemp "${OUTPUT_PATH}.tmp.XXXXXX")"
curl \
  --fail \
  --silent \
  --show-error \
  --retry 3 \
  --connect-timeout 5 \
  --max-time 15 \
  --cacert "${CERT_PATH}" \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/certs" \
  > "${TEMP_PATH}"

jq -e '
  (.keys | type == "array" and length > 0)
  and ([.keys[] | select(.kty == "RSA" and .alg == "RS256" and .use == "sig")] | length > 0)
  and (([.keys[] | select(.kty == "RSA" and .alg == "RS256" and .use == "sig")] | map(.kid))
    | length == (unique | length))
  and all([.keys[] | select(.kty == "RSA" and .alg == "RS256" and .use == "sig")][];
    (.kid | type == "string" and length > 0)
    and (.n | type == "string" and length > 0)
    and (.e | type == "string" and length > 0)
  )
' "${TEMP_PATH}" >/dev/null

FILTERED_PATH="$(mktemp "${OUTPUT_PATH}.filtered.XXXXXX")"
jq '{keys: [.keys[] | select(.kty == "RSA" and .alg == "RS256" and .use == "sig") | {kty, alg, use, kid, n, e}]}' \
  "${TEMP_PATH}" > "${FILTERED_PATH}"

jq -e '
  (.keys | type == "array" and length > 0)
  and (([.keys[].kid] | unique | length) == (.keys | length))
  and all(.keys[];
    .kty == "RSA"
    and .alg == "RS256"
    and .use == "sig"
    and (.kid | type == "string" and length > 0)
    and (.n | type == "string" and length > 0)
    and (.e | type == "string" and length > 0)
  )
' "${FILTERED_PATH}" >/dev/null

mv "${FILTERED_PATH}" "${OUTPUT_PATH}"
FILTERED_PATH=""
rm -f "${TEMP_PATH}"
TEMP_PATH=""
chmod 644 "${OUTPUT_PATH}"
printf 'Wrote sanitized public Keycloak JWKS: %s\n' "${OUTPUT_PATH}"
