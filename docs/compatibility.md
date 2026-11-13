# Compatibility matrix

Only combinations listed here have passed end-to-end tests. Everything else is best-effort.

| Client | Client settings | Logstash | Queue | Result |
|---|---|---|---|---|
| OTel Collector contrib `0.165.0`, `elasticsearch` exporter (`go-docappender` v2) | `version_detection.enabled: false`, `compression: none`, `discover.on_start: false`, other defaults | 9.5.6 | memory, persisted | Pass — 1,000/1,000 records ingested, 0 exporter failures, timestamps exact |

Notes:

- The exporter's default `version_detection` (`GET /`) and `compression: gzip` are not supported
  yet; with them enabled the exporter fails to start or gets `415`.
- Tested 2026-11-12 with native binaries (Docker was unavailable); the Testcontainers suite
  (`mvn verify -Pintegration`) automates the same checks.
