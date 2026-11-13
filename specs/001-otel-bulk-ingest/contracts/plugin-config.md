# Contract: Logstash configuration

```ruby
input {
  elasticsearch_compat {
    port => 9200
  }
}
```

| Setting | Type | Default | Notes |
|---|---|---|---|
| `host` | string | `"0.0.0.0"` | Listen address |
| `port` | number | `9200` | Listen port |
| `threads` | number | CPU cores | Worker pool size |
| `max_pending_requests` | number | `200` | Bounded queue in front of workers |
| `max_content_length` | bytes | `104857600` | Per-request body limit |
| `metadata_target` | field ref | `"[@metadata][elasticsearch_compat]"` | Routing metadata location |

Standard input options (`id`, `tags`, `add_field`, `type`, `enable_metric`) apply. No
authentication or TLS settings in this feature.

## Event contract

- Event fields = source document (FR-002).
- `[@metadata][elasticsearch_compat]`: `action`, `index`, and `id`, `routing`, `pipeline` when
  supplied (FR-003). `@metadata` is not serialized by outputs; promote fields explicitly if
  needed downstream.
- Reserved fields (standard Logstash behavior):
  - Valid `@timestamp` → becomes the event timestamp.
  - Invalid `@timestamp` → tag `_timestampparsefailure`, original value kept in `_@timestamp`.
  - Missing `@timestamp` → ingest time.
  - Document's own `@metadata` → kept; only the `metadata_target` subkey is overwritten.
