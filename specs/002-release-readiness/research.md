# Research: Release Readiness

Sources: Elasticsearch `v9.2.0` (`HttpTransportSettings`, `Netty4HttpServerTransport`),
`logstash-input-http` `32af925` (`lib/logstash/inputs/http.rb`, `SslSimpleBuilder`),
otelcol-contrib exporter source (`es_version.go`), Netty 4.1.140.Final jars.

## R1. Request decompression (FR-001–FR-003)

- **Decision**: Netty `HttpContentDecompressor(false, 0)` placed before the aggregator, always on,
  as in Elasticsearch. The aggregator limit (`max_content_length`) applies to decompressed bytes
  and yields 413. No per-buffer `maxAllocation`: when exceeded it throws `DecompressionException`
  (found in testing: that would be a 400, not 413); each input chunk (≤ 8 KB) can expand at most
  ~1000× (deflate's limit), so transient memory per chunk stays bounded. Allowed encodings: `gzip`, `x-gzip`, `deflate`,
  `x-deflate`, `identity`; anything else → 415 (Brotli/Zstd decoders are not bundled).
  Corrupt data (`DecompressionException`) → 400, nothing queued.
- **Rationale**: Matches Elasticsearch (decompressor always installed) and `logstash-input-http`.
- **Alternatives**: Decompress in the worker thread — moves CPU off the event loop but needs our
  own bounded inflater; rejected unless profiling shows event-loop contention.

## R2. Response compression (FR-004, FR-005)

- **Decision**: Netty `HttpContentCompressor` with gzip and deflate at level 3, no size threshold,
  installed only when `compression_enabled` (default true). It sits right after the codec so it
  sees each request's `Accept-Encoding` and compresses every response, including early errors.
- **Rationale**: Exactly Elasticsearch's setup (`http.compression: true`,
  `http.compression_level: 3`, no threshold).

## R3. Identity response (FR-006)

- **Decision**: `GET /` → 200 `{"name":"logstash-input-elasticsearch_compat","version":{"number":"8.19.0","build_flavor":"default"},"tagline":"Elasticsearch Bulk API compatibility receiver; not Elasticsearch"}`
  with `X-Elastic-Product: Elasticsearch`. `HEAD /` → 200, no body. Other methods on `/` → 405.
  Answered before aggregation; not counted in metrics.
- **Rationale**: The exporter reads only `version.number` and `version.build_flavor`
  (`es_version.go`); 8.19.0 (< 9.4) keeps its baseline metrics encoding (spec clarification).
  No cluster name, UUID, Lucene version, or build hash: nothing we cannot justify (Principle I).
  The probe uses the raw transport, so the product check does not apply to it, but the header is
  sent anyway for other clients.

## R4. Bulk routes (FR-007, FR-008)

- **Decision**: Routing accepts `POST`/`PUT` on `/_bulk` and on `/{index}/_bulk` where `{index}`
  is one non-empty, URL-decoded path segment (no `/`). Anything else is not a Bulk route (404,
  or 405 for wrong methods). The URL index is passed to the parser as the default `_index`; an
  action's own `_index` wins. Index names are not validated (consistent with action `_index`).

## R5. TLS (FR-009, FR-010)

- **Decision**: Port `logstash-input-http`'s `SslSimpleBuilder`/`SslHandlerProvider` design
  (Apache-2.0, attributed in NOTICE) into `server/tls/`, using Netty's JDK `SslContextBuilder`
  (no OpenSSL native libs: they cannot be shaded). Settings, defaults, allowed values, and the
  default cipher list are copied exactly. Validation rules are copied into the Ruby adapter:
  - certificate requires key, and key requires certificate;
  - TLS on requires a certificate or a keystore, not both;
  - certificate authorities and truststore are mutually exclusive;
  - client authentication `optional`/`required` needs certificate authorities, a truststore, or a
    keystore (the keystore then acts as trust store);
  - certificate authorities or truststore with client authentication `none` → error;
  - TLS settings with `ssl_enabled => false` → warning only.
  `SslHandler` is first in the pipeline with `ssl_handshake_timeout`.
- **Note**: `TLSv1.1` is an allowed value (parity) but the JDK disables it by default
  (`jdk.tls.disabledAlgorithms`); documented, not overridden.
- **Test fixtures**: committed CA, server, and client certificates (PEM, PKCS12, JKS, truststore)
  with a `generate.sh`, as `logstash-input-http` does; long validity to avoid expiry.

## R6. Metrics (FR-011–FR-013)

- **Decision**: Java keeps cumulative `LongAdder` counters, incremented once per request (and once
  per request for its item-error count). Ruby publishes them with `metric.gauge(key, value)`
  after each `submit` call (once per request) and from a 1 s timer, so refused requests that
  never reach Ruby still appear within a second. Gauges of cumulative values are idempotent and
  need no locking. `enable_metric => false` gives Logstash's null metric, so nothing is reported.
- **Rationale**: No per-event work; at most a handful of calls per request (Principle III,
  FR-012).
- **Alternatives**: Ruby callback per refused request — adds Ruby calls exactly when overloaded.

## R7. Performance verification (FR-014)

- **Decision**: `ThroughputIT` runs both plugins with TLS (same PEM certificate) and sends
  gzip-compressed bodies (level 1, the exporter's `BestSpeed`) through an HTTPS client that trusts
  the test CA; this plugin also has metrics on. `EventBuildBenchIT`'s combined measure adds gzip
  decode of the request and level-3 compression of the response.

## R8. Release packaging (FR-015–FR-019)

- **Findings**: Netty jars ship no `LICENSE`/`NOTICE`; the shaded JAR's `META-INF/NOTICE` is
  Jackson's only.
- **Decision**:
  - Add root `LICENSE` (Apache-2.0) and `NOTICE.TXT` containing the project notice, Netty's
    `NOTICE.txt` (from the `netty-4.1.140.Final` source tag), Jackson's NOTICE, and attribution
    for the ported `logstash-input-http` TLS code.
  - Shade with `ApacheNoticeResourceTransformer`/`ApacheLicenseResourceTransformer` and copy
    `NOTICE.TXT`/`LICENSE` into the JAR's `META-INF`.
  - Add `CHANGELOG.md`, `docs/index.asciidoc` in the standard Logstash plugin format, and
    `RELEASING.md`.
  - Set gemspec `required_ruby_version '>= 3.1'` (JRuby 9.4+, as bundled across Logstash 9.x),
    with complete metadata and no build warnings.
