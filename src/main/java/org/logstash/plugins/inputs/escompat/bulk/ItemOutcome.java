package org.logstash.plugins.inputs.escompat.bulk;

/**
 * Result of one Bulk operation. Status codes follow the exporter's retry rules: 400 is final,
 * 500 and 503 are retried.
 */
public enum ItemOutcome {
    /** The event was accepted by the Logstash queue. */
    QUEUED(201, null),
    /** {@code update} or {@code delete}: never executed, never reported as success. */
    UNSUPPORTED_ACTION(400, "illegal_argument_exception"),
    /** No {@code _index} on the action. */
    MISSING_INDEX(400, "action_request_validation_exception"),
    /** The document cannot be represented as a Logstash event (e.g. non-object {@code @metadata}). */
    INVALID_DOCUMENT(400, "document_parsing_exception"),
    /** Building or queuing the event raised; whether it reached the queue is unknown. */
    SUBMIT_FAILED(500, "exception"),
    /** Never attempted (after an earlier failure, or during shutdown); safe to retry. */
    NOT_ATTEMPTED(503, "unavailable_exception");

    private final int status;
    private final String errorType;

    ItemOutcome(final int status, final String errorType) {
        this.status = status;
        this.errorType = errorType;
    }

    public int status() {
        return status;
    }

    /** @return Elasticsearch-style error type, or {@code null} for success */
    public String errorType() {
        return errorType;
    }

    public boolean isError() {
        return status >= 300;
    }

    public String reason(final Action action) {
        switch (this) {
            case UNSUPPORTED_ACTION: return action.wireName() + " is not supported by this receiver";
            case MISSING_INDEX: return "index is missing";
            case INVALID_DOCUMENT: return "[@metadata] must be an object";
            case SUBMIT_FAILED: return "failed to queue event; it may or may not have been queued";
            case NOT_ATTEMPTED: return "event was not queued; retry";
            default: return null;
        }
    }
}
