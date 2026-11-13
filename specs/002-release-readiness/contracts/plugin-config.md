# Contract: Configuration and Metrics (additions to 001)

Base: [001 plugin-config.md](../../001-otel-bulk-ingest/contracts/plugin-config.md).

## New settings

| Setting | Type | Default | Notes |
|---|---|---|---|
| `compression_enabled` | boolean | `true` | Response compression only; request decompression is always on |
| `ssl_enabled` | boolean | `false` | |
| `ssl_certificate` | path | — | PEM; requires `ssl_key` |
| `ssl_key` | path | — | PEM, PKCS#8 (PKCS#1 rejected, as in `logstash-input-http`); requires `ssl_certificate` |
| `ssl_key_passphrase` | password | — | Encrypted key |
| `ssl_keystore_path` | path | — | Alternative to certificate/key |
| `ssl_keystore_password` | password | — | |
| `ssl_keystore_type` | `pkcs12`/`jks` | — | Unset: detected as `logstash-input-http` does |
| `ssl_certificate_authorities` | array of paths | `[]` | Trust for client certificates |
| `ssl_truststore_path` | path | — | Alternative to certificate authorities |
| `ssl_truststore_password` | password | — | |
| `ssl_client_authentication` | `none`/`optional`/`required` | `none` | |
| `ssl_supported_protocols` | list of `TLSv1.1`/`TLSv1.2`/`TLSv1.3` | `["TLSv1.2","TLSv1.3"]` | JDK disables TLSv1.1 by default |
| `ssl_cipher_suites` | list (JDK-supported names) | `logstash-input-http` default list | |
| `ssl_handshake_timeout` | number (ms) | `10000` | |

Validation (startup error unless noted), identical to `logstash-input-http`:

1. `ssl_certificate` without `ssl_key`, or `ssl_key` without `ssl_certificate`.
2. `ssl_enabled` with neither a certificate nor a keystore, or with both.
3. Both `ssl_certificate_authorities` and `ssl_truststore_path`.
4. `ssl_client_authentication` `optional`/`required` with no certificate authorities, truststore,
   or keystore.
5. `ssl_certificate_authorities` or `ssl_truststore_path` with `ssl_client_authentication => none`.
6. Any `ssl_*` setting with `ssl_enabled => false`: warning, not an error.

## Metrics (node stats)

`GET :9600/_node/stats/pipelines/<id>` → `pipelines.<id>.plugins.inputs[]` entry for this input
gains, alongside Logstash's own `events`:

```json
{
  "bulk_requests": 42,
  "bulk_requests_rejected_overload": 3,
  "bulk_requests_rejected_too_large": 0,
  "bulk_requests_rejected_unsupported_encoding": 0,
  "bulk_requests_rejected_bad_request": 1,
  "bulk_items_failed": 7,
  "queue_submit_failures": 0
}
```

Semantics: [data-model.md](../data-model.md#plugin-counters). Values lag by at most about 1 s.
Absent when `enable_metric => false`.
