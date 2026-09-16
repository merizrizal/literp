# Literp POS Operations API - OpenAPI Specification

This directory contains the published OpenAPI contract for POS terminals, shifts,
and receipt lookup.

## Files

- **pos-operations.yaml** - OpenAPI 3.0 specification in YAML format
- **pos-operations.json** - The synchronized JSON representation
- **pos-operations-README.md** - Contract and publication notes

The YAML and JSON documents describe the same ten operations. The JSON asset is
kept synchronized with the YAML source and the application version.

## API status

All ten operations are mounted behind the authenticated resource-server
boundary as safe placeholders. An eligible authenticated request currently
returns `501 NOT_IMPLEMENTED`; authentication, organization, capability, and
principal-eligibility checks run before the placeholder. Placeholders do not
write to the database, resolve resources, replay idempotency keys, or claim
resource-scope enforcement.

Terminal and shift behavior is not implemented yet. Receipt lookup is not
implemented yet, and receipt generation and refunds are not exposed by this
contract.

## Base URL

- Development: `http://localhost:8010/api/v1`
- Production: `https://api.literp.example.com/api/v1` (deployment acceptance is pending)

## Operations

| Method | Path | operationId | Availability |
|---|---|---|---|
| GET | `/pos/terminals` | `listPosTerminals` | Authenticated placeholder |
| POST | `/pos/terminals` | `createPosTerminal` | Authenticated placeholder |
| GET | `/pos/terminals/{terminalId}` | `getPosTerminal` | Authenticated placeholder |
| PATCH | `/pos/terminals/{terminalId}` | `updatePosTerminal` | Authenticated placeholder |
| POST | `/pos/terminals/{terminalId}/deactivate` | `deactivatePosTerminal` | Authenticated placeholder |
| POST | `/pos/terminals/{terminalId}/shifts` | `openPosShift` | Authenticated placeholder |
| GET | `/pos/terminals/{terminalId}/current-shift` | `getCurrentPosShift` | Authenticated placeholder |
| POST | `/pos/shifts/{shiftId}/close` | `closePosShift` | Authenticated placeholder |
| GET | `/pos/receipts/by-number/{receiptNumber}` | `getPosReceiptByNumber` | Authenticated placeholder |
| GET | `/pos/orders/{salesOrderId}/receipts` | `listPosReceiptsBySalesOrder` | Authenticated placeholder |

All paths are relative to `/api/v1`. Terminal and shift persistence and
attribution are future behavior. Receipt lookup and any receipt generation or
refund workflow are future behavior; no receipt/refund routes are invented
here.

## Contract boundaries

- Terminal identifiers, shift identifiers, and sales-order identifiers are UUIDs.
- Terminal codes are bounded to 1–50 characters; device names are bounded to
  1–255 characters.
- Terminal listing supports bounded pagination, authorized `locationId` and
  `isActive` filters, and the documented terminal-code/creation-time sort
  allowlist.
- Terminal creation, shift opening, and shift closing require a bounded
  `Idempotency-Key` header.
- Shift opening accepts only a nonnegative two-decimal `openingBalance` and a
  three-letter uppercase `currency`.
- Shift closing accepts only the nonnegative two-decimal counted
  `closingBalance`; expected cash and variance are server-owned values.
- Receipt-number lookup is bounded to 1–50 characters. Receipt listing by
  sales order uses bounded pagination and deterministic receipt-date/receipt-ID
  ordering.
- Receipt `receiptData` is potentially sensitive historical data and is not
  public telemetry.

## Response and error contract

Successful resource responses use `{ "data": ... }`; list responses use
`{ "data": [...], "pagination": ... }`. The current placeholder response is
an error envelope such as:

```json
{
  "error": "POS operation is not implemented",
  "errorCode": "NOT_IMPLEMENTED",
  "status": 501,
  "errorId": "550e8400-e29b-41d4-a716-446655440000"
}
```

Every response preserves `X-Request-ID`. Authentication failures use the
existing bearer challenge and error-envelope conventions. Capability,
organization, location, and human-only shift eligibility remain fail-closed.

## Bruno collection

The reproducible requests are under `../Literp`:

- `Pos-List-Terminals.bru`
- `Pos-Create-Terminal.bru`
- `Pos-Get-Terminal.bru`
- `Pos-Update-Terminal.bru`
- `Pos-Deactivate-Terminal.bru`
- `Pos-Open-Shift.bru`
- `Pos-Current-Shift.bru`
- `Pos-Close-Shift.bru`
- `Pos-Get-Receipt-By-Number.bru`
- `Pos-List-Receipts-By-Order.bru`

Each request uses `auth: inherit`, contains no credential literal, documents
its HTTP method/path and OpenAPI `operationId`, and records the `501
NOT_IMPLEMENTED` placeholder status. The sample identifiers, amounts, and
idempotency keys in `environments/Local.bru` are nonsecret only. Provide an
approved local bearer token through the existing development setup; do not
commit or copy credentials into POS request files. No request has a
post-response script that could treat a placeholder as a successful workflow
step.

The existing core order requests remain the source for order creation,
confirmation, payment capture, fulfillment, and cancellation. No duplicate POS
order lifecycle requests are included here.

## Validation

From the repository root, use the project Gradle contract tests and the
OpenAPI asset verifier in an approved Python environment:

```bash
./gradlew test --tests com.literp.contract.PosOperationsContractTest
python scripts/verify_openapi_assets.py
```

The verifier checks that all OpenAPI YAML/JSON pairs exist, match the Gradle
application version, and are canonically equal. The contract test checks the
ten Bruno request files, inherited authentication, operation/path traceability,
and safe placeholder documentation.

## Security and acceptance status

Every POS operation requires the `bearerAuth` requirement. Terminal and receipt
resource access must remain location-scoped when behavior is implemented;
shift opening and closing additionally require the approved human-principal
rules. Read capability does not imply write capability.

This publication does not constitute provider, deployment, JWKS, network,
audit-ownership, or external authenticated acceptance. Those security and
operational rows remain pending in
[AUTHENTICATION_BASELINE.md](../../docs/knowledge/AUTHENTICATION_BASELINE.md).
