# Compatibility matrix

Only combinations listed here have passed end-to-end tests. Everything else is best-effort.

| Client | Client settings | Logstash | Queue | Transport | Result |
|---|---|---|---|---|---|
| OTel Collector contrib `0.165.0`, `elasticsearch` exporter (`go-docappender` v2) | Defaults (only `endpoint`: gzip and version detection on) | 9.5.6 | memory | HTTP | Pass |
| same | `version_detection` off, `compression: none`, `discover.on_start: false` | 9.5.6 | memory, persisted | HTTP | Pass |
| same | Defaults + `tls.ca_file` | 9.5.6 | memory | HTTPS | Pass |

Tested 2026-11-13 with the Testcontainers suite (`mvn verify -Pintegration`): 1,000/1,000 records
per run, 0 exporter failures, exact timestamps.
