# logstash-input-elasticsearch_compat — Technical Specification

**Status:** Revised design specification  
**Revision:** 0.2 — 2026-11-05  
**Implementation:** Ruby Logstash input plugin with Java/Netty backend  
**Initial compatibility target:** OpenTelemetry Collector Elasticsearch exporter, including Elastic EDOT Collector  

## 1. Purpose

Provide an HTTP ingestion endpoint that accepts Elasticsearch Bulk API requests and emits corresponding Logstash events. It is intended for applications and collectors already configured to send telemetry to Elasticsearch, particularly the OpenTelemetry Collector's `elasticsearch` exporter and clients built on `go-docappender`.

The plugin is a **protocol-compatible ingestion receiver**, not an Elasticsearch node. Its successful acknowledgment means that a Logstash queue submission completed normally; it does **not** certify indexing, downstream output delivery, or durable disk checkpointing.

The plugin prioritizes **permissive ingestion**: accept structurally valid documents that can be represented as Logstash events, tolerate unfamiliar Elasticsearch options, and ignore irrelevant Elasticsearch-specific semantics. Do not validate Elasticsearch mappings, data stream existence, shard state, or index privileges.

### Non-goals

- Search/query, index management, mappings, templates, ingest pipelines, ILM, shards, or persistent document storage.
- Elasticsearch document-existence checks, version conflicts, optimistic concurrency, or true `create` uniqueness.
- Emulating a general Elasticsearch cluster or implementing arbitrary Elasticsearch client APIs.
- Exactly-once ingestion, transactional Bulk requests, or acknowledgments from downstream Logstash outputs.
- A native Logstash Java Input API implementation or Java codec plugin.

## 2. Implementation architecture

**Decided:** implement a conventional Ruby Logstash input plugin, packaged as a Ruby gem, with nearly all functionality in a regular Java library. Do not compile against `logstash-core.jar` or depend on `co.elastic.logstash.api`.

```text
Elasticsearch-compatible clients
             |
         HTTP / TLS
             v
+-----------------------------------------+
| Java / Netty HTTP backend               |
|  - connection handling and authentication|
|  - endpoint routing and request limits  |
|  - gzip decoding / Bulk NDJSON parsing  |
|  - operation metadata extraction        |
|  - request staging and admission bounds |
|  - Bulk response encoding / filter_path |
+-------------------+---------------------+
                    |
        bounded request/batch handoff
                    v
+-----------------------------------------+
| Thin Ruby Logstash input adapter        |
|  - configuration and lifecycle          |
|  - LogStash::Event creation             |
|  - standard event decoration            |
|  - queue << event                       |
|  - submission outcomes returned to Java |
+-------------------+---------------------+
                    |
          Logstash pipeline queue
            (memory or persistent)
                    |
              filters / outputs
```

### 2.1 Java responsibilities

Java owns the HTTP server, TLS, authentication checks, request routing, bounded I/O buffering, Bulk framing and parsing, gzip handling, request validation, index resolution, metadata extraction, ordered response assembly, HTTP error encoding, and low-cardinality operational counters. Java must not import Logstash-internal classes.

The Java side prepares a bounded batch of parsed records and returns an outcome-aware HTTP response after the Ruby adapter has attempted submission. Handoff occurs **per request or bounded batch**, not through a Ruby callback for every line of NDJSON.

### 2.2 Ruby responsibilities

Ruby owns only the documented Logstash input lifecycle, configuration integration, `LogStash::Event` creation, `decorate(event)`, and insertion with `queue << event`. Ruby returns the known result of each attempted event submission to Java. Ordinary Logstash input options such as `tags`, `add_field`, `id`, and plugin metrics must remain functional.

A default codec declaration may be necessary to satisfy Logstash input conventions, but **Bulk parsing does not use the codec interface**. `logstash-codec-es_bulk` is an optional behavioral reference, not a runtime dependency.

### 2.3 Threading and backpressure

Netty event-loop threads must not perform blocking Logstash queue writes. Use a **bounded** handoff to one or more Ruby/JRuby ingestion worker threads, with concurrency verified in integration tests. Do not assume additional worker threads improve throughput before benchmarking.

The Java backend limits simultaneous requests, body sizes, decompressed sizes, and total pending bytes. At capacity, refuse **new work before queue submission** with HTTP `429` or `503` as appropriate. A blocked Logstash `queue << event` operation has no assumed nonblocking `tryOffer` or guaranteed cancellation/timeout mechanism.

## 3. Supported HTTP protocol

### 3.1 Initial Bulk routes

| Method | Path | Behavior |
| --- | --- | --- |
| `POST`, `PUT` | `/_bulk` | Process Bulk NDJSON |
| `POST`, `PUT` | `/{index}/_bulk` | Use URL index when an action omits `_index` |
| `GET`, `HEAD` | `/` | Minimal identity/connectivity behavior **only where needed by tested clients** |

Do not implement node discovery, cluster health, or other Elasticsearch endpoints until a specific supported client requires them. Unknown routes return `404`; unsupported methods return `405` where appropriate.

### 3.2 Transport and encoding

- HTTP/1.1 keep-alive, `Content-Length`, and chunked transfer encoding.
- UTF-8 Bulk NDJSON with `application/x-ndjson` and `application/json` media types.
- Support gzip request bodies when required by supported exporters; bound both compressed and expanded bytes.
- Enforce body, header, connection, request-count, and aggregate in-flight memory limits; select exact defaults through tests rather than guesswork.
- Handle request cancellation and disconnects without claiming that already-queued events were undone.

### 3.3 Server identity

Version detection is enabled by default in the OpenTelemetry Elasticsearch exporter; the exporter can also disable it. The initial release must be tested with the pinned exporter **in its selected supported configuration**. If its default configuration requires `GET /`, implement the smallest empirically compatible identity response. Do **not** invent Lucene versions, shard counts, cluster members, or a broad menu of Elasticsearch compatibility profiles. Any `X-Elastic-Product` header or synthetic identity must be explicitly documented as compatibility behavior and tested against the target client.

## 4. Bulk request parsing

### 4.1 Framing

The body contains an action-and-metadata JSON line followed, for `index`, `create`, and `update`, by a source line. `delete` has no source line. A final newline is required, consistent with Elasticsearch Bulk framing. Parse the input as NDJSON, never as a single JSON array.

Validate framing, source JSON, UTF-8, and resource limits before submitting any event from that request, to avoid partially accepting a structurally malformed request. This may require a bounded staged body or a two-pass parser; **the staging mechanism is an implementation choice**. It must not buffer without bound.

### 4.2 Action behavior

| Action | Initial behavior |
| --- | --- |
| `index` | Emit one Logstash event from the source document |
| `create` | Emit one Logstash event; do not attempt duplicate-ID detection |
| `update` | Parse framing; reject **that item** as unsupported rather than misrepresent a partial update as a full document |
| `delete` | Parse framing; reject **that item** as unsupported rather than silently discard a deletion |
| Unknown action | Reject request if its framing cannot be interpreted safely |

Recognized but unsupported actions in an otherwise structurally valid request may generate item-level errors while supported actions are admitted. Do not silently report success for discarded `update` or `delete` operations.

### 4.3 Permissive parameter policy

Do not reject `index`/`create` merely because a request includes Elasticsearch options the receiver does not implement. In particular, tolerate and ignore the semantics of `refresh`, `wait_for_active_shards`, `require_alias`, `require_data_stream`, `timeout`, mapping hints, and ingest-pipeline selection. Accept unknown optional query and action-metadata fields unless they interfere with framing, security, resource limits, or protocol correctness.

Treat `filter_path` differently: it affects the **HTTP response** and must be honored for the patterns used by supported exporters. Unsupported response-filter expressions must not silently produce a misleading response; return a clear error or implement them as client tests require.

### 4.4 Index and metadata

Resolve the requested target in this order: action `_index`, URL `/{index}/_bulk`, then no resolved target. For initial Elasticsearch Bulk compatibility, an operation lacking a resolvable target yields an item-level error; the plugin does not invent an index. Keep this rule isolated so a later explicitly configured fallback may be added without changing parsing.

Preserve, at minimum, the action name, resolved index, and original `_id` if present. Preserve `_routing` and `pipeline` when present as informative metadata; do **not** execute those semantics. Unknown metadata is tolerated and normally ignored. Preserve original document fields/nesting as far as the `LogStash::Event` model permits.

Place routing information in `[@metadata][elasticsearch_compat]`:

```text
[@metadata][elasticsearch_compat][action]
[@metadata][elasticsearch_compat][index]
[@metadata][elasticsearch_compat][id]       # only if supplied
[@metadata][elasticsearch_compat][routing]  # optional
[@metadata][elasticsearch_compat][pipeline] # optional
```

Do not overwrite application document fields to add routing or HTTP metadata. `@metadata` is normally excluded from downstream output serialization; pipelines forwarding to Kafka must deliberately promote any fields that Kafka consumers require. Test reserved fields and Logstash-specific coercions such as `@timestamp` rather than promising exact byte-for-byte JSON preservation.

## 5. Acknowledgments and failure behavior

### 5.1 Successful responses

For a structurally valid Bulk request, return Elasticsearch-shaped JSON with `took`, `errors`, and ordered `items`, unless a supported `filter_path` intentionally removes fields. Every item has its original operation name, a numeric status, and applicable `_index`/`_id` values. A normal successful queue submission may use `201` for `index` and `create` as an ingestion acknowledgment convention; it does not imply an Elasticsearch document was actually created.

Example, before response filtering:

```json
{
  "took": 6,
  "errors": false,
  "items": [
    {"create": {"_index": "logs-app-default", "_id": "123", "status": 201}},
    {"index":  {"_index": "logs-app-default", "_id": "124", "status": 201}}
  ]
}
```

Never invent `_version`, `_seq_no`, `_primary_term`, shard totals, storage result, or a generated ID merely to look like Elasticsearch. If a target client requires additional fields, add only those whose meaning can be justified or clearly specified as a compatibility placeholder.

### 5.2 Admission boundary

The Ruby adapter reports success only after `queue << event` completes normally. That establishes **submission completion**, not downstream output completion or a guaranteed durable disk checkpoint. With a persistent queue, disk durability depends on Logstash's actual persistence/checkpoint behavior and configuration.

When the input is already at its configured request-admission limit, send request-level `429` before admitting documents. In an otherwise valid request, unsupported actions can receive item-level `400` responses. If an exception occurs after some submissions, report only known outcomes and treat uncertain ones as **ambiguous**, never as guaranteed unqueued. A failed HTTP response, disconnect, or timeout may cause retries and duplicates.

Do not specify a `queue_write_timeout` or promise precise per-item `429` results until a supported, tested implementation actually exists. Never blanket-reject a partially admitted request while implying that none of its events reached Logstash.

### 5.3 Response filtering

The OpenTelemetry Elasticsearch exporter commonly requests:

```text
items.*._index,items.*.status,items.*.failure_store,items.*.error.type,items.*.error.reason
```

Generate the logical result first, then apply the supported `filter_path` expressions. Test response shape with the **actual** `go-docappender`/exporter decoder, for both successes and failures. A full arbitrary Elasticsearch response-filter expression engine is not part of the initial scope.

### 5.4 Error categories

| Situation | Initial response |
| --- | --- |
| Valid Bulk request, all accepted | HTTP `200`, `errors:false` |
| Valid Bulk request, known item failures | HTTP `200`, `errors:true`, ordered item errors |
| Malformed framing / invalid JSON | HTTP `400`, before any queue submission |
| Missing/invalid credentials | HTTP `401` or `403` |
| Too large / unsupported encoding | HTTP `413` / `415` |
| Admission capacity exhausted **before** submission | HTTP `429` (retryable) |
| Receiver shutting down/unavailable | HTTP `503` when safe to respond |
| Unexpected internal failure | HTTP `500`, with honest treatment of ambiguous submissions |

## 6. Security and operational controls

Support HTTPS/TLS and configurable certificate/key files, HTTP Basic authentication, and static receiver-side API-key authentication using the Elasticsearch `Authorization: ApiKey ...` form. These keys identify callers to **this receiver**; they are not Elasticsearch-issued privilege grants. Support mTLS later if needed by a selected deployment.

Require bounded in-flight data, request and header limits, decompression safety, and a bounded pending-request queue. Authenticate before fully parsing large bodies where practical. Never log document bodies, passwords, API keys, or authorization headers by default. Keep operational metrics low-cardinality.

Expose the normal Logstash monitoring metrics. A small plugin readiness endpoint can be added only if deployment health checks require one; do not implement synthetic Elasticsearch cluster health for v1.

## 7. Initial configuration surface

These are proposed setting names; defaults for throughput-sensitive limits must be established by testing.

| Setting | Purpose |
| --- | --- |
| `host`, `port` | Listener address and port |
| `ssl_enabled`, `ssl_certificate`, `ssl_key` | HTTPS configuration |
| `auth_type`, `user`, `password`, `api_keys` | Receiver-side authentication |
| `max_content_length` | Maximum compressed/uncompressed request wire body as applicable |
| `max_uncompressed_content_length` | Hard cap after decompression |
| `max_pending_requests`, `max_pending_request_bytes` | Bounded staging |
| `max_bulk_actions` | Per-request operation limit |
| `metadata_target` | Metadata field reference; default `[@metadata][elasticsearch_compat]` |

Keep optional settings out of the public interface until there is a demonstrated need. Specifically defer compatibility-profile selection, fallback indexes, synthetic health endpoints, node discovery, configurable admission timeouts, advanced authorization, and arbitrary metadata-preservation toggles.

Example **illustrative** Logstash configuration (validate setting spellings/defaults during implementation):

```ruby
input {
  elasticsearch_compat {
    id => "bulk_receiver"
    host => "0.0.0.0"
    port => 9200
    ssl_enabled => true
    ssl_certificate => "/etc/logstash/certs/receiver.crt"
    ssl_key => "/etc/logstash/certs/receiver.key"
    auth_type => "basic"
    user => "${ES_COMPAT_USER}"
    password => "${ES_COMPAT_PASSWORD}"
  }
}
```

The plugin supports both Logstash memory and persistent queues through the standard input queue API. It does not implement its own durable queue.

## 8. Build, packaging, and compatibility

- Distribute as a normal `logstash-input-elasticsearch_compat` Ruby gem containing the Ruby adapter and a precompiled Java JAR.
- Use Maven for the Java backend's dependencies, compilation, and tests; use JRuby/Bundler and gem tooling for the Ruby adapter and final gem packaging. No native Logstash Java API dependency or rebuilt Logstash core JAR is necessary.
- Keep the Java library isolated from Logstash classes; integrate through a small JRuby-facing adapter.
- Test **the same gem** against each explicitly supported Logstash version; do not claim binary/source compatibility across major versions without those tests.
- Use dependency lock/pinning and vulnerability checks for Netty and the JSON parser.
- The existing `logstash-input-http` implementation is a design reference; do not depend on its internal or undocumented classes.

## 9. Testing and acceptance criteria

### 9.1 Java unit tests

Cover Bulk line framing (including `delete`/`update`), invalid JSON/UTF-8, final newline rules, mixed indexes, metadata preservation, unknown parameters, gzip and decompression caps, header limits, response ordering, error serialization, and the tested `filter_path` patterns.

### 9.2 Integration tests

Run real Logstash instances with memory and persistent queues. Test empty and saturated pipeline conditions, client disconnections, blocked queue writes, graceful shutdown, and attempts to exceed request/concurrency/memory limits. Verify that Java HTTP threads remain responsive while Logstash queue writes block.

### 9.3 Real client compatibility

Pin at least one upstream OpenTelemetry Collector Elasticsearch exporter version and one EDOT Collector release where needed. Test the real exporter and `go-docappender` behavior for startup probing, successful Bulk ingestion, mixed supported/unsupported actions, response filtering, HTTP-level retryable overload, partial item errors, index resolution, authentication, and compression. Record the exact supported versions in a compatibility matrix.

Other Elasticsearch clients, syslog-ng, and custom integrations are **best-effort** until end-to-end tests for named versions have passed.

### 9.4 v1 acceptance

1. The plugin installs as a standard gem and runs on supported Logstash releases.
2. The pinned real exporter connects and sends valid requests without response-decoding errors.
3. `index` and `create` each produce exactly one event with correct document fields and routing metadata.
4. Valid but irrelevant Elasticsearch parameters do not cause rejection.
5. Unsupported `update`/`delete` actions are not falsely acknowledged as successful indexing.
6. HTTP responses preserve operation order and work with the exporter's default `filter_path`.
7. Accepted items are acknowledged only after normal completion of queue submission.
8. Overload is bounded; request-level pre-admission rejection is retryable.
9. Memory and persistent queue paths, TLS/authentication, shutdown, and client retry behavior pass integration tests.
10. Limitations and duplication risks are documented.

## 10. Implementation sequence

**Milestone A — working vertical slice.** Java Netty listener; `POST /_bulk`; Java parser; Ruby batch adapter; event/metadata creation; queue submission; proper Bulk success response; one pinned OTel exporter test.

**Milestone B — production receiver.** Other Bulk routes/methods; request and gzip limits; bounded admission/backpressure; TLS and authentication; supported `filter_path`; memory/PQ tests; shutdown behavior; metrics.

**Milestone C — additional demonstrated compatibility.** Add only features required by failing real-client tests (identity endpoint, request options, or further response fields). Maintain a named client/version compatibility matrix.

## 11. Open validation items (not assumed features)

1. **Ruby handoff:** Confirm safe JRuby worker-thread behavior and best request/batch size for queue submission.
2. **Queue failures:** Confirm exactly what a returned versus failed `queue << event` means for each supported Logstash version/queue type; do not assume nonblocking try-write or hard timeout.
3. **Exporter identity:** Record the actual initial version/probe requests for each pinned client; implement only required responses.
4. **Response decoder:** Verify required item fields, ID omission behavior, `filter_path`, and retry classification against real clients.
5. **Event conversion:** Test `@timestamp`, `@metadata`, special source fields, large/nested JSON, and binary/number precision edge cases.
6. **Production bounds:** Derive limits and worker counts from load tests rather than embedding speculative defaults.

**Design rule:** Add API surface and implementation complexity only when required by the Bulk protocol, safe operation, or a demonstrated client behavior.

## References

- Logstash input plugin development: https://www.elastic.co/docs/extend/logstash/input-new-plugin
- Logstash HTTP input: https://www.elastic.co/docs/reference/logstash/plugins/plugins-inputs-http
- Elasticsearch Bulk API: https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-bulk
- OpenTelemetry Collector Elasticsearch exporter: https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/exporter/elasticsearchexporter/README.md
- Elastic EDOT Collector Elasticsearch exporter: https://www.elastic.co/docs/reference/edot-collector/components/elasticsearchexporter
