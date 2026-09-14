#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CERT_DIR="${SCRIPT_DIR}/certs"
CERT_PATH="${CERT_DIR}/localhost-cert.pem"
KEY_PATH="${CERT_DIR}/localhost-key.pem"

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required to generate the local Keycloak certificate" >&2
  exit 1
fi

if [[ -e "${CERT_PATH}" || -e "${KEY_PATH}" ]]; then
  echo "Local Keycloak certificate files already exist under ${CERT_DIR}; remove both before regenerating" >&2
  exit 1
fi

umask 077
mkdir -p "${CERT_DIR}"
openssl req \
  -x509 \
  -newkey rsa:3072 \
  -nodes \
  -sha256 \
  -days 365 \
  -keyout "${KEY_PATH}" \
  -out "${CERT_PATH}" \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

chmod 600 "${KEY_PATH}"
chmod 644 "${CERT_PATH}"
printf 'Created local Keycloak certificate: %s\n' "${CERT_PATH}"
printf 'Created local Keycloak private key: %s\n' "${KEY_PATH}"
printf 'Trust the certificate locally before using a browser-based PKCE client.\n'
