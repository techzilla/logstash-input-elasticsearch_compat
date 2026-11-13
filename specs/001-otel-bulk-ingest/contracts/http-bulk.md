# Contract: HTTP Bulk endpoint

## Request

`POST /_bulk[?filter_path=...&<any other params ignored>]`

- Body: Bulk NDJSON, UTF-8, final `\n` required. `Content-Type` `application/x-ndjson` or
  `application/json` (others accepted and parsed the same).
- No `Content-Encoding` allowed in this feature.
- `Authorization` header: any or none; ignored and dropped (FR-011).

Other paths → `404`. Other methods on `/_bulk` → `405`.

## Response headers (all responses)

- `X-Elastic-Product: Elasticsearch` (FR-015)
- `Content-Type: application/json` when a body is present

## 200 — request parsed

```json
{
  "took": 3,
  "errors": true,
  "items": [
    {"create": {"_index": "logs-generic.otel-default", "status": 201}},
    {"index":  {"_index": "logs-a", "_id": "7", "status": 201}},
    {"update": {"_index": "logs-a", "_id": "8", "status": 400,
                "error": {"type": "illegal_argument_exception",
                          "reason": "update is not supported by this receiver"}}}
  ]
}
```

- `items` in request order; key = original action name.
- `_index` present when resolved; `_id` only when supplied; `status` always.
- `errors` is `true` if any item status ≥ 300.
- Never: `_version`, `_seq_no`, `_primary_term`, `_shards`, `result`, generated `_id`.
- With `filter_path`, only listed `items.*.<field>` paths are emitted; `took`/`errors` are
  dropped unless listed. Item outcomes: see [data-model.md](../data-model.md#itemoutcome-ruby--java-one-per-operation).

## Request-level errors

Body shape: `{"error": {"type": "<type>", "reason": "<reason>"}, "status": <code>}`.

| Status | When | Anything queued |
|---|---|---|
| 400 | Malformed framing/JSON/UTF-8, unknown action, unsupported `filter_path` | No |
| 404 / 405 | Unknown path / method | No |
| 413 | Body over `max_content_length` | No |
| 415 | `Content-Encoding` present | No |
| 429 | Pending-request queue full | No |
| 503 | Shutting down before submission started | No |
