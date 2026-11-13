# Quickstart: OTel Bulk Ingest validation

## Prerequisites

`mise install` (Java 21, Maven, JRuby), Docker running.

## 1. Build

```bash
mvn -q package            # unit tests + shaded JAR
gem build *.gemspec       # under JRuby, for manual use; -Pintegration builds it automatically
```

## 2. Manual smoke test

Run Logstash 9.5.6 with the gem installed (`bin/logstash-plugin install --local <gem>`) and:

```ruby
input  { elasticsearch_compat { port => 9200 } }
output { stdout { codec => rubydebug { metadata => true } } }
```

```bash
printf '{"create":{"_index":"logs-a","_id":"1"}}\n{"message":"hi"}\n{"delete":{"_index":"logs-a","_id":"2"}}\n' |
  curl -si -XPOST localhost:9200/_bulk -H 'Content-Type: application/x-ndjson' --data-binary @-
```

Expect: `200`, `X-Elastic-Product: Elasticsearch`, `errors:true`, item 1 `201`, item 2 `400`;
one event with `message: hi` and metadata `action=create, index=logs-a, id=1`.

## 3. Integration suite (real Logstash + real exporter)

```bash
mvn verify -Pintegration
```

Covers spec user stories 1–3 on memory and persistent queues, with `otelcol-contrib:0.165.0`
configured per [research R1](research.md#r1-reference-client-and-pinned-versions).
Pass = SC-001–SC-005.

## 4. Performance

```bash
mvn verify -Pintegration,throughput   # ~5 min: capture exporter bodies, Ruby event-build bench
                                      # (SC-007), replay vs logstash-input-http (SC-006)
mvn verify -Pbench                    # JMH, Java part of SC-007 (needs target/bench/exporter)
```

Without Docker, the same runs work against native binaries: run `CaptureStub` and
`BulkLoadDriver` (`src/it/java/.../it/support`) by hand against a local Logstash and
`otelcol-contrib`.

Pass = combined p99 (parse + event build + response, from `EventBuildBenchIT`) < 20 ms at 1,000 items with linear growth; median events/s
over 3 runs ≥ 95% of `logstash-input-http` (`json_lines`) on the same host. Results are recorded in `specs/001-otel-bulk-ingest/results.md`.
