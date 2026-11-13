# Feature Specification: OTel Bulk Ingest

**Feature Branch**: `001-otel-bulk-ingest`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "A Logstash input that accepts Elasticsearch Bulk API requests on POST /_bulk from the OpenTelemetry Collector Elasticsearch exporter and emits one Logstash event per index/create action, with routing metadata, returning an ordered Bulk response only after events are queued. update/delete return item-level errors."

## Clarifications

### Session 2026-11-12

- Q: What sustained throughput should one instance reach before this feature is done? → A: At
  least `logstash-input-http` (`json_lines` codec) on the same hardware, payload, and pipeline.
- Q: How much time may the plugin add per Bulk request beyond queue-acceptance time? → A: p99
  under 10 ms for 1,000 items, growing no faster than linearly with item count.
- Q: What concurrency and batch shape should performance targets be measured with? → A: The
  pinned exporter's default concurrency and batching (discovery and compression off), sending
  realistic OTel log documents.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Exporter ships telemetry into Logstash (Priority: P1)

An operator points an OpenTelemetry Collector's Elasticsearch exporter at Logstash instead of
Elasticsearch. Each `index` or `create` document becomes one Logstash event carrying its original
fields plus routing metadata, and the exporter receives a Bulk response it accepts as success.

**Why this priority**: This is the whole point of the plugin; nothing else matters without it.

**Independent Test**: Run a pinned exporter against a Logstash pipeline using the plugin, send
logs, and confirm one event per document with correct fields and metadata, and no exporter errors.

**Acceptance Scenarios**:

1. **Given** a running pipeline, **When** the exporter sends a Bulk request with N `index`/`create`
   actions, **Then** exactly N events are queued and the response reports N successful items in
   request order.
2. **Given** an action naming `_index` and `_id`, **When** it is ingested, **Then** the event's
   routing metadata holds the action name, index, and ID, and document fields are unchanged.
3. **Given** a request to `/_bulk` whose action omits `_index`, **When** it is processed, **Then**
   that item gets an item-level error and no event is created for it.
4. **Given** a request with unknown or unimplemented Elasticsearch options, **When** it is
   processed, **Then** the options are ignored and documents are ingested normally.

---

### User Story 2 - Unsupported and malformed input is never falsely acknowledged (Priority: P2)

A client sends `update` or `delete` actions, or a broken request. The client is told the truth so
it does not believe data was ingested or deleted when it was not.

**Why this priority**: Truthful acknowledgment is a core constitutional rule and prevents silent
data loss.

**Independent Test**: Send mixed and malformed Bulk bodies directly and inspect responses and the
pipeline's received events.

**Acceptance Scenarios**:

1. **Given** a request mixing `index`, `update`, and `delete`, **When** processed, **Then** `index`
   items succeed, `update`/`delete` items carry item-level errors, the response flags errors, and
   item order matches the request.
2. **Given** a request with invalid JSON, invalid UTF-8, or broken action/source pairing, **When**
   processed, **Then** the whole request is rejected as a bad request and no events are queued.
3. **Given** events cannot be queued for some items after others succeeded, **When** the response
   is sent, **Then** it reports only known outcomes and never claims the queued items were not
   queued.

---

### User Story 3 - Overload is refused safely (Priority: P3)

When the pipeline is backed up, new requests are refused with a retryable response instead of
piling up or hanging the listener.

**Why this priority**: Needed for safe operation but not for a first demonstration.

**Independent Test**: Block the pipeline, send more requests than the pending limit, and confirm
retryable refusals while the listener keeps answering.

**Acceptance Scenarios**:

1. **Given** the pending-request limit is reached, **When** another request arrives, **Then** it
   is refused with a retryable "too many requests" response before anything from it is queued.
2. **Given** a body larger than the size limit, **When** it arrives, **Then** it is rejected as too
   large and nothing from it is queued.

### Edge Cases

- Empty body, or body missing the final newline.
- Compressed body (e.g. gzip): rejected as an unsupported encoding, nothing queued.
- Action line without its source line, or `delete` followed by a stray source line.
- Unknown action names that make framing ambiguous: reject the request.
- Requests with no credentials, or with any `Authorization` header: processed the same way.
- Document fields that collide with Logstash reserved fields (e.g. `@timestamp`).
- Client disconnects mid-request: events already queued stay queued.
- Pipeline shutdown while requests are in flight: unavailable response where it is still safe to
  respond.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The plugin MUST accept Bulk NDJSON requests on `POST /_bulk`.
- **FR-002**: Each `index` or `create` action MUST produce exactly one event containing the source
  document's fields and nesting, as far as the Logstash event model allows.
- **FR-003**: Each event MUST carry routing metadata under `[@metadata][elasticsearch_compat]`
  (configurable): action, resolved index, and `_id`, `routing`, `pipeline` when supplied. Document
  fields MUST NOT be overwritten.
- **FR-004**: The target index MUST be the action's `_index`; if absent, the item MUST receive an
  item-level error.
- **FR-005**: `update` and `delete` actions MUST be parsed for framing and receive item-level
  errors; they MUST NOT be reported as successful.
- **FR-006**: The whole request's framing, UTF-8, and JSON MUST be validated before any event from
  it is queued; invalid requests MUST be rejected as bad requests with nothing queued.
- **FR-007**: An item MUST be reported successful only after its event has been accepted by the
  Logstash queue.
- **FR-008**: The response MUST be Elasticsearch Bulk-shaped (`took`, `errors`, ordered `items`
  with action name, `_index`, `_id` when supplied, and numeric status), and MUST NOT contain
  invented Elasticsearch fields such as versions, sequence numbers, or shard counts.
- **FR-009**: The response MUST honor the `filter_path` patterns the pinned exporter sends.
- **FR-010**: Unknown or unimplemented Elasticsearch query and action options MUST be ignored, not
  rejected.
- **FR-011**: Requests MUST NOT be authenticated; requests with or without credentials MUST be
  handled identically, and credentials MUST NOT appear in events, logs, or responses.
- **FR-012**: The plugin MUST bound pending requests and per-request body size, refusing excess
  with retryable "too many requests" or "too large" responses before queuing anything.
- **FR-013**: Standard Logstash input options (`id`, `tags`, `add_field`) MUST work on emitted
  events.
- **FR-014**: The reference exporter MUST be tested with version discovery and compression
  disabled. Compressed bodies and the `GET /` discovery probe are not supported in this feature.
- **FR-015**: Every response MUST carry `X-Elastic-Product: Elasticsearch`. The pinned exporter
  rejects a server whose first successful response lacks it. This is documented compatibility
  behavior, not a claim to be Elasticsearch.

### Key Entities

- **Bulk request**: An ordered list of operations, each an action line and, for
  `index`/`create`/`update`, a source document.
- **Operation**: Action name, optional target index, ID, routing, and pipeline, plus its document.
- **Event**: One Logstash event per accepted `index`/`create` operation, holding the document and
  routing metadata.
- **Bulk response**: Overall error flag and one result per operation, in request order.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With the pinned exporter, 100% of sent log records arrive as events, with zero
  exporter response-decoding errors.
- **SC-002**: For any accepted request of N `index`/`create` items, exactly N events are queued
  and N success items are returned, in order.
- **SC-003**: 0 `update`/`delete` items are ever reported as successful.
- **SC-004**: 0 events are queued from malformed, oversized, or refused-for-overload requests.
- **SC-005**: While the pipeline is blocked, the listener still answers new requests (with
  refusals) within 1 second.
- **SC-006**: On the benchmark workload, sustained events per second are at least those of
  `logstash-input-http` with the `json_lines` codec on the same hardware, payload, and pipeline,
  within 5% measurement noise (median of 3 runs each).
- **SC-007**: On the benchmark workload, time the plugin adds to a request (request handling plus
  event construction), excluding time waiting for the Logstash queue, is under 10 ms at the 99th percentile for 1,000 items and grows no
  faster than linearly with item count.

## Assumptions

- Out of scope for this feature: `PUT /_bulk`, `/{index}/_bulk`, TLS, gzip, `GET`/`HEAD /`,
  response filters beyond the exporter's patterns, metrics, and other clients. A follow-up feature
  adds gzip and the discovery probe, driven by a failing test with exporter defaults.
- TLS is required before release; delivered in feature 002.
- One pinned OpenTelemetry Collector contrib release is the reference client; others are
  best-effort.
- Success means "queued in Logstash", not delivered downstream; retries may create duplicates, and
  this is documented.
- Default limits follow `logstash-input-http` (200 pending requests, 100 MB body).
- Benchmark workload: the pinned exporter with its default concurrency and batching (discovery
  and compression off), sending realistic OTel log documents.
- Both Logstash memory and persistent queues work through the standard input queue.
