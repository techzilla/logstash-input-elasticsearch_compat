---

description: "Task list for OTel Bulk Ingest"
---

# Tasks: OTel Bulk Ingest

**Input**: Design documents from `specs/001-otel-bulk-ingest/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Required. The constitution's Quality Gates require unit, real-Logstash, and
real-client tests, and SC-001–SC-007 are verified by tests. Write each story's tests first and
confirm they fail.

**Organization**: Tasks are grouped by user story so each story can be built and tested on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: User story from spec.md (US1, US2, US3)

## Path Conventions

- Java main: `src/main/java/org/logstash/plugins/inputs/escompat/` (abbreviated `J/` below)
- Java unit tests: `src/test/java/org/logstash/plugins/inputs/escompat/` (`JT/`)
- Integration tests: `src/it/java/org/logstash/plugins/inputs/escompat/it/` (`IT/`), resources in
  `src/it/resources/`
- Benchmarks: `src/jmh/java/org/logstash/plugins/inputs/escompat/bench/` (`JMH/`)
- Ruby: `lib/logstash/inputs/`; Ruby specs: `spec/inputs/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Toolchain, build, and gem skeleton

- [ ] T001 Add `java = "temurin-21"`, `maven = "3.9"`, and `ruby = "jruby-<X>"` to `[tools]` in `mise.toml`, where `<X>` is the JRuby version reported by `docker run --rm docker.elastic.co/logstash/logstash:9.5.6 /usr/share/logstash/vendor/jruby/bin/jruby -v`; record the version in research.md R9
- [ ] T002 Create `pom.xml`: groupId `org.logstash.plugins.inputs`, artifactId `logstash-input-elasticsearch_compat`, version read from `VERSION`, `maven.compiler.release` 21; dependencies `io.netty:netty-codec-http` (latest 4.1.x) and `com.fasterxml.jackson.core:jackson-core` (latest 2.x) pinned; test deps JUnit 5, Testcontainers, `org.openjdk.jmh`; `maven-shade-plugin` relocating `io.netty` → `org.logstash.plugins.inputs.escompat.shaded.netty` and `com.fasterxml.jackson` → `org.logstash.plugins.inputs.escompat.shaded.jackson`, excluding Netty native-transport artifacts; copy the shaded JAR to `vendor/jar-dependencies/org/logstash/plugins/inputs/logstash-input-elasticsearch_compat/${version}/` at `package`
- [ ] T003 Add Maven profiles to `pom.xml`: `integration` (build-helper adds `src/it/java` + `src/it/resources`, failsafe runs `*IT` excluding `*ThroughputIT` and `Capture*IT`), `bench` (adds `src/jmh/java`, runs JMH via exec plugin), `throughput` (failsafe runs `CaptureExporterBodiesIT` then `*ThroughputIT`), `audit`; in `integration` and `throughput`, `exec-maven-plugin` runs `jruby -S gem build logstash-input-elasticsearch_compat.gemspec` in `pre-integration-test` and moves the gem to `target/` (`org.owasp:dependency-check-maven` failing on CVSS ≥ 7)
- [ ] T004 [P] Create `VERSION` (`0.1.0`), `Gemfile`, and `logstash-input-elasticsearch_compat.gemspec` (platform `java`, metadata `logstash_plugin: true`, `logstash_group: input`, runtime dep `logstash-core-plugin-api` `~> 2.0`, dev dep `rspec`; files include `lib/**/*` and `vendor/jar-dependencies/**/*.jar`)
- [ ] T005 [P] Create `lib/logstash-input-elasticsearch_compat_jars.rb` calling `require_jar('org.logstash.plugins.inputs', 'logstash-input-elasticsearch_compat', <VERSION>)`
- [ ] T006 [P] Add `target/`, `vendor/jar-dependencies/`, `*.gem`, `.bundle/` to `.gitignore`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared types, HTTP server skeleton, Ruby lifecycle, and test harness

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T007 Spike (research R7): in a `logstash:9.5.6` container, run a JRuby snippet that calls `LogStash::Event.new` with a `java.util.LinkedHashMap` containing nested `LinkedHashMap`/`ArrayList`, `Long`, `BigInteger`, `Double`, `Boolean`, `null`, and an `@timestamp` string; record in research.md R7 whether it is accepted as-is, and if not, add a Java-side conversion helper task to US1
- [ ] T008 [P] Create `J/bulk/Action.java` enum `INDEX, CREATE, UPDATE, DELETE` with `wireName()` (`"index"`, …) and `fromWireName(String)` returning null for unknown
- [ ] T009 [P] Create `J/bulk/ItemOutcome.java` enum per data-model.md: `QUEUED` 201; `UNSUPPORTED_ACTION` 400 `illegal_argument_exception`; `MISSING_INDEX` 400 `action_request_validation_exception`; `SUBMIT_FAILED` 500 `exception`; `NOT_ATTEMPTED` 503 `unavailable_exception`; each with `status()`, `errorType()`, `reason(Action)`
- [ ] T010 [P] Create `J/bulk/Operation.java` (final fields `action`, `index`, `id`, `routing`, `pipeline` all nullable `String` except `action`; `Map<String,Object> document` null for `delete`/`update`; `ItemOutcome preStatus` null unless pre-rejected) with getters used from Ruby
- [ ] T011 [P] Create `J/BatchHandler.java` interface: `ItemOutcome[] submit(List<Operation> ops)` (one entry per op, same order; ops with non-null `preStatus` are passed through untouched)
- [ ] T012 Create `J/server/Responses.java`: build `FullHttpResponse` with `X-Elastic-Product: Elasticsearch` on every response (FR-015), JSON error body `{"error":{"type":…,"reason":…},"status":N}`, `Content-Length`, keep-alive handling
- [ ] T013 Create `J/server/EsCompatServer.java`: NIO boss (1) + worker event loops, `ThreadPoolExecutor(threads, threads, 0, ArrayBlockingQueue(maxPendingRequests))` with a rejection handler hook, `AUTO_READ=false` until the pool is started, `run()` blocking until `close()`, `close()` shutting down the listener then the pool (5 s grace); constructor args `host, port, threads, maxPendingRequests, maxContentLength, BatchHandler`
- [ ] T014 Create `J/server/HttpInitializer.java` and `J/server/RoutingHandler.java`: pipeline `HttpServerCodec` → `RoutingHandler` (only `POST /_bulk` passes; other paths `404`, other methods on `/_bulk` `405`, via `Responses`; after an early error response, discard the rest of the request body, or close the connection if it exceeds `maxContentLength`) → (encoding check slot, T043) → `HttpObjectAggregator(maxContentLength)` → placeholder bulk handler returning `200 {"took":0,"errors":false,"items":[]}`
- [ ] T015 [P] Create `JT/server/RoutingHandlerTest.java` with Netty `EmbeddedChannel`: `GET /` → 404, `PUT /_bulk` → 405, `POST /x/_bulk` → 404, every response has `X-Elastic-Product: Elasticsearch`
- [ ] T016 Create `lib/logstash/inputs/elasticsearch_compat.rb`: `config_name "elasticsearch_compat"`, `default :codec, "plain"`; settings per contracts/plugin-config.md (`host` "0.0.0.0", `port` 9200, `threads` CPU cores, `max_pending_requests` 200, `max_content_length` 104857600, `metadata_target` "[@metadata][elasticsearch_compat]"); `register` builds `EsCompatServer`; `run(queue)` stores queue and calls `server.run`; `stop`/`close` call `server.close` (rescue nil)
- [ ] T017 Create `lib/logstash/inputs/elasticsearch_compat/batch_handler.rb` implementing `BatchHandler` as a stub that returns `QUEUED` for every op without a `preStatus`
- [ ] T018 [P] Create `IT/support/LogstashContainer.java`: Testcontainers image from `docker.elastic.co/logstash/logstash:9.5.6` with the built gem installed via `bin/logstash-plugin install --local`, pipeline file mounted from `src/it/resources/pipelines/`, queue type parameter (`memory`/`persisted`), extra environment variables (e.g. `PIPELINE_WORKERS`, `PIPELINE_BATCH_SIZE`), exposed 9200 and 9600, `/out` mounted to a temp dir writable by uid 1000; gem taken from `target/*.gem` (built by T003); helper `readEvents()` parsing `/out/events.ndjson`
- [ ] T019 [P] Create `src/it/resources/pipelines/capture.conf`: input `elasticsearch_compat { id => "it_input" port => 9200 tags => ["t1"] add_field => {"f1" => "v1"} }` and a second input `elasticsearch_compat { port => 9201 metadata_target => "[@metadata][custom]" }` (filter copies it to `[_custom]`), filter `mutate { copy => { "[@metadata][elasticsearch_compat]" => "[_meta]" } }`, output `file { path => "/out/events.ndjson" codec => json_lines }`
- [ ] T020 [P] Create `IT/support/OtelCollectorContainer.java` and `src/it/resources/otelcol/config.yaml`: `otelcol-contrib:0.165.1`, OTLP receiver, `elasticsearch` exporter with `endpoint` = Logstash container, `version_detection.enabled: false`, `compression: none`, `discover.on_start: false`, other settings default (research R1); collector internal metrics exposed on `:8888`; plus a `ghcr.io/open-telemetry/opentelemetry-collector-contrib/telemetrygen:v0.165.1` container sending OTLP to the collector
- [ ] T021 Create `IT/SmokeIT.java`: Logstash starts with `capture.conf`, `POST /_bulk` with `{"index":{"_index":"a"}}\n{}\n` returns 200 with `X-Elastic-Product` (verifies gem packaging and JAR loading end to end)

**Checkpoint**: Plugin loads in real Logstash and answers `POST /_bulk`

---

## Phase 3: User Story 1 - Exporter ships telemetry into Logstash (Priority: P1) 🎯 MVP

**Goal**: Each `index`/`create` becomes exactly one event with routing metadata; the exporter
accepts the response.

**Independent Test**: `ExporterIngestIT` — pinned exporter → plugin; every log record arrives as
one event with correct fields and metadata, zero exporter errors (SC-001, SC-002).

### Tests for User Story 1 ⚠️

- [ ] T022 [P] [US1] Create `JT/bulk/BulkParserTest.java` (happy paths): `index` and `create` with `_index`, `_id`, `routing`, `pipeline` mapped to `Operation`; missing `_index` → `preStatus` `MISSING_INDEX`; ignored action keys `dynamic_templates`, `require_alias`, `require_data_stream`, `version`; nested objects/arrays preserved in order; integer → `Long`, > `Long.MAX_VALUE` → `BigInteger`, float → `Double`; 1,000-op body parses to 1,000 ops in order
- [ ] T023 [P] [US1] Create `JT/response/FilterPathTest.java`: parses `items.*._index,items.*.status,items.*.failure_store,items.*.error.type,items.*.error.reason`; parses subsets; `took`/`errors` excluded unless listed; matching of nested `error.type`
- [ ] T024 [P] [US1] Create `JT/response/BulkResponseWriterTest.java`: items in request order keyed by original action name; `status` 201 for `QUEUED`; `_index` when resolved; `_id` only when supplied; `errors` false when all < 300; output never contains `_version`, `_seq_no`, `_primary_term`, `_shards`, `result`; with the exporter's default `filter_path` output equals the expected JSON byte for byte
- [ ] T025 [P] [US1] Create `IT/BulkIngestIT.java` (parameterized memory/persisted): N=3 mixed `index`/`create` items → 3 events, `_meta` holds `action`, `index`, `id` only when supplied; document fields unchanged; `tags` has `t1` and `f1` = `v1`; unknown query params (`refresh=true&timeout=1m&pipeline=x`) ignored; requests with no `Authorization`, `Basic`, and `ApiKey` headers all succeed identically and no event, log line, or response contains the credential value; node stats list the input under `it_input`; the `:9201` input puts metadata at `[_custom]`; reserved fields per contracts/plugin-config.md: valid `@timestamp` used, invalid one tagged `_timestampparsefailure` with value in `_@timestamp`, missing one gets ingest time, a document's own `@metadata` is kept except the target subkey
- [ ] T026 [P] [US1] Create `IT/ExporterIngestIT.java` (parameterized memory/persisted): `telemetrygen logs --logs 1000` → otelcol → plugin; assert 1,000 events, each `_meta.action` = `create` and `_meta.index` non-empty; from the collector's `:8888/metrics`, `otelcol_exporter_sent_log_records` = 1000 and `otelcol_exporter_send_failed_log_records` = 0

### Implementation for User Story 1

- [ ] T027 [US1] Implement `J/bulk/BulkParser.java`: `List<Operation> parse(ByteBuf body)` using a Jackson `JsonFactory` streaming `JsonParser` over a `ByteBufInputStream` (no `String` copy); per line read the action object (one key, `Action.fromWireName`), its metadata (`_index`, `_id`, `routing`/`_routing`, `pipeline`; skip all other keys with `skipChildren`), then the source object into `LinkedHashMap`/`ArrayList` with numbers as `Long`/`BigInteger`/`Double`; set `preStatus` `MISSING_INDEX` when `_index` is absent (FR-004)
- [ ] T028 [US1] Implement `J/response/FilterPath.java`: `static FilterPath parse(String)` for comma-separated `items.*.<dotted.field>` paths; `includes(String... path)`; `FilterPath.ALL` when the parameter is absent
- [ ] T029 [US1] Implement `J/response/BulkResponseWriter.java`: `ByteBuf write(List<Operation>, ItemOutcome[], long tookMillis, FilterPath, ByteBufAllocator)` using a Jackson `JsonGenerator` on a pooled `ByteBufOutputStream`, emitting only fields allowed by `FilterPath`, per contracts/http-bulk.md
- [ ] T030 [US1] Implement `J/server/BulkRequestHandler.java` replacing the placeholder: on `FullHttpRequest`, remove `Authorization` (FR-011), parse `filter_path` from the query string (ignore all other params, FR-010), retain the request and submit a task to the pool; the task parses, calls `BatchHandler.submit`, writes the response via `BulkResponseWriter` and `Responses` with `Content-Type: application/json`, and releases buffers in `finally`
- [ ] T031 [US1] Implement `lib/logstash/inputs/elasticsearch_compat/batch_handler.rb`: for each op without `preStatus`, `event = LogStash::Event.new(op.document)`, `event.set(@metadata_target, meta)` where `meta` has `"action"`, `"index"`, and `"id"`, `"routing"`, `"pipeline"` only when non-nil (FR-003), `decorate(event)`, `@queue << event`, outcome `QUEUED`; return a Java `ItemOutcome[]`
- [ ] T032 [US1] Wire `BulkRequestHandler` into `J/server/HttpInitializer.java` and pass the Ruby `BatchHandler` from `register` in `lib/logstash/inputs/elasticsearch_compat.rb`; ensure no Logstash class is imported anywhere under `J/`

**Checkpoint**: US1 passes `BulkIngestIT` and `ExporterIngestIT` on both queue types

---

## Phase 4: User Story 2 - Unsupported and malformed input is never falsely acknowledged (Priority: P2)

**Goal**: `update`/`delete` get item errors, malformed requests queue nothing, partial failures
report only known outcomes.

**Independent Test**: `TruthfulAckIT` with mixed and malformed bodies sent directly
(SC-003, SC-004).

### Tests for User Story 2 ⚠️

- [ ] T033 [P] [US2] Extend `JT/bulk/BulkParserTest.java` (failure paths): `update` consumes its source line and gets `UNSUPPORTED_ACTION`; `delete` has no source line and gets `UNSUPPORTED_ACTION`; request-level `BulkParseException` for: invalid JSON, invalid UTF-8, missing final newline, empty body, action line with ≠ 1 key, unknown action name, action without source line, non-object source line, `delete` followed by a stray source line
- [ ] T034 [P] [US2] Extend `JT/response/BulkResponseWriterTest.java`: `UNSUPPORTED_ACTION`/`MISSING_INDEX` → 400 with `error.type`/`error.reason`; `SUBMIT_FAILED` → 500; `NOT_ATTEMPTED` → 503; `errors` true when any item ≥ 300; unsupported `filter_path` (e.g. `took,hits.*`) → `FilterPath.parse` throws, handler returns request `400`
- [ ] T035 [P] [US2] Create `spec/inputs/batch_handler_spec.rb` (plain JRuby + rspec, run after `mvn package`, `require`s the shaded JAR from `target/` for `ItemOutcome`/`BatchHandler`; fake queue and injected event factory): a queue that raises on the 2nd push yields `[QUEUED, SUBMIT_FAILED, NOT_ATTEMPTED, …]`; ops with `preStatus` pass through unchanged; after `stop!` every remaining op is `NOT_ATTEMPTED`
- [ ] T036 [P] [US2] Create `IT/TruthfulAckIT.java`: mixed `index`/`update`/`delete` → `index` 201, others 400, order kept, 1 event; each malformed case from T033 → request 400 and 0 events

### Implementation for User Story 2

- [ ] T037 [US2] Add whole-body validation to `J/bulk/BulkParser.java`: throw `J/bulk/BulkParseException.java` (with reason) for every case in T033 before returning, so nothing is submitted (FR-006); `update` reads and discards its source; `delete` sets no document; both get `UNSUPPORTED_ACTION` (FR-005)
- [ ] T038 [US2] In `J/server/BulkRequestHandler.java`, map `BulkParseException` and invalid `filter_path` to request `400` `{"error":{"type":"parse_exception","reason":…},"status":400}` via `Responses`
- [ ] T039 [US2] In `lib/logstash/inputs/elasticsearch_compat/batch_handler.rb`, rescue any exception from building/pushing an event: that op → `SUBMIT_FAILED`, all later submittable ops → `NOT_ATTEMPTED`; log the exception class and message without the document; refactor event creation behind an injectable factory for T035

**Checkpoint**: US1 and US2 pass independently

---

## Phase 5: User Story 3 - Overload is refused safely (Priority: P3)

**Goal**: Excess load gets retryable refusals before anything is queued; the listener stays
responsive.

**Independent Test**: `OverloadIT` with a blocked pipeline (SC-004, SC-005).

### Tests for User Story 3 ⚠️

- [ ] T040 [P] [US3] Create `JT/server/LimitsTest.java` with `EmbeddedChannel`: body > `maxContentLength` → 413 with `X-Elastic-Product`; `Content-Encoding: gzip` → 415; pool rejection → 429; all with JSON error bodies and the handler never called
- [ ] T041 [P] [US3] Create `src/it/resources/pipelines/blocked.conf` (`threads => 1`, `max_pending_requests => 2`, filter `sleep { time => 3600 }`) and `IT/OverloadIT.java`, starting Logstash with env `PIPELINE_WORKERS=1`, `PIPELINE_BATCH_SIZE=1`: fire 20 concurrent requests; at least one gets 429 within 1 s (SC-005); refused requests add 0 events; a 413 request adds 0 events; disconnect case: send a 3-item request, close the socket while item 1 is blocked, then unblock (restart the pipeline without `sleep`) and assert item 1 appears exactly once, the listener still serves, and nothing above DEBUG is logged for the disconnect
- [ ] T042 [P] [US3] Create `IT/ShutdownIT.java`: with `blocked.conf`, start a request, stop Logstash; the response (if delivered) marks un-pushed items 503, and a new request during shutdown gets 503 or connection refused

### Implementation for User Story 3

- [ ] T043 [US3] Add `J/server/ContentEncodingHandler.java` in the T014 slot (after `RoutingHandler`, before the aggregator) in `J/server/HttpInitializer.java`, discarding the body after responding as in T014: any `Content-Encoding` other than `identity` → 415 (FR-014)
- [ ] T044 [US3] Subclass `HttpObjectAggregator` in `J/server/BoundedAggregator.java` overriding `handleOversizedMessage` to send 413 via `Responses` and close; use it in `J/server/HttpInitializer.java` (FR-012)
- [ ] T045 [US3] In `J/server/EsCompatServer.java`, make the rejection handler send 429 with JSON error `{"error":{"type":"too_many_requests","reason":"pending request limit reached"},"status":429}` and release the request (FR-012)
- [ ] T046 [US3] Shutdown: in `J/server/EsCompatServer.java` set a `closing` flag so queued-but-unstarted tasks answer 503; in `lib/logstash/inputs/elasticsearch_compat.rb` `stop` calls `batch_handler.stop!` before `server.close`

**Checkpoint**: All three stories pass independently

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Performance verification, documentation, release hygiene

- [ ] T047 Create `IT/CaptureExporterBodiesIT.java` that points the exporter at a capturing HTTP stub (returning a valid Bulk response with `X-Elastic-Product`) and writes 20 real request bodies to `target/bench/exporter/*.ndjson`, plus `*.jsonl` copies with action lines removed (for `logstash-input-http` `json_lines`)
- [ ] T048 [P] Measure SC-007 in two parts: (a) `JMH/BulkPathBenchmark.java`: parse + `BatchHandler` no-op + response write over captured bodies resized to 1,000, 5,000, and 10,000 items, reporting p99; (b) `src/it/resources/bench/event_build_bench.rb`, run by `IT/EventBuildBenchIT.java` inside the `logstash:9.5.6` container: p99 time of `Event.new` + `event.set(metadata)` + `decorate` for the same 1,000/5,000/10,000 documents, no queue; pass rule (documented in `src/jmh/resources/README.md`): Java p99 + Ruby p99 < 10 ms at 1,000 items, each growing ≤ linearly
- [ ] T049 [P] Create `IT/ThroughputIT.java` and `src/it/resources/pipelines/throughput-escompat.conf` / `throughput-http.conf` (`http { codec => json_lines }`), both outputting to nothing via `drop {}` in filter: replay captured bodies with 10 concurrent senders for 60 s after 15 s warm-up against each plugin on the same container resources, sending `Content-Type: application/x-ndjson` to both (so `logstash-input-http` uses `json_lines`, not its `application/json` → `json` mapping); read `events.in` from `:9600/_node/stats/pipelines`; run each plugin 3 times alternately and fail if this plugin's median events/s < 95% of `logstash-input-http`'s median (SC-006)
- [ ] T050 Run `mvn verify -Pbench` and `mvn verify -Pthroughput`; record hardware, versions, and numbers in `specs/001-otel-bulk-ingest/results.md`
- [ ] T051 [P] Write `README.md`: install, configuration (contracts/plugin-config.md), what success means (queued, not delivered), retry duplication, unsupported `update`/`delete`, synthetic `X-Elastic-Product` header, credentials accepted and ignored, not yet supported (TLS, gzip, `GET /`, other routes)
- [ ] T052 [P] Create `docs/compatibility.md`: matrix row "OTel Collector contrib v0.165.1 `elasticsearch` exporter — tested with version_detection off, compression none — Logstash 9.5.6 memory + persisted"
- [ ] T053 Run `mvn verify -Paudit` (profile in `pom.xml`) and resolve any CVSS ≥ 7 finding in Netty/Jackson
- [ ] T054 Run every step of [quickstart.md](quickstart.md) and fix any drift

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none
- **Foundational (Phase 2)**: after Setup; blocks all stories. T007 first: its result may add a
  task to US1
- **US1 (Phase 3)**: after Foundational
- **US2 (Phase 4)**: after Foundational; edits files created in US1 (`BulkParser`,
  `BulkRequestHandler`, `batch_handler.rb`), so run after US1
- **US3 (Phase 5)**: after Foundational; touches only server classes, so can run in parallel
  with US2
- **Polish (Phase 6)**: after US1 (T047–T050 need the working ingest path); T051–T054 after all
  stories

### Within Each Story

Tests first and failing → parser/response → request handler → Ruby → wiring.

### Parallel Opportunities

- Setup: T004, T005, T006
- Foundational: T008–T011, T015, T018–T020
- US1 tests: T022–T026; US2 tests: T033–T036; US3 tests: T040–T042
- US3 alongside US2
- Polish: T048, T049, T051, T052

---

## Parallel Example: User Story 1

```text
Task: "Create JT/bulk/BulkParserTest.java (happy paths)"
Task: "Create JT/response/FilterPathTest.java"
Task: "Create JT/response/BulkResponseWriterTest.java"
Task: "Create IT/BulkIngestIT.java"
Task: "Create IT/ExporterIngestIT.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Setup → Foundational (including the T007 spike)
2. US1 → validate with `ExporterIngestIT` on both queue types
3. Optionally run T047–T050 early to catch performance problems before US2/US3

### Incremental Delivery

US1 (exporter works) → US2 (truthful errors) → US3 (overload safety) → Polish (performance
evidence, docs). Feature 002 (TLS) follows and blocks release.

---

## Notes

- Commit after each task or logical group
- Classes under `src/main/java` may import only the plugin's own
  `org.logstash.plugins.inputs.escompat` package from `org.logstash.*`, and never
  `co.elastic.logstash.*`
