# Contract: HTTP API (changes from 001)

Base contract: [001 http-bulk.md](../../001-otel-bulk-ingest/contracts/http-bulk.md). Everything
there still holds unless changed below.

## Routes

`POST|PUT /_bulk` and `POST|PUT /{index}/_bulk` — identical behavior; on `/{index}/_bulk`, an
action without `_index` uses `{index}` (URL-decoded, one non-empty segment).

## Identity

```http
GET / HTTP/1.1

HTTP/1.1 200 OK
X-Elastic-Product: Elasticsearch
Content-Type: application/json

{"name":"logstash-input-elasticsearch_compat","version":{"number":"8.19.0","build_flavor":"default"},"tagline":"Elasticsearch Bulk API compatibility receiver; not Elasticsearch"}
```

`HEAD /` → same status and headers, no body. Compatibility placeholder: `8.19.0` is fixed so the
OTel exporter keeps its baseline metrics encoding; it is not a claim to be Elasticsearch.

## Compression

| Request | Result |
|---|---|
| `Content-Encoding: gzip`/`x-gzip`/`deflate`/`x-deflate`/`identity`/absent | Decoded, processed normally (always, regardless of `compression_enabled`) |
| Other `Content-Encoding` | 415 |
| Corrupt compressed data | 400 `parse_exception` |
| Decompressed size > `max_content_length` | 413 |

Responses: when `compression_enabled` (default) and `Accept-Encoding` allows gzip or deflate,
every response is compressed (level 3, no size threshold) with `Content-Encoding` set.

## TLS

With `ssl_enabled`, the port speaks HTTPS only; plain HTTP connections fail. All routes behave the
same over TLS.
