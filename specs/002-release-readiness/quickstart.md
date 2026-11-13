# Quickstart: Release Readiness validation

Prerequisites as in [001 quickstart](../001-otel-bulk-ingest/quickstart.md): `mise install`,
Docker running.

## 1. Unit tests

```bash
mvn package
jruby -S rspec spec/inputs
```

Covers routing (methods × paths), identity, encodings and limits, TLS builder and validation
rules, counters.

## 2. Integration (real Logstash + exporter defaults)

```bash
mvn verify -Pintegration
```

Pass criteria:

- Exporter with only `endpoint` set (gzip on, version detection on) → 1,000/1,000 records, no
  exporter errors, collector log shows `Connected to Elasticsearch ... version 8.19.0` (SC-001).
- Same body via all four method/path combinations → identical events (SC-003).
- Each TLS setup and each invalid combination from
  [contracts/plugin-config.md](contracts/plugin-config.md) behaves as specified (SC-004).
- Node stats counters equal the driven numbers; absent with `enable_metric => false` (SC-005).

## 3. Performance (TLS + gzip on both plugins, metrics on)

```bash
mvn verify -Pintegration,throughput
```

Pass: median events/s ≥ 95% of `logstash-input-http`; combined p99 < 20 ms at 1,000 items
(SC-006).

## 4. Release dry run

```bash
mvn package && jruby -S gem build logstash-input-elasticsearch_compat.gemspec   # no warnings
tar -xOf *.gem data.tar.gz | tar -tz | grep -E 'LICENSE|NOTICE'
```

Then follow `RELEASING.md` up to (not including) `gem push` (SC-007).
