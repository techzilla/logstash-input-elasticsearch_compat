# Results: Release Readiness

Recorded 2026-11-13. Docker Desktop (32 CPUs), Logstash 9.5.6, otelcol-contrib 0.165.0.

## Test runs

| Run | Result |
|---|---|
| `mvn package` | 115 unit tests pass |
| `jruby -S rspec spec/inputs` | 22 examples pass (batch handler, TLS validation, metrics publisher) |
| `mvn verify -Pintegration` | 19 integration tests pass (4:30) |
| `mvn verify -Pintegration,throughput` | pass (4:28) |

## Success criteria

| Criterion | Result | Evidence |
|---|---|---|
| SC-001 exporter with defaults | **Pass** | `ExporterDefaultsIT`: 1,000/1,000, 0 failures, probe logs `Connected to Elasticsearch` 8.19.0 |
| SC-002 compressed = uncompressed | **Pass** | `CompressionTest`: gzip/x-gzip/deflate/x-deflate match; bomb → 413, corrupt → 400, br → 415, nothing submitted |
| SC-003 four routes identical | **Pass** | `RoutesIT`, `RoutingHandlerTest` |
| SC-004 TLS parity | **Pass** | `SslContextFactoryTest`, `TlsServerTest`, `ssl_validation_spec` (rules 1–6, exact messages), `TlsIT` (PEM, keystore, client auth, exporter over TLS, invalid config fails startup) |
| SC-005 counters exact | **Pass** | `RequestCountersTest`, `MetricsIT` (node stats; absent with `enable_metric => false`) |
| SC-006 no regression with TLS + gzip + metrics | **Pass** | Median 278,648 vs 50,803 events/s (549%), both plugins on TLS with gzip bodies; combined p99 15.49 ms at 1,000 items (< 20 ms), linear growth |
| SC-007 releasable artifact | **Pass** | `PackagingIT`: JAR LICENSE + NOTICE (Netty, Jackson, logstash-input-http), gem has LICENSE/NOTICE.TXT/CHANGELOG/docs; every setting documented; `gem build` with 0 warnings |

## Defects found during implementation

1. **Decompression cap**: Netty's per-buffer `maxAllocation` throws `DecompressionException`, which
   would turn an oversized compressed body into 400 instead of 413. Removed the cap (as
   Elasticsearch does); the aggregator bounds decompressed size (research R1).
2. **Shading excluded `netty-transport-native-unix-common`**: plain-Java classes `SslHandler`
   needs. Unit tests passed (unshaded classpath); TLS failed only in real Logstash
   (`NoClassDefFoundError: UnixChannel`). Now only the epoll/kqueue/io_uring native transports
   are excluded.
3. **PKCS#1 keys** are rejected by Netty's PEM loader (same as `logstash-input-http`); documented
   as "PKCS#8" with a conversion command.
