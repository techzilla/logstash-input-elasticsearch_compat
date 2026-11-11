# logstash-input-elasticsearch_compat Constitution

## Core Principles

### I. Honest Protocol Compatibility

A Bulk ingestion receiver, not an Elasticsearch node. Responses MUST NOT invent Elasticsearch state
(`_version`, `_seq_no`, `_primary_term`, shards, generated IDs, cluster info). Any synthetic field
or header a client needs MUST be documented and tested against that client. Search, index
management, mappings, pipelines, storage, and version conflicts are out of scope.

### II. Acknowledge Only Known Outcomes

- An item is successful only after `queue << event` completes for it. This means queued, not
  delivered or durably checkpointed.
- `update` and `delete` get item-level errors; never dropped or reported as indexed.
- After a partial failure, report only known outcomes; uncertain ones are ambiguous, never
  "unqueued".
- Response items MUST keep request order. Retry duplication MUST be documented.

### III. Bounded Resources and Backpressure

No stricter than `logstash-input-http`:

- Bound pending requests (`max_pending_requests`) and per-request body size after decompression
  (`max_content_length`).
- Netty event loops MUST NOT block on queue writes; hand requests (or bounded batches, never single
  NDJSON lines) to a fixed worker pool through a bounded queue.
- When full, refuse before submission with retryable `429` (`503` on shutdown).
- Do not assume `queue << event` can be non-blocking, cancelled, or timed out.

### IV. Permissive Content, Strict Framing

- Validate framing, UTF-8, and JSON for the whole request before queuing any of it; malformed
  requests get `400` with nothing queued.
- Unknown or unimplemented Elasticsearch options MUST NOT cause rejection; ignore them.
- Honor `filter_path` for patterns supported clients use; reject unsupported expressions clearly.
- Target index: action `_index`, then URL index, else item-level error. Never invent one.
- Routing metadata goes under `[@metadata][elasticsearch_compat]` (configurable); never overwrite
  document fields.

### V. Real-Client Evidence Drives Scope

New endpoints, response fields, settings, or limits require the Bulk protocol, safe operation, or a
failing test against a named, pinned client. Compatibility claims require end-to-end tests recorded
in a client/version matrix; other clients are best-effort.

### VI. Accept Any Credentials, Enforce None

- Never authenticate. Requests with no `Authorization` header or any scheme are processed alike;
  never `401`/`403`.
- Drop the `Authorization` header before building events; never log, store, or echo credentials.
- Support TLS. Do not log document bodies by default. Keep metrics low-cardinality.

## Architecture Constraints

- Ruby Logstash input gem bundling a precompiled Java (Netty) JAR, built with Maven.
- Java owns HTTP, TLS, routing, limits, gzip, Bulk parsing, metadata, and responses. It MUST NOT
  depend on Logstash classes (`logstash-core.jar`, `co.elastic.logstash.api`).
- Ruby owns lifecycle, config, event creation, `decorate`, `queue << event`, and reporting outcomes
  to Java. Standard input options (`id`, `tags`, `add_field`) MUST work.
- No codec-based Bulk parsing; no own durable queue. `logstash-input-http` and
  `logstash-codec-es_bulk` are references, not dependencies.
- Pin Netty and JSON-parser versions and check them for vulnerabilities.
- Supported Logstash versions are only those the built gem is tested against.

## Quality Gates

- Test at the lowest tier that catches the failure: Java unit tests; integration tests on real
  Logstash with memory and persistent queues; pinned real-client tests (OTel/EDOT exporter,
  `go-docappender`) for client-facing behavior.
- A feature is done only when its acknowledgment, overload, and error paths are tested.
- Plans MUST pass a Constitution Check; deviations need recorded justification.

## Governance

This constitution overrides conflicting practices and documents. Amend via `/speckit-constitution`,
committed separately from feature code. Versioning: MAJOR redefines or removes a principle, MINOR
adds one or materially expands guidance, PATCH clarifies wording.

**Version**: 1.0.0 | **Ratified**: 2026-11-11 | **Last Amended**: 2026-11-11
