# Changelog

## 0.1.0

First release.

- Accepts Elasticsearch Bulk API requests on `POST` and `PUT` `/_bulk` and `/{index}/_bulk`; emits
  one event per `index`/`create` action with routing metadata under
  `[@metadata][elasticsearch_compat]` (configurable with `metadata_target`).
- Items are acknowledged only after the event is accepted by the Logstash queue; `update` and
  `delete` get item-level errors; malformed requests queue nothing.
- Works with the OpenTelemetry Collector `elasticsearch` exporter's default settings: gzip/deflate
  request bodies, Elasticsearch-style response compression (`compression_enabled`), and a
  `GET /` identity response for version detection (reports `8.19.0`, a compatibility
  placeholder).
- Exporter `@timestamp` values (epoch milliseconds, numeric or string) are converted with
  nanosecond precision.
- TLS with the same `ssl_*` settings and validation rules as `logstash-input-http`, including
  optional client certificates.
- Bounded workers and pending requests (`threads`, `max_pending_requests`), body size limit after
  decompression (`max_content_length`); overload gets retryable `429`.
- Per-input counters in the Logstash node stats API.
- Credentials (`Authorization`) are accepted and ignored.
