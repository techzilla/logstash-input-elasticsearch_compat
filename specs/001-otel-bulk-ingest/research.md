# Research: OTel Bulk Ingest

Sources: `opentelemetry-collector-contrib` `exporter/elasticsearchexporter` at `54aa4be`
(2026-11-12; uses `go-docappender/v2` v2.15.0) and `logstash-plugins/logstash-input-http` at
`32af925` (2026-11-04).

## R1. Reference client and pinned versions

- **Decision**: OpenTelemetry Collector contrib `v0.165.1` with the `elasticsearch` exporter,
  configured with `version_detection.enabled: false`, `compression: none`,
  `discover.on_start: false`, everything else default. Logstash `9.5.6`.
- **Rationale**: Latest releases at planning time. These settings match FR-014.
- **Alternatives**: EDOT Collector — deferred; same exporter code, add to the matrix later.

## R2. Product check header

- **Decision**: Send `X-Elastic-Product: Elasticsearch` on every response (FR-015).
- **Rationale**: `esclient.go` `genuineCheckHeader` rejects the server ("not Elasticsearch")
  on the first 2xx response without it, independent of version detection.
- **Alternatives**: None; without it the exporter cannot send anything.

## R3. Exporter retry semantics (drives status codes)

From `factory.go` defaults:

- Request-level retry on `400, 401, 403, 404, 409, 429, 500, 502, 503, 504`. Not retried:
  `413`, `415`. So a malformed body (`400`) is retried up to the retry limit; harmless because
  nothing is queued.
- Item-level retry on `429, 500, 502, 503, 504`; item `400` is final.
- **Decision**:
  - Unsupported `update`/`delete` and missing index → item `400` (final, never retried).
  - Item whose `queue << event` raised → item `500` (retried; may duplicate — documented).
  - Items never attempted after a failure or during shutdown → item `503` (retried; safe, not
    queued).
  - Pool full → request `429`. Too large → `413`. `Content-Encoding` present → `415`.

## R4. Exporter traffic shape (benchmark workload)

- Batches by bytes: min 1 MB, max 5 MB, flush 10 s; sending queue size 10, block on overflow;
  default consumers 10 → up to 10 concurrent Bulk requests.
- Action per item set by the exporter (`create` for data streams), `_index` always present,
  optional `dynamic_templates`, `pipeline`, `require_data_stream`; `filter_path` default
  `items.*._index,items.*.status,items.*.failure_store,items.*.error.type,items.*.error.reason`.
- **Decision**: Implement `filter_path` only as a list of `items.*.<dotted.field>` paths (covers
  the default and any subset). Any other expression → request `400` with a clear reason.

## R5. HTTP server and threading (match `logstash-input-http`)

- **Decision**: Netty `HttpServerCodec` → reject `Content-Encoding` (415) →
  `HttpObjectAggregator(max_content_length)` (413) → handler submits to
  `ThreadPoolExecutor(threads, threads, ArrayBlockingQueue(max_pending_requests))`; rejection →
  `429`. Workers parse, call Ruby, and write the response. Defaults: `threads` = CPU cores,
  `max_pending_requests` 200, `max_content_length` 100 MB.
- **Rationale**: Proven design; Principle III sets exactly this bar. Netty stays on 4.1 to match
  `logstash-input-http` (4.1.138); moving to 4.2 needs evidence first.
- **Alternatives**: Stall detector (`RejectWhenBlockedInboundHandler`) — not needed; a full pool
  already yields immediate `429` (SC-005).

## R6. Parsing and response encoding for SC-006/SC-007

- **Decision**: Jackson streaming `JsonParser` over the aggregated `ByteBuf` (no intermediate
  `String`), validating the whole body first and building `java.util.Map`/`List` values per
  document. Integers → `Long` (`BigInteger` on overflow); floats → `Double`. Response written with
  a Jackson `JsonGenerator` straight into a pooled `ByteBuf`, applying `filter_path` while writing
  (no tree).
- **Rationale**: One pass, no tree or string copies; linear in item count.
- **Alternatives**: Jackson tree model — extra allocation; hand-rolled parser — not justified.

## R7. Java → Ruby handoff

- **Decision**: One call per request: Java passes a `java.util.List` of parsed operations to a
  Ruby object implementing a Java interface (as in `logstash-input-http`'s `IMessageHandler`). Ruby
  does `LogStash::Event.new(doc_map)`, `event.set(metadata_target, meta_map)`, `decorate`,
  `queue << event` per accepted item, and returns an `int[]` of outcomes.
- **Rationale**: No per-line Ruby callback (constitution); `Event.new` accepts a Java `Map`
  directly, avoiding Ruby hash conversion.
- **Validation task**: Confirm in Logstash 9.5.6 that `Event.new` with a Java `Map` (including
  nested maps/lists, `Long`, `BigInteger`, `Double`) is accepted without conversion overhead;
  fall back to a Java-side conversion helper if not.

## R8. Dependency isolation

- **Decision**: Shade and relocate Netty and Jackson into the plugin JAR
  (`maven-shade-plugin`), loaded with `require_jar`.
- **Rationale**: Logstash core and other plugins (beats, http) ship their own Netty/Jackson;
  relocation prevents version clashes in the shared JRuby classloader.

## R9. Toolchain and testing

- **Decision**: Java 21 (Logstash 9 bundles JDK 21), Maven 3.9, and JRuby (version matched to Logstash 9.5.6's bundled JRuby), installed via `mise.toml`.
  JUnit 5 unit tests; Testcontainers integration tests running real `logstash:9.5.6` (memory and
  persistent queue) and `otelcol-contrib:0.165.1` with `telemetrygen` as log source; JMH
  microbenchmarks for SC-007; a throughput harness for SC-006 replaying captured exporter bodies
  at 10 concurrent senders against this plugin, and the same documents as `json_lines` against
  `logstash-input-http`.
- **Rationale**: One test language (Java) across tiers; Docker is available locally.
- **Alternatives**: Ruby specs via `logstash-devutils` — needs a Logstash source checkout; the
  Ruby adapter is thin and fully exercised by integration tests.
