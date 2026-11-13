---

description: "Task list for Release Readiness"
---

# Tasks: Release Readiness

**Input**: Design documents from `specs/002-release-readiness/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Required (constitution Quality Gates). Write each story's tests first and confirm they
fail.

**Organization**: Tasks are grouped by user story so each story can be built and tested on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: User story from spec.md (US1–US5)

## Path Conventions

Same abbreviations as feature 001: `J/` = `src/main/java/org/logstash/plugins/inputs/escompat/`,
`JT/` = `src/test/java/org/logstash/plugins/inputs/escompat/`,
`IT/` = `src/it/java/org/logstash/plugins/inputs/escompat/it/`, Ruby in `lib/logstash/inputs/`.

Target pipeline order (research R1–R5), built up by the stories:
`ssl → codec → compressor → routing → identity → encoding → decompressor → aggregator → bulk → errors`.

---

## Phase 1: Setup

- [X] T001 Create `src/test/resources/tls/generate.sh` (openssl, 36500-day validity) and run it once, committing its outputs to `src/test/resources/tls/`: `ca.crt`/`ca.key`; `server.crt` (SAN `localhost`, `127.0.0.1`, `logstash`) with `server.key` (PKCS#8, unencrypted), `server.pkcs1.key`, `server.encrypted.key` (PKCS#8, passphrase `changeit`); `server.p12` and `server.jks` keystores (password `changeit`); `truststore.jks` with `ca.crt` (password `changeit`); `client.crt`/`client.key` signed by `ca`; `untrusted-ca.crt` and `untrusted-client.crt`/`.key` signed by a second CA. Integration tests read them from the test classpath

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T002 Create `J/ServerConfig.java`: immutable value object with builder; fields `name`, `host`, `port`, `threads`, `maxPendingRequests`, `maxContentLength` (all required, > 0 where numeric), `compressionEnabled` (default true), `sslContext` (`io.netty.handler.ssl.SslContext`, nullable), `sslHandshakeTimeoutMillis` (default 10000), `counters` (`RequestCounters`, nullable until US4)
- [X] T003 Refactor `J/server/EsCompatServer.java` to `EsCompatServer(ServerConfig, BatchHandler)`; update `JT/server/TestServer.java` (add constructor taking a `ServerConfig.Builder` customizer) and `lib/logstash/inputs/elasticsearch_compat.rb` `register`; `mvn package` and `jruby -S rspec spec/inputs` still pass unchanged
- [X] T004 Restructure `J/server/HttpInitializer.java` to take `ServerConfig` and name every pipeline stage (`ssl`, `codec`, `compressor`, `routing`, `identity`, `encoding`, `decompressor`, `aggregator`, `bulk`, `errors`) with stages added only when configured; document the target order in its Javadoc; all existing tests pass
- [X] T005 Extend `JT/server/TestServer.java`: `request(method, path, byte[] body, String... headers)` returning raw body bytes plus headers; helper `gunzip(byte[])`; HTTPS variant built from an `SSLContext` (used by US2)

**Checkpoint**: Behavior identical to 001; new configuration plumbing in place

---

## Phase 3: User Story 1 - Exporter works with its default settings (Priority: P1) 🎯 MVP

**Goal**: An unmodified exporter (gzip on, version detection on) delivers everything.

**Independent Test**: `ExporterDefaultsIT` — exporter with only `endpoint` set; 1,000/1,000 records, no errors, version-probe success logged (SC-001).

### Tests for User Story 1 ⚠️

- [X] T006 [P] [US1] Create `JT/server/CompressionTest.java`: `gzip`, `x-gzip`, `deflate`, `x-deflate` bodies produce the same handler ops and response as the plain body; a 2 KB gzip body that expands past `maxContentLength` (64 KB in the test) → 413, handler never called; corrupt gzip bytes → 400 `parse_exception`, handler never called; `Content-Encoding: br` → 415; with `Accept-Encoding: gzip` the 200, 400 and 404 responses carry `Content-Encoding: gzip` and decode to the uncompressed body; without `Accept-Encoding` responses are plain; with `compressionEnabled(false)` a gzip request is still accepted and the response is never compressed
- [X] T007 [P] [US1] Create `JT/server/IdentityTest.java`: `GET /` → 200 with exactly `{"name":"logstash-input-elasticsearch_compat","version":{"number":"8.19.0","build_flavor":"default"},"tagline":"Elasticsearch Bulk API compatibility receiver; not Elasticsearch"}` and `X-Elastic-Product: Elasticsearch`; `HEAD /` → 200, same headers, empty body; `POST /` and `PUT /` → 405; handler never called
- [X] T008 [P] [US1] Update `JT/server/LimitsTest.java`: replace the `gzipIs415` case with `brotliIs415` (`Content-Encoding: br`)
- [X] T009 [P] [US1] Create `src/it/resources/otelcol/config-defaults.yaml` (only `endpoint: ${env:ES_ENDPOINT}` under the exporter, plus the same receivers/telemetry as `config.yaml`) and make `IT/support/OtelCollectorContainer.java` accept the config resource name (default `otelcol/config.yaml`)
- [X] T010 [P] [US1] Create `IT/ExporterDefaultsIT.java` (memory queue, `capture.conf`): `config-defaults.yaml`, 1,000 logs; assert 1,000 events, `otelcol_exporter_sent_log_records` 1000 and `send_failed` 0, collector logs contain `Connected to Elasticsearch` and `8.19.0` and no `failed to fetch Elasticsearch info`

### Implementation for User Story 1

- [X] T011 [US1] Update `J/server/ContentEncodingHandler.java`: allow `gzip`, `x-gzip`, `deflate`, `x-deflate`, `identity` (case-insensitive, trimmed); anything else → 415
- [X] T012 [US1] Add stage `decompressor` = `new HttpContentDecompressor(false, Math.min(maxContentLength, 1 << 20))` before `aggregator` in `J/server/HttpInitializer.java`, always installed (FR-001, FR-002)
- [X] T013 [US1] Create `J/server/DecompressionErrorHandler.java` (stage `errors`, last): on an exception whose cause chain contains `DecompressionException`, send 400 `parse_exception` "invalid compressed request body" via `Responses` and close; otherwise log at DEBUG and close (FR-003)
- [X] T014 [US1] Add stage `compressor` = `new HttpContentCompressor(0, StandardCompressionOptions.gzip(3, 15, 8), StandardCompressionOptions.deflate(3, 15, 8))` right after `codec` in `J/server/HttpInitializer.java`, only when `compressionEnabled` (FR-004, FR-005)
- [X] T015 [US1] Create `J/server/IdentityHandler.java` (extends the early-reject pattern) for path `/`: `GET` → 200 with the T007 body, `HEAD` → 200 no body, other methods → 405; discard any request body; update `J/server/RoutingHandler.java` to let `/` through to it (FR-006)
- [X] T016 [US1] Add `config :compression_enabled, :validate => :boolean, :default => true` to `lib/logstash/inputs/elasticsearch_compat.rb` and pass it to `ServerConfig`

**Checkpoint**: Exporter with default settings works end to end

---

## Phase 4: User Story 2 - Traffic can be encrypted (Priority: P1)

**Goal**: TLS with `logstash-input-http`'s exact settings and rules.

**Independent Test**: `TlsServerTest` + `TlsIT` — every valid setup works, every invalid one is rejected (SC-004).

### Tests for User Story 2 ⚠️

- [X] T017 [P] [US2] Create `JT/server/tls/SslContextFactoryTest.java`: builds contexts from PEM cert+key, PKCS#1 key, encrypted key with `changeit`, `server.p12`, `server.jks` (type given and type detected); client-auth contexts from CA list and from truststore; unknown cipher name → `IllegalArgumentException`; protocols restricted to the configured list
- [X] T018 [P] [US2] Create `JT/server/TlsServerTest.java` (real server): HTTPS Bulk request with a client trusting `ca.crt` → 200; plain HTTP to the TLS port fails; `required` client auth: no certificate → handshake failure, `untrusted-client` → failure, `client` → 200; `optional`: no certificate → 200; server `TLSv1.3` only vs client `TLSv1.2` only → failure; server limited to one TLS 1.2 cipher vs client offering another → failure; a TCP connection sending nothing is closed within `sslHandshakeTimeoutMillis` (set to 500) + 1 s
- [X] T019 [P] [US2] Create `spec/inputs/ssl_validation_spec.rb` (plain JRuby) for `LogStash::ElasticsearchCompat::SslValidation`: each rule 1–6 from contracts/plugin-config.md produces the expected error (or warning for rule 6) with `logstash-input-http`'s message text; valid combinations produce none
- [X] T020 [P] [US2] Create pipelines `src/it/resources/pipelines/tls-pem.conf`, `tls-keystore.conf`, `tls-client-auth.conf` (`ssl_client_authentication => required`, `ssl_certificate_authorities`), `tls-invalid.conf` (certificate without key), mounting fixtures at `/usr/share/logstash/tls/`; and `src/it/resources/otelcol/config-tls.yaml` (exporter endpoint `https://...`, `tls.ca_file`)
- [X] T021 [P] [US2] Create `IT/TlsIT.java`: for `tls-pem` and `tls-keystore`, an HTTPS Bulk request → 200 and 1 event and HTTPS `GET /` returns the identity response; `tls-client-auth` rejects a client without a certificate and accepts `client.crt`; the exporter over TLS (`config-tls.yaml`) delivers 1,000 records; `tls-invalid` makes Logstash fail to start with "Using an `ssl_certificate` requires an `ssl_key`" in its log. `LogstashContainer` gains an option to mount the TLS fixtures

### Implementation for User Story 2

- [X] T022 [US2] Create `J/server/tls/TlsSettings.java` (record of the contract's TLS fields) and `J/server/tls/SslContextFactory.java`, porting `logstash-input-http`'s `SslSimpleBuilder` behavior (attribution header, Apache-2.0): JDK provider, PEM or keystore key material, keystore type detection when unset, trust from CA list or truststore, `ClientAuth` NONE/OPTIONAL/REQUIRE, protocols, ciphers (default = its `DEFAULT_CIPHERS` candidate list filtered to JDK-supported; explicit names validated against the JDK's supported list)
- [X] T023 [US2] Add stage `ssl` first in `J/server/HttpInitializer.java` when `sslContext` is set, with `setHandshakeTimeoutMillis(sslHandshakeTimeoutMillis)`
- [X] T024 [US2] Create `lib/logstash/inputs/elasticsearch_compat/ssl_validation.rb` (pure module, no Logstash dependency) implementing rules 1–6 with `logstash-input-http`'s exact messages
- [X] T025 [US2] Add the 14 `ssl_*` settings to `lib/logstash/inputs/elasticsearch_compat.rb` with `logstash-input-http`'s exact `:validate`, `:default`, `:list` options (cipher suites validated against `SslContextFactory.supportedCiphers()`, default `SslContextFactory.defaultCiphers()`); in `register`, run `SslValidation` (errors → `LogStash::ConfigurationError`, warnings → `logger.warn`), build `TlsSettings`, call `SslContextFactory`, and set `sslContext`/`sslHandshakeTimeoutMillis` on `ServerConfig`

**Checkpoint**: TLS parity with `logstash-input-http`

---

## Phase 5: User Story 3 - Any standard Bulk route works (Priority: P2)

**Goal**: POST and PUT on `/_bulk` and `/{index}/_bulk`.

**Independent Test**: `RoutesIT` — identical events across the four combinations (SC-003).

### Tests for User Story 3 ⚠️

- [X] T026 [P] [US3] Extend `JT/server/RoutingHandlerTest.java`: `POST`/`PUT` × `/_bulk`/`/logs-a/_bulk` → 200; `GET /_bulk` and `DELETE /logs-a/_bulk` → 405; `//_bulk`, `/a/b/_bulk`, `/a%2Fb/_bulk` → 404
- [X] T027 [P] [US3] Extend `JT/bulk/BulkParserTest.java`: with default index `url-idx`, an action without `_index` gets `url-idx`; an action with `_index` keeps it; with null default a missing `_index` is still `MISSING_INDEX`
- [X] T028 [P] [US3] Create `IT/RoutesIT.java` (`capture.conf`): send the same 2-item body (one item without `_index`) via `POST /_bulk`, `PUT /_bulk`, `POST /url-idx/_bulk`, `PUT /url-idx/_bulk`; events identical except `_meta.index` (`url-idx` only on the indexed routes; the item without `_index` gets 400 on `/_bulk`)

### Implementation for User Story 3

- [X] T029 [US3] Update `J/server/RoutingHandler.java`: accept `POST`/`PUT` on `/_bulk` and on `/{index}/_bulk` where `{index}` is one non-empty decoded segment without `/`; store the default index on the channel via an `AttributeKey<String>` for the request; other methods on these paths → 405; anything else → 404
- [X] T030 [US3] Add `parse(ByteBuf body, String defaultIndex)` to `J/bulk/BulkParser.java` (existing overloads delegate with `null`): resolved index = action `_index` if non-empty, else `defaultIndex`, else `MISSING_INDEX`
- [X] T031 [US3] Make `J/server/BulkRequestHandler.java` read the default-index attribute and pass it to the parser

**Checkpoint**: All four routes work

---

## Phase 6: User Story 4 - Operators can see what the plugin is doing (Priority: P2)

**Goal**: Counters from data-model.md in node stats, per request, honoring `enable_metric`.

**Independent Test**: `MetricsIT` — counters equal driven numbers (SC-005).

### Tests for User Story 4 ⚠️

- [X] T032 [P] [US4] Create `JT/server/RequestCountersTest.java` (real server via `TestServer`): drive 3 accepted requests (one with an `update` item and one `index` item without `_index`), one each of 413, 415, 400 (malformed), and 429 (blocked handler, 1 worker, 1 pending); assert snapshot `bulk_requests` = 7 (3 accepted + 4 refused), each `rejected_*` = 1, `bulk_items_failed` = 2, `queue_submit_failures` = 0; `GET /`, 404, and `GET /_bulk` (405) requests not counted; invariant `bulk_requests` ≥ sum of rejections
- [X] T033 [P] [US4] Create `spec/inputs/metrics_publisher_spec.rb` (plain JRuby, fake metric recording `gauge` calls): `publish(snapshot)` sets every counter key; `start`/`stop` of the 1 s timer publishes at least once and stops cleanly
- [X] T034 [P] [US4] Create `IT/MetricsIT.java` with pipeline `src/it/resources/pipelines/metrics.conf` (input `id => "m_on"` on 9200 and `id => "m_off"` with `enable_metric => false` on 9201): drive known traffic to 9200, wait 2 s, read node stats; `m_on` counters equal the driven numbers; `m_off` has none of the counter keys

### Implementation for User Story 4

- [X] T035 [US4] Create `J/server/RequestCounters.java`: `LongAdder` per counter in data-model.md, `increment*` methods, `snapshot()` → `Map<String, Long>` with the exact key names from contracts/plugin-config.md
- [X] T036 [US4] Count in the pipeline: `bulk_requests` in `RoutingHandler` when `POST`/`PUT` matches a Bulk route (not for 405); `rejected_unsupported_encoding` in `ContentEncodingHandler`; `rejected_too_large` in `BoundedAggregator`; `rejected_overload` in `BulkRequestHandler.Task.reject` (429 only); `rejected_bad_request` for parse, `filter_path`, and decompression errors; `bulk_items_failed` (+ items with status ≥ 400) and `queue_submit_failures` (+ `SUBMIT_FAILED`) once per request after outcomes are known — all in `J/server/`
- [X] T037 [US4] Create `lib/logstash/inputs/elasticsearch_compat/metrics_publisher.rb`: `publish(snapshot)` → `metric.gauge(key.to_sym, value)` per key; a daemon thread publishing every 1 s, started in `run`, stopped in `stop`/`close`
- [X] T038 [US4] Wire counters: create `RequestCounters` in `register`, put it on `ServerConfig`, call `publisher.publish(counters.snapshot)` after each `submit` in `lib/logstash/inputs/elasticsearch_compat/batch_handler.rb` (via an injected callable so the existing specs still pass)

**Checkpoint**: Counters visible in node stats

---

## Phase 7: User Story 5 - The plugin can be released (Priority: P3)

**Goal**: Licensing, docs, changelog, release process.

**Independent Test**: `PackagingIT` + release dry run (SC-007).

### Tests for User Story 5 ⚠️

- [X] T039 [P] [US5] Create `IT/PackagingIT.java`: the shaded JAR has `META-INF/LICENSE` and `META-INF/NOTICE` containing "Netty", "Jackson" and "logstash-input-http"; the gem (`target/logstash-input-elasticsearch_compat.gem`) contains `LICENSE`, `NOTICE.TXT`, `CHANGELOG.md`, `docs/index.asciidoc`; `jruby -S gem build` output has no `WARNING`; every `config :<name>` declared in `lib/logstash/inputs/elasticsearch_compat.rb` appears in `docs/index.asciidoc`

### Implementation for User Story 5

- [X] T040 [P] [US5] Add `LICENSE` with the full Apache License 2.0 text
- [X] T041 [P] [US5] Add `NOTICE.TXT`: project notice; Netty's `NOTICE.txt` from the `netty-4.1.140.Final` tag; Jackson core's notice (from its JAR `META-INF/NOTICE`); attribution for the TLS code ported from `logstash-input-http` (Copyright Elasticsearch B.V., Apache-2.0)
- [X] T042 [US5] Update `pom.xml` shade configuration: `ApacheLicenseResourceTransformer`, `ApacheNoticeResourceTransformer` (`addHeader` false), and `IncludeResourceTransformer`s placing `LICENSE` and `NOTICE.TXT` at `META-INF/LICENSE` and `META-INF/NOTICE`
- [X] T043 [P] [US5] Add `CHANGELOG.md` with a `0.1.0` entry covering features 001 and 002
- [X] T044 [P] [US5] Update `logstash-input-elasticsearch_compat.gemspec`: `required_ruby_version '>= 3.1'`, include `LICENSE`, `NOTICE.TXT`, `CHANGELOG.md`, `docs/**/*`; `gem build` with no warnings
- [X] T045 [P] [US5] Write `docs/index.asciidoc` in the Logstash plugin reference format (`:plugin:`, `:type:`, `:default_codec:` attributes, description, settings table, per-setting details) covering every setting, compression, identity response, routes, metrics, acknowledgment semantics and limitations
- [X] T046 [P] [US5] Write `RELEASING.md`: version bump (`VERSION` and `pom.xml`), `CHANGELOG.md`, `mvn verify -Pintegration`, gem build, tag `v<version>`, `gem push`

**Checkpoint**: Releasable artifact

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T047 Add pipelines `src/it/resources/pipelines/throughput-escompat-tls.conf` and `throughput-http-tls.conf` (same `ssl_certificate`/`ssl_key`); extend `IT/support/BulkLoadDriver.java` with HTTPS (trusting `ca.crt`) and pre-gzipped bodies (level 1) sent with `Content-Encoding: gzip`; switch `IT/ThroughputIT.java` to the TLS pipelines and gzip bodies, metrics on (FR-014)
- [X] T048 Add gzip decode of the request body and level-3 gzip of the response to the combined measure in `src/it/resources/bench/event_build_bench.rb` (`EventBuildBenchIT` assertions unchanged)
- [X] T049 [P] Update `README.md` and `docs/compatibility.md`: exporter defaults supported, compression, identity response, routes, TLS, metrics; update matrix row (exporter defaults, TLS)
- [X] T050 Run `mvn verify -Pintegration` and `mvn verify -Pintegration,throughput`; record results in `specs/002-release-readiness/results.md`
- [X] T051 Run every step of [quickstart.md](quickstart.md) and fix drift

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T005)** → stories.
- **US1** and **US2** (both P1) touch `HttpInitializer` stages independently; run sequentially or
  coordinate on that file. US2 needs T001 fixtures.
- **US3** independent of US1/US2 except `RoutingHandler` (also edited by T015): run after US1.
- **US4** after US1–US3 (counts refusals they introduce).
- **US5** independent; T041's attribution assumes US2's port.
- **Polish** after all stories (T047 needs US1 + US2 + US4).

### Parallel Opportunities

- Tests within each story: T006–T010; T017–T021; T026–T028; T032–T034; T039.
- US5 files T040, T041, T043–T046 alongside any story.

## Parallel Example: User Story 2

```text
Task: "Create JT/server/tls/SslContextFactoryTest.java"
Task: "Create JT/server/TlsServerTest.java"
Task: "Create spec/inputs/ssl_validation_spec.rb"
Task: "Create TLS pipelines and otelcol config-tls.yaml"
```

## Implementation Strategy

1. Setup + Foundational.
2. US1 (MVP: exporter defaults) → validate with `ExporterDefaultsIT`.
3. US2 (TLS) → US3 (routes) → US4 (metrics) → US5 (packaging).
4. Polish: performance under TLS + gzip, docs, results.

## Notes

- Commit after each task or logical group.
- Java under `src/main/java` must not import Logstash classes; TLS validation and metric publishing
  live in Ruby.

---

## Implementation Status (2026-11-13)

All 51 tasks done. 115 unit tests, 22 Ruby specs, 19 integration tests, and the throughput
profile pass; results in [results.md](results.md).

Deviations from the task text:

- T012: `HttpContentDecompressor(false, 0)` (no per-buffer cap) instead of a 1 MiB cap — a capped
  buffer throws, turning an oversized body into 400 instead of 413 (research R1 updated).
- T035 (`RequestCounters`) was created during Phase 2 because `ServerConfig` references it.
- T001 fixtures also include `client.p12` and `untrusted-client.p12` (used by the TLS clients).
- T009: `OtelCollectorContainer` also mounts `ca.crt` (for `config-tls.yaml`) and keeps collector
  logs for `ExporterDefaultsIT`.
- Packaging fix found by `TlsIT`: the shade exclusion `netty-transport-native-*` also dropped the
  plain-Java `unix-common` classes `SslHandler` needs; now only epoll/kqueue/io_uring are excluded.
- `ssl_key` is PKCS#8 only (PKCS#1 rejected, matching `logstash-input-http`); contract and docs say so.
- The gemspec gained `homepage` (the repository URL) so `gem build` has no warnings (FR-017).
