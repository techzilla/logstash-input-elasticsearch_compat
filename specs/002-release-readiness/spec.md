# Feature Specification: Release Readiness

**Feature Branch**: `002-release-readiness`

**Created**: 2026-11-13

**Status**: Draft

**Input**: User description: "Release readiness for the elasticsearch_compat input. (1) The OTel Collector elasticsearch exporter works with its default settings: accept gzip and deflate request bodies (size limit applies after decompression), compress responses when the client accepts it, with a compression_enabled option (default true) to turn compression off; answer the exporter's version_detection GET / with a minimal, documented identity response. (2) Bulk routes: POST and PUT on both /_bulk and /{index}/_bulk; on /{index}/_bulk the URL index is used only when an action omits _index. (3) TLS with the same ssl_* settings, defaults, allowed values and validation rules as logstash-input-http (certificate/key, keystore, truststore, certificate authorities, client authentication, protocols, cipher suites, handshake timeout). (4) Low-cardinality plugin metrics exposed through Logstash's standard node stats API under this input and honoring enable_metric: requests, 429/413/415/400 refusals, item errors, queue submission failures; updated per request, never per event. Performance must not regress: SC-006 and SC-007 from feature 001 still pass with compression, TLS and metrics enabled. (5) Release packaging: Apache-2.0 LICENSE file, NOTICE that preserves the notices of all shaded dependencies (Netty and Jackson), CHANGELOG, gemspec metadata cleanup (required_ruby_version), Logstash plugin reference docs (docs/index.asciidoc), and a documented release process (version bump, tag, RubyGems publish)."

## Clarifications

### Session 2026-11-13

- Q: Should the `logstash-input-http` baseline also run with TLS and compression in the
  throughput comparison? → A: Yes; both plugins run with TLS and gzip-compressed requests (same
  certificate, same bodies); this plugin also has metrics on.
- Q: How should compression behave? → A: Match Elasticsearch: request bodies are always
  decompressed (gzip/deflate); responses are compressed whenever the client accepts it, with no
  size threshold, at compression level 3; `compression_enabled` (default true, like
  Elasticsearch's `http.compression`) turns off response compression only.
- Q: Should the `requests` counter include refused requests? → A: Yes; it counts every Bulk
  request received, refusal counters break it down by kind, and `GET /`/`HEAD /` are not counted.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Exporter works with its default settings (Priority: P1)

An operator points an unmodified OTel Collector `elasticsearch` exporter at Logstash. The
exporter's default gzip-compressed requests and its startup version probe are handled, and data
flows without changing any exporter setting.

**Why this priority**: Feature 001 required disabling two exporter defaults; most users will not.

**Independent Test**: Run the pinned exporter with default settings (only the endpoint set)
against the plugin and confirm every record arrives with no exporter errors.

**Acceptance Scenarios**:

1. **Given** default plugin settings, **When** the exporter sends gzip-compressed Bulk requests,
   **Then** they are decompressed, ingested exactly as uncompressed requests are, and acknowledged.
2. **Given** a deflate-compressed request, **When** it arrives, **Then** it is handled the same way.
3. **Given** a compressed body whose decompressed size exceeds the size limit, **When** it
   arrives, **Then** it is refused as too large and nothing from it is queued.
4. **Given** a client that accepts compressed responses, **When** it sends a request, **Then** the
   response may be compressed and decodes to the same content.
5. **Given** the exporter's startup version probe on `GET /`, **When** it runs, **Then** it receives
   the documented identity response and the exporter starts without warnings about it.
6. **Given** `compression_enabled => false`, **When** a compressed request arrives, **Then** it is
   still decompressed and ingested, and the response is not compressed.

---

### User Story 2 - Traffic can be encrypted (Priority: P1)

An operator enables TLS using the same `ssl_*` settings they already use with
`logstash-input-http`, optionally requiring client certificates.

**Why this priority**: Required before production use (feature 001 assumption).

**Independent Test**: Configure each supported TLS setup; connect with clients that should and
should not succeed; check ingestion over TLS with the exporter.

**Acceptance Scenarios**:

1. **Given** `ssl_enabled` with a PEM certificate and key, **When** a client connects over TLS,
   **Then** Bulk requests succeed; plain-HTTP connections to that port fail.
2. **Given** a keystore instead of PEM files, **When** the plugin starts, **Then** TLS works the same.
3. **Given** `ssl_client_authentication => required` with certificate authorities, **When** a client
   presents no or an untrusted certificate, **Then** the handshake fails; a trusted certificate
   succeeds.
4. **Given** an invalid combination of TLS settings (one that `logstash-input-http` rejects),
   **When** the pipeline starts, **Then** it fails with a clear configuration error.
5. **Given** a client offering only a protocol or cipher outside the configured lists, **When** it
   connects, **Then** the handshake fails.

---

### User Story 3 - Any standard Bulk route works (Priority: P2)

A client using `PUT` or an index-scoped path (`/{index}/_bulk`) is accepted like `POST /_bulk`.

**Why this priority**: Widens client compatibility; the exporter itself uses `POST /_bulk`.

**Independent Test**: Send identical bodies via all four method/path combinations and compare
responses and events.

**Acceptance Scenarios**:

1. **Given** the same body, **When** sent with POST or PUT to `/_bulk` or `/{index}/_bulk`,
   **Then** responses and events are identical apart from index resolution.
2. **Given** `/{index}/_bulk` and an action without `_index`, **When** processed, **Then** the
   event's index is the URL index.
3. **Given** `/{index}/_bulk` and an action with `_index`, **When** processed, **Then** the action's
   `_index` wins.

---

### User Story 4 - Operators can see what the plugin is doing (Priority: P2)

An operator reads request, refusal, and failure counts for this input from Logstash's standard
monitoring API and existing monitoring tools.

**Why this priority**: Needed to run the plugin in production; not needed for data to flow.

**Independent Test**: Drive known numbers of accepted, refused, and failing requests; read the
counts from the node stats API.

**Acceptance Scenarios**:

1. **Given** N accepted requests, R refusals of each kind, and E item errors, **When** stats are
   read, **Then** the counters show exactly those numbers under this input's entry.
2. **Given** `enable_metric => false`, **When** stats are read, **Then** this input reports no
   custom counters.

---

### User Story 5 - The plugin can be released (Priority: P3)

A maintainer publishes a release that meets licensing obligations and has user documentation.

**Why this priority**: Required to ship; independent of runtime behavior.

**Independent Test**: Build the gem and inspect its contents; follow the documented release
process in a dry run.

**Acceptance Scenarios**:

1. **Given** the built gem, **When** inspected, **Then** it contains the Apache-2.0 license and a
   notice file that preserves the notices of every bundled third-party library.
2. **Given** the docs, **When** read, **Then** every setting is documented in the standard Logstash
   plugin reference format, and a changelog lists this release.
3. **Given** the release process document, **When** a maintainer follows it, **Then** they can bump
   the version, tag, and publish without other knowledge.

### Edge Cases

- Unknown `Content-Encoding` (e.g. `br`): refused as unsupported, nothing queued.
- Corrupt gzip data: refused as a bad request, nothing queued.
- Compressed body that expands far beyond its compressed size (decompression bomb): stopped at the
  size limit without exhausting memory.
- `Accept-Encoding` absent or `identity`: response is never compressed.
- `GET /` and `HEAD /` with TLS enabled behave the same as without TLS.
- `/{index}/_bulk` with an empty or malformed index segment: request refused, nothing queued.
- TLS handshake timeout: connection closed, no request processed.
- Metrics disabled: request handling unchanged.

## Requirements *(mandatory)*

### Functional Requirements

**Compression and identity (US1)**

- **FR-001**: Request bodies with `Content-Encoding` `gzip` or `deflate` (and their `x-gzip`/
  `x-deflate` aliases) MUST always be
  decompressed before parsing, regardless of `compression_enabled` (as Elasticsearch does).
- **FR-002**: `max_content_length` MUST apply to the decompressed body; decompression MUST stop at
  the limit and refuse the request as too large, with nothing queued.
- **FR-003**: Corrupt compressed data MUST be refused as a bad request; unknown encodings MUST be
  refused as unsupported; nothing from either is queued.
- **FR-004**: With `compression_enabled` true (the default), every response MUST be compressed when
  the client accepts `gzip` or `deflate`, with no size threshold, at compression level 3 (matching
  Elasticsearch's `http.compression` and `http.compression_level` defaults).
- **FR-005**: With `compression_enabled` false, responses MUST NOT be compressed; request
  decompression is unaffected.
- **FR-006**: `GET /` and `HEAD /` MUST return a minimal identity response sufficient for the
  exporter's version detection, carrying the product header. It reports a fixed version number
  below 9.4 (`8.19.0`), so the exporter keeps its most widely compatible metrics format (9.4+
  enables exponential and 9.5+ cumulative histograms). The response's content and rationale MUST
  be documented as a compatibility placeholder, not a claim to be Elasticsearch.

**Routes (US3)**

- **FR-007**: `POST` and `PUT` MUST be accepted on `/_bulk` and `/{index}/_bulk` with identical
  behavior; other methods on these paths get "method not allowed".
- **FR-008**: On `/{index}/_bulk`, an action's `_index` MUST take precedence; the URL index MUST be
  used only when the action omits `_index` (replaces feature 001 FR-004's error for that case).

**TLS (US2)**

- **FR-009**: The plugin MUST offer the same TLS settings as `logstash-input-http` with the same
  names, defaults, and allowed values: `ssl_enabled` (false), `ssl_certificate`, `ssl_key`,
  `ssl_key_passphrase`, `ssl_keystore_path`, `ssl_keystore_password`, `ssl_keystore_type`
  (`pkcs12`/`jks`), `ssl_certificate_authorities` ([]), `ssl_truststore_path`,
  `ssl_truststore_password`, `ssl_client_authentication` (`none`/`optional`/`required`, default
  `none`), `ssl_supported_protocols` (`TLSv1.1`/`TLSv1.2`/`TLSv1.3`, default `TLSv1.2`,`TLSv1.3`),
  `ssl_cipher_suites` (its default list, limited to those the runtime supports),
  `ssl_handshake_timeout` (10000 ms).
- **FR-010**: Invalid TLS setting combinations MUST be rejected at startup with the same rules as
  `logstash-input-http`; deprecated legacy names are not supported.

**Metrics (US4)**

- **FR-011**: The plugin MUST report, under this input in Logstash's node stats, counters for:
  Bulk requests received (every request on a Bulk route, including refused ones; `GET /` and
  `HEAD /` excluded); requests refused for overload (429), oversized body (413), unsupported
  encoding (415), and bad request (400), each a subset of requests received; item-level errors;
  and queue submission failures. Wrong-method requests on Bulk paths (405) are not counted.
  Counts are visible in node stats within about 1 second.
- **FR-012**: Counters MUST be updated per request, never per event, and MUST NOT be labeled by
  index, client, or other unbounded values.
- **FR-013**: `enable_metric => false` MUST suppress these counters.

**Performance (all)**

- **FR-014**: With compression, TLS, and metrics enabled, feature 001's SC-006 and SC-007 MUST
  still pass. For SC-006 the `logstash-input-http` baseline runs with the same TLS certificate
  and the same gzip-compressed bodies; this plugin also has metrics enabled.

**Release packaging (US5)**

- **FR-015**: The gem MUST include an Apache-2.0 license file and a notice file containing the
  notices of every bundled third-party library.
- **FR-016**: The project MUST have a changelog with an entry for this release.
- **FR-017**: The gemspec MUST declare the supported Ruby version, license, summary, description,
  authors, and `logstash_plugin`/`logstash_group` metadata (homepage optional), building
  without warnings.
- **FR-018**: User documentation MUST follow the Logstash plugin reference format and cover every
  setting, including compression, TLS, routes, metrics, and limitations.
- **FR-019**: A release process document MUST describe version bump, tagging, and publishing.

### Key Entities

- **Identity response**: Minimal server description returned on `GET /`; documented compatibility
  behavior, not a claim to be Elasticsearch.
- **TLS configuration**: The `ssl_*` settings and their validation rules.
- **Plugin counters**: Request-level counts per refusal kind and failure kind for one input.
- **Release artifact**: The gem with code, license, notice, and docs.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The pinned exporter with only its endpoint configured delivers 100% of sent records,
  with zero exporter errors and no version-probe warnings.
- **SC-002**: Compressed and uncompressed forms of the same request produce identical events and
  item results; 0 events are queued from oversized-after-decompression, corrupt, or unsupported
  encodings.
- **SC-003**: All four method/path combinations produce identical events for the same body.
- **SC-004**: Every TLS setup accepted by `logstash-input-http`'s rules works here, and every
  combination it rejects is rejected here (tested setting by setting).
- **SC-005**: Counters match the exact number of driven requests, refusals, and failures.
- **SC-006**: Feature 001's SC-006 (median events/s ≥ 95% of `logstash-input-http`, both with TLS
  and gzip-compressed requests) and SC-007 (p99 < 20 ms at 1,000 items) pass with compression,
  TLS, and metrics enabled.
- **SC-007**: The release gem passes a license-content check (license plus all third-party
  notices present) and the release process succeeds in a dry run.

## Assumptions

- Compression behavior mirrors Elasticsearch; TLS settings mirror `logstash-input-http`;
  `compression_enabled` is the only new non-TLS setting.
- Plugin counters appear in Logstash's node stats and therefore in Elastic Stack Monitoring and
  common exporters without extra work.
- Out of scope: CI, soak testing, OWASP dependency audit, Logstash 8.19.x support, and Bulk APIs
  other than `_bulk`.
- RubyGems publishing uses the maintainer's own account; credentials are not part of the repo.
