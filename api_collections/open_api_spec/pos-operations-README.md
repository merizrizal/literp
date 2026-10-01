# Literp POS Operations API - OpenAPI Specification

This directory contains the published OpenAPI contract for POS terminals, shifts,
and receipt lookup.

## Files

- **pos-operations.yaml** - OpenAPI 3.0 specification in YAML format
- **pos-operations.json** - The synchronized JSON representation
- **pos-operations-README.md** - Contract and publication notes

The YAML and JSON documents describe the same twelve operations. The JSON asset
is kept synchronized with the YAML source and the application version.

## API status

The eight terminal and shift operations are implemented behind the authenticated
resource-server boundary. Terminal access and POS attribution are location-scoped;
shift opening and closing require an authenticated human, and only the shift owner
may close it. POS orders use the existing Order Process endpoints.

The two receipt lookup operations are implemented, authenticated, and
location-scoped. Receipt generation and refunds remain authenticated
`501 NOT_IMPLEMENTED` placeholders for their later implementation slices.

Historical shifts without trusted currency and payment attribution are not
silently backfilled or reconciled; they require an explicit remediation process.
Expected cash is the opening balance plus persisted attributed captured CASH
payments. Tender/change-given, refunds, cash adjustments, paid-outs, and currency
conversion are not modeled.

## Base URL

- Development: `http://localhost:8010/api/v1`
- Production: `https://api.literp.example.com/api/v1` (deployment acceptance is pending)

## Operations

| Method | Path | operationId | Availability |
|---|---|---|---|
| GET | `/pos/terminals` | `listPosTerminals` | Implemented; location-scoped |
| POST | `/pos/terminals` | `createPosTerminal` | Implemented; authorized location and idempotent |
| GET | `/pos/terminals/{terminalId}` | `getPosTerminal` | Implemented; location-scoped |
| PATCH | `/pos/terminals/{terminalId}` | `updatePosTerminal` | Implemented; location-scoped |
| POST | `/pos/terminals/{terminalId}/deactivate` | `deactivatePosTerminal` | Implemented; conflicts while shift is open |
| POST | `/pos/terminals/{terminalId}/shifts` | `openPosShift` | Implemented; human-owned and idempotent |
| GET | `/pos/terminals/{terminalId}/current-shift` | `getCurrentPosShift` | Implemented; location-scoped |
| POST | `/pos/shifts/{shiftId}/close` | `closePosShift` | Implemented; owner-only reconciliation and idempotent |
| GET | `/pos/receipts/by-number/{receiptNumber}` | `getPosReceiptByNumber` | Implemented; location-scoped |
| GET | `/pos/orders/{salesOrderId}/receipts` | `listPosReceiptsBySalesOrder` | Implemented; location-scoped and paginated |
| POST | `/pos/orders/{salesOrderId}/receipts` | `generatePosReceipt` | Authenticated 501 placeholder |
| POST | `/pos/receipts/{receiptId}/refunds` | `createPosReceiptRefund` | Authenticated 501 placeholder |

All paths are relative to `/api/v1`. Terminal administration, shift lifecycle,
trusted order attribution, and both receipt lookups are implemented. Receipt
generation and refunds remain authenticated placeholders; additional
receipt/refund routes are outside this contract.

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
`{ "data": [...], "pagination": ... }`. The receipt generation and refund
write placeholders return `501 NOT_IMPLEMENTED` in the standard error envelope,
for example:

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
- `Pos-Generate-Receipt.bru`
- `Pos-Create-Receipt-Refund.bru`

All requests use `auth: inherit`, contain no credential literal, and document
their HTTP method/path and OpenAPI `operationId`. Only the receipt generation
and refund requests document the `501 NOT_IMPLEMENTED` placeholder status. The
sample identifiers, amounts, and idempotency keys in `environments/Local.bru` are
nonsecret only. Provide an approved local bearer token through the existing
development setup; do not commit or copy credentials into POS request files. No
request has a post-response script that could treat a placeholder as a successful
workflow step.

The existing core order requests remain the source for order creation,
confirmation, payment capture, fulfillment, and cancellation. No duplicate POS
order lifecycle requests are included here.

## Validation

From the repository root, run the focused POS contract and authenticated HTTP
acceptance tests, then verify the synchronized OpenAPI assets in an approved
Python environment:

```bash
./gradlew test --tests com.literp.contract.PosOperationsContractTest
./gradlew test --tests com.literp.verticle.AuthenticationHttpIntegrationTest
python scripts/verify_openapi_assets.py
```

The verifier checks that all OpenAPI YAML/JSON pairs exist, match the Gradle
application version, and are canonically equal. The contract test checks the
12 Bruno requests, authentication and path traceability, and that `501` appears
only for receipt/refund write placeholders. The authenticated HTTP test covers
the terminal-to-order-to-shift-close lifecycle, reconciliation, actor linkage,
and fulfillment after close.

## Security and acceptance status

Every POS operation requires the `bearerAuth` requirement. Implemented terminal
operations, POS-attributed order commands, and receipt lookups enforce location
scope. Shift opening and closing require a human principal, and closing is
owner-only. Read capability does not imply write capability.

This publication does not constitute provider, deployment, JWKS, network,
audit-ownership, or external authenticated acceptance. Those security and
operational rows remain pending in
[AUTHENTICATION_BASELINE.md](../../docs/knowledge/AUTHENTICATION_BASELINE.md).
