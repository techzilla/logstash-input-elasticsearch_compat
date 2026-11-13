# Data Model: Release Readiness

Changes relative to [001 data-model](../001-otel-bulk-ingest/data-model.md).

## Request routing

| Method | Path | Result |
|---|---|---|
| `POST`, `PUT` | `/_bulk` | Bulk request, no default index |
| `POST`, `PUT` | `/{index}/_bulk` | Bulk request, default index = decoded `{index}` |
| `GET` | `/` | Identity response (200 + body) |
| `HEAD` | `/` | Identity response (200, no body) |
| other method | any of the above | 405 |
| anything else | — | 404 |

## Operation (changed)

`index` resolution: action `_index` if non-empty, else the route's default index, else
`MISSING_INDEX` (item 400).

## Encodings

- Request `Content-Encoding`: `gzip`, `x-gzip`, `deflate`, `x-deflate`, `identity` accepted;
  others → 415. Decompressed size counts toward `max_content_length` (413). Corrupt data → 400.
- Response: compressed (gzip or deflate, level 3) whenever `Accept-Encoding` allows and
  `compression_enabled` is true.

## Plugin counters

Cumulative, per input, low cardinality:

| Counter | Incremented when |
|---|---|
| `bulk_requests` | `POST`/`PUT` reaches a Bulk route (before any refusal); wrong-method 405s are not counted |
| `bulk_requests_rejected_overload` | 429 |
| `bulk_requests_rejected_too_large` | 413 |
| `bulk_requests_rejected_unsupported_encoding` | 415 |
| `bulk_requests_rejected_bad_request` | 400 (malformed body, corrupt compression, unsupported `filter_path`) |
| `bulk_items_failed` | +N for N items with status ≥ 400 in a 200 response |
| `queue_submit_failures` | +1 per item reported `SUBMIT_FAILED` |

Invariant: `bulk_requests` ≥ sum of the four `rejected_*` counters.

## TLS configuration

Settings and validation rules: see [contracts/plugin-config.md](contracts/plugin-config.md) and
research R5. TLS state is built once at `register`; no hot reload.
