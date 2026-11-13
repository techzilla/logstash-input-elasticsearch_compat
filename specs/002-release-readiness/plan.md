# Implementation Plan: Release Readiness

**Branch**: `002-release-readiness` | **Date**: 2026-11-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-release-readiness/spec.md`

## Summary

Extend the feature 001 plugin so the OTel exporter works with its defaults (always-on gzip/deflate
request decoding, Elasticsearch-style response compression, a fixed `GET /` identity), accept
`POST`/`PUT` on `/_bulk` and `/{index}/_bulk`, add TLS with `logstash-input-http`'s exact
`ssl_*` settings and rules, publish per-request counters to node stats, and complete release
packaging. All changes sit in the Netty pipeline, the router, the Ruby adapter, and packaging; the
Bulk parse/queue path is unchanged except for the default index.

## Technical Context

**Language/Version**: Java 21; JRuby as bundled with Logstash 9.5.6 (unchanged)

**Primary Dependencies**: Netty 4.1.140 codec-http (adds `HttpContentDecompressor`,
`HttpContentCompressor`, `SslHandler` via JDK `SslContextBuilder`), Jackson core; both shaded

**Storage**: N/A

**Testing**: JUnit 5 with committed TLS certificate fixtures, Ruby rspec for TLS validation rules,
Testcontainers ITs (Logstash 9.5.6, otelcol-contrib 0.165.0), JMH

**Target Platform**: Linux, Logstash 9.5.x

**Project Type**: Logstash input plugin (Ruby gem + Java library)

**Performance Goals**: SC-006 from 001 with TLS + gzip on both plugins and metrics on

**Constraints**: SC-007 combined p99 < 20 ms at 1,000 items incl. decode/encode; counters never
per event; no OpenSSL native libraries (cannot be shaded)

**Scale/Scope**: as 001

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|---|---|---|
| I. Honest compatibility | Pass | Identity response carries only `version.number`/`build_flavor` (fixed placeholder) and a tagline that says it is not Elasticsearch; documented (R3) |
| II. Known outcomes only | Pass | Unchanged; corrupt compression is a request-level 400 before submission |
| III. Bounds | Pass | Decompressed size bounded by `max_content_length` plus per-buffer `maxAllocation` (R1); handshake timeout bounds TLS setup |
| IV. Permissive content, strict framing | Pass | Index segment unvalidated like action `_index`; unknown encodings → 415 |
| V. Evidence drives scope | Pass | Compression and `GET /`: exporter defaults; TLS settings: parity with `logstash-input-http` (user decision); routes: Elasticsearch Bulk API |
| VI. Credentials ignored, TLS supported | Pass | TLS now supported (resolves 001's recorded deviation); optional client-certificate verification is explicitly allowed by constitution v1.1.0 |
| Architecture | Pass | Java imports no Logstash classes; metrics published from Ruby (R6) |
| Quality gates | Pass | Each story has unit + container tests; performance re-verified under TLS/gzip |

Post-design re-check: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/002-release-readiness/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── http-api.md
│   └── plugin-config.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
LICENSE                                   # new: Apache-2.0
NOTICE.TXT                                # new: project + Netty + Jackson + ported TLS code
CHANGELOG.md, RELEASING.md                # new
docs/index.asciidoc                       # new: Logstash plugin reference
pom.xml                                   # shade notice/license transformers
logstash-input-elasticsearch_compat.gemspec   # required_ruby_version, files
lib/logstash/inputs/elasticsearch_compat.rb    # new settings, TLS validation, metrics publishing
src/main/java/org/logstash/plugins/inputs/escompat/
├── server/
│   ├── HttpInitializer.java              # ssl → codec → compressor → routing → encoding → decompressor → aggregator → bulk
│   ├── RoutingHandler.java               # methods × paths, default index, identity
│   ├── IdentityHandler.java              # new: GET/HEAD /
│   ├── ContentEncodingHandler.java       # allow gzip/deflate family
│   ├── DecompressionErrorHandler.java    # new: corrupt data → 400
│   ├── RequestCounters.java              # new: LongAdder counters + snapshot
│   └── tls/                              # new: SslContextFactory, TlsSettings (ported design)
├── bulk/BulkParser.java                  # default index parameter
└── ServerConfig.java                     # new: settings bundle passed from Ruby
src/test/java/...                         # unit tests per area
src/it/java/.../it/                       # TLS, routes, metrics, exporter-defaults ITs; throughput with TLS+gzip
src/it/resources/tls/                     # committed fixtures + generate.sh
spec/inputs/                              # TLS validation rules spec
```

**Structure Decision**: Same single-repo layout as 001. A `ServerConfig` value object replaces the
growing constructor argument list of `EsCompatServer`.

## Complexity Tracking

No violations. (001's TLS deviation is resolved by this feature.)
