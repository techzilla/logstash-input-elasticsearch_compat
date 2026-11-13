# Data Model: OTel Bulk Ingest

## BulkRequest (Java)

| Field | Type | Rule |
|---|---|---|
| `operations` | `List<Operation>` | Request order preserved; size ≥ 1 |
| `filterPath` | `List<String[]>` or null | Parsed `items.*.<field>` paths (research R4) |

Validation (whole body, before any submission — FR-006): every line valid UTF-8 JSON object;
action line has exactly one key; `index`/`create`/`update` followed by a source line; `delete` is
not; body ends with `\n`; unknown action → request `400`.

## Operation (Java)

| Field | Type | Source |
|---|---|---|
| `action` | enum `INDEX, CREATE, UPDATE, DELETE` | Action line key |
| `index` | `String` or null | `_index` |
| `id` | `String` or null | `_id` |
| `routing` | `String` or null | `routing` / `_routing` |
| `pipeline` | `String` or null | `pipeline` |
| `document` | `Map<String,Object>` or null | Source line; null for `delete`/`update` |
| `preStatus` | `ItemOutcome` or null | Set when rejected before submission |

Other action-line keys (`dynamic_templates`, `require_alias`, …) are read and discarded (FR-010).

`preStatus` rules, in order: `UPDATE`/`DELETE` → `UNSUPPORTED_ACTION`; `index` null or empty →
`MISSING_INDEX`; `@metadata` present and not an object → `INVALID_DOCUMENT`.

Top-level `@timestamp` that is a JSON number or a digit string (`-?\d+(\.\d+)?`) is epoch
milliseconds, as Elasticsearch's default date format reads it, and is converted to an ISO-8601
string from the exact text (research R10). Out-of-range values are left unchanged.

## Java ↔ Ruby handoff

`BatchHandler.submit(List<Operation> submittable, ItemOutcome[] results)`: Java passes only
submittable operations; Ruby sets `QUEUED` or `SUBMIT_FAILED`; entries left `null` become
`NOT_ATTEMPTED`.

## Event (Ruby, built per submittable Operation)

- Fields: `document` as-is (`LogStash::Event.new(map)`); Logstash coerces `@timestamp`.
- Metadata at `metadata_target` (default `[@metadata][elasticsearch_compat]`):
  `action` (`"index"`/`"create"`), `index`, and `id`, `routing`, `pipeline` only when present.
- Then `decorate(event)` applies `tags`, `add_field`, `type`.

## ItemOutcome (Ruby → Java, one per Operation)

| Outcome | Item status | `error.type` | Retried by exporter |
|---|---|---|---|
| `QUEUED` | 201 | — | — |
| `UNSUPPORTED_ACTION` | 400 | `illegal_argument_exception` | No |
| `MISSING_INDEX` | 400 | `action_request_validation_exception` | No |
| `INVALID_DOCUMENT` (`@metadata` present but not an object; Logstash cannot build the event) | 400 | `document_parsing_exception` | No |
| `SUBMIT_FAILED` (push raised; ambiguous) | 500 | `exception` | Yes (may duplicate) |
| `NOT_ATTEMPTED` (after failure / shutdown) | 503 | `unavailable_exception` | Yes |

## Request lifecycle

```text
received → [413/415/429 refused] → parsed+validated → [400 refused]
         → submitted item by item (Ruby) → response encoded in request order → sent
```

No state persists between requests.
