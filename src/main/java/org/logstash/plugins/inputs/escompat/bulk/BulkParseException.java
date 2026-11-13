package org.logstash.plugins.inputs.escompat.bulk;

/** The request body is not valid Bulk NDJSON; the whole request is rejected with 400. */
public final class BulkParseException extends Exception {
    private static final long serialVersionUID = 1L;

    public BulkParseException(final String reason) {
        super(reason, null, false, false);
    }
}
