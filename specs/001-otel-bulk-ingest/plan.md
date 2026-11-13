# Implementation Plan: OTel Bulk Ingest

**Branch**: `001-otel-bulk-ingest` | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-otel-bulk-ingest/spec.md`

## Summary

A Logstash input gem whose shaded Java/Netty JAR accepts `POST /_bulk`, validates and parses the
whole NDJSON body in one streaming pass, hands the parsed operations to a thin Ruby adapter in a
single call per request, and returns an ordered Bulk response reflecting each item's real queue
outcome. Threading and limits mirror `logstash-input-http`. Performance is verified against that
plugin (SC-006) and with JMH (SC-007).

## Technical Context

**Language/Version**: Java 21; JRuby as bundled with Logstash 9.5.6

**Primary Dependencies**: Netty 4.1 (codec-http), Jackson core (streaming), both shaded and
relocated; Logstash plugin API (Ruby side only)

**Storage**: N/A (Logstash memory or persistent queue)

**Testing**: JUnit 5; Testcontainers (Logstash 9.5.6, otelcol-contrib 0.165.1); JMH

**Target Platform**: Linux, Logstash 9.5.x

**Project Type**: Logstash input plugin (Ruby gem + Java library)

**Performance Goals**: events/s ≥ `logstash-input-http` (`json_lines`) on the benchmark workload
(SC-006)

**Constraints**: p99 added time < 10 ms per 1,000-item request, linear in items (SC-007); no event
loop blocking; listener answers within 1 s while the pipeline is blocked (SC-005)

**Scale/Scope**: Up to 10 concurrent requests of 1–5 MB (exporter defaults, research R4)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|---|---|---|
| I. Honest compatibility | Pass | No invented fields ([contract](contracts/http-bulk.md)); `X-Elastic-Product` is required by the exporter (R2), documented and tested |
| II. Known outcomes only | Pass | Per-item outcomes incl. ambiguous `500` and not-attempted `503` ([data-model](data-model.md)) |
| III. Bounds and backpressure | Pass | Same design and defaults as `logstash-input-http` (R5) |
| IV. Permissive content, strict framing | Pass | Whole-body validation before submission; unknown options ignored; `filter_path` subset with clear `400` (R4) |
| V. Evidence drives scope | Pass | Only `POST /_bulk`; product header justified by exporter source |
| VI. Credentials ignored | Deviation | `Authorization` dropped and ignored; **TLS deferred** — see Complexity Tracking |
| Architecture | Pass | Java imports no Logstash classes; Ruby does event creation and `queue <<`; Maven build |
| Quality gates | Pass | Unit, real-Logstash (memory + PQ), and pinned real-client tiers; overload and error paths tested |

Post-design re-check: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/001-otel-bulk-ingest/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── http-bulk.md
│   └── plugin-config.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
pom.xml                                   # Java build, shading, test/bench profiles
logstash-input-elasticsearch_compat.gemspec
Gemfile
lib/
├── logstash-input-elasticsearch_compat_jars.rb   # require_jar
└── logstash/inputs/
    ├── elasticsearch_compat.rb                   # config, lifecycle
    └── elasticsearch_compat/batch_handler.rb     # implements Java BatchHandler
vendor/jar-dependencies/                          # built shaded JAR (generated)
src/main/java/org/logstash/plugins/inputs/escompat/
├── server/        # Netty bootstrap, pipeline, request handler, worker pool
├── bulk/          # streaming parser, Operation, validation
├── response/      # filter_path, JsonGenerator response writer, error bodies
└── BatchHandler.java                             # Java ↔ Ruby interface
src/test/java/org/logstash/plugins/inputs/escompat/   # JUnit unit tests
src/it/java/...                                   # Testcontainers integration + throughput
src/jmh/java/...                                  # JMH benchmarks
src/it/resources/                                 # Logstash pipelines, otelcol config
```

**Structure Decision**: Single repository with the gem at the root and one Maven project for the
Java library, mirroring `logstash-input-http`'s layout. Integration and benchmark code live in
separate source roots enabled by Maven profiles (`integration`, `bench`, `throughput`).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| VI requires TLS support; this feature has none | Keeps the first feature to protocol correctness and performance baselines; TLS is one Netty handler with no design impact | Including TLS now adds cert fixtures and a second benchmark matrix without reducing this feature's risks. TLS is the next feature and blocks release |
