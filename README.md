# logstash-input-elasticsearch_compat

A Logstash input that accepts Elasticsearch Bulk API requests (`POST`/`PUT` on `/_bulk` and
`/{index}/_bulk`) and emits one Logstash event per `index` or `create` action. Point a client that
normally writes to Elasticsearch — tested with the OpenTelemetry Collector `elasticsearch` exporter
with its default settings — at Logstash instead.

This is a protocol-compatible receiver, **not** an Elasticsearch node.

## Install

```bash
bin/logstash-plugin install logstash-input-elasticsearch_compat-<version>-java.gem
```

## Configuration

```ruby
input {
  elasticsearch_compat {
    port => 9200
  }
}
```

| Setting | Default | Purpose |
|---|---|---|
| `host` | `"0.0.0.0"` | Listen address |
| `port` | `9200` | Listen port |
| `threads` | CPU cores | Worker threads that parse requests and push events |
| `max_pending_requests` | `200` | Requests waiting for a worker; beyond this, `429` |
| `max_content_length` | `104857600` | Maximum body size in bytes; beyond this, `413` |
| `metadata_target` | `"[@metadata][elasticsearch_compat]"` | Where routing metadata goes |
| `compression_enabled` | `true` | Compress responses when the client accepts it (requests are always decoded) |
| `ssl_*` | `ssl_enabled => false` | TLS; the same 14 settings, defaults and rules as `logstash-input-http` |

Standard input options (`id`, `tags`, `add_field`, `type`, `enable_metric`) work as usual. Full
reference: [docs/index.asciidoc](docs/index.asciidoc).

## Routes

- `POST`/`PUT` `/_bulk` and `/{index}/_bulk` (the URL index applies only to actions without
  `_index`).
- `GET /` and `HEAD /` return a minimal identity for client version detection. It reports version
  `8.19.0`, a fixed compatibility placeholder that keeps the OTel exporter on its most widely
  compatible metrics format.

## Compression

Like Elasticsearch: gzip and deflate request bodies are always accepted (`max_content_length`
counts decompressed bytes); responses are compressed at level 3 whenever the client accepts it,
unless `compression_enabled => false`. Other encodings get `415`.

## TLS

```ruby
input {
  elasticsearch_compat {
    ssl_enabled => true
    ssl_certificate => "/etc/logstash/certs/server.crt"
    ssl_key => "/etc/logstash/certs/server.pkcs8.key"     # PKCS#8
    # ssl_client_authentication => "required"
    # ssl_certificate_authorities => ["/etc/logstash/certs/ca.crt"]
  }
}
```

Keystores, truststores, protocols, cipher suites and handshake timeout follow
`logstash-input-http` exactly.

## Monitoring

Counters for this input appear in `GET :9600/_node/stats/pipelines/<id>`: `bulk_requests`,
`bulk_requests_rejected_overload`/`_too_large`/`_unsupported_encoding`/`_bad_request`,
`bulk_items_failed`, `queue_submit_failures`.

## Events

- Event fields are the source document.
- `[@metadata][elasticsearch_compat]` holds `action`, `index`, and `id`, `routing`, `pipeline`
  when supplied. `@metadata` is not written by outputs; copy what downstream consumers need,
  for example `mutate { copy => { "[@metadata][elasticsearch_compat][index]" => "[target_index]" } }`.
- `@timestamp`: ISO-8601 strings are parsed by Logstash. Numbers and digit strings are epoch
  milliseconds, as Elasticsearch reads them (this is how the OTel exporter sends it) and keep
  nanosecond precision. Anything else is tagged `_timestampparsefailure` and kept in
  `_@timestamp`.

## What a response means

- An item's `201` means the event was **accepted by the Logstash queue** — not that an output
  delivered it, and not a durable checkpoint unless you use the persistent queue.
- `update` and `delete` are **not supported**: each gets an item-level `400`, never a success.
- An item without `_index` gets `400`. A document whose `@metadata` is not an object gets `400`.
- A malformed body (bad framing, JSON, or UTF-8) is rejected with `400` and **nothing** from it
  is queued.
- If queuing fails mid-request, items already queued are `201`, the failing item `500`
  (outcome unknown), and the rest `503`.
- **Duplicates are possible**: clients retry `429`, `500`, `503`, timeouts and disconnects, so
  an event may be queued more than once. Deduplicate downstream if that matters.

Responses carry `X-Elastic-Product: Elasticsearch` because the exporter refuses servers without
it. They never contain invented Elasticsearch fields (`_version`, `_seq_no`, `_shards`, …).

## Credentials

Credentials are **accepted and ignored**: requests with no `Authorization` header, Basic, ApiKey,
or any other scheme are all processed the same way. The header is dropped before events are
built and is never logged. Restrict access with TLS client certificates or at the network layer.

## Not yet supported

Other Elasticsearch endpoints (`404`), Brotli/Zstd bodies (`415`), and `filter_path` expressions
beyond `took`, `errors`, `items`, `items.*`, `items.*.<field>` (`400`).

## Client setup (OpenTelemetry Collector)

Defaults work; only the endpoint is needed:

```yaml
exporters:
  elasticsearch:
    endpoint: http://logstash:9200
```

Tested versions: [docs/compatibility.md](docs/compatibility.md).

## Development

```bash
mise install                           # Java 21, Maven, JRuby
mvn package                            # unit tests, shaded JAR into vendor/jar-dependencies
jruby -S rspec spec/inputs             # Ruby adapter spec (after mvn package)
mvn verify -Pintegration               # real Logstash + exporter (Docker)
mvn verify -Pintegration,throughput    # SC-006/SC-007 performance runs (Docker)
mvn verify -Pbench                     # JMH (needs captured bodies in target/bench/exporter)
```
