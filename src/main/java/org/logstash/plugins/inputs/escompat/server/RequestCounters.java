package org.logstash.plugins.inputs.escompat.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cumulative per-input counters (data-model.md "Plugin counters"). Updated once per request, never
 * per event; published to Logstash's metrics by the Ruby adapter.
 */
public final class RequestCounters {
    public static final String BULK_REQUESTS = "bulk_requests";
    public static final String REJECTED_OVERLOAD = "bulk_requests_rejected_overload";
    public static final String REJECTED_TOO_LARGE = "bulk_requests_rejected_too_large";
    public static final String REJECTED_UNSUPPORTED_ENCODING = "bulk_requests_rejected_unsupported_encoding";
    public static final String REJECTED_BAD_REQUEST = "bulk_requests_rejected_bad_request";
    public static final String ITEMS_FAILED = "bulk_items_failed";
    public static final String QUEUE_SUBMIT_FAILURES = "queue_submit_failures";

    private final LongAdder bulkRequests = new LongAdder();
    private final LongAdder rejectedOverload = new LongAdder();
    private final LongAdder rejectedTooLarge = new LongAdder();
    private final LongAdder rejectedUnsupportedEncoding = new LongAdder();
    private final LongAdder rejectedBadRequest = new LongAdder();
    private final LongAdder itemsFailed = new LongAdder();
    private final LongAdder queueSubmitFailures = new LongAdder();

    public void bulkRequest() { bulkRequests.increment(); }
    public void rejectedOverload() { rejectedOverload.increment(); }
    public void rejectedTooLarge() { rejectedTooLarge.increment(); }
    public void rejectedUnsupportedEncoding() { rejectedUnsupportedEncoding.increment(); }
    public void rejectedBadRequest() { rejectedBadRequest.increment(); }

    public void itemsFailed(final long n) {
        if (n > 0) itemsFailed.add(n);
    }

    public void queueSubmitFailures(final long n) {
        if (n > 0) queueSubmitFailures.add(n);
    }

    /** Current totals, keyed by the names exposed in node stats. */
    public Map<String, Long> snapshot() {
        final Map<String, Long> m = new LinkedHashMap<>(8);
        m.put(BULK_REQUESTS, bulkRequests.sum());
        m.put(REJECTED_OVERLOAD, rejectedOverload.sum());
        m.put(REJECTED_TOO_LARGE, rejectedTooLarge.sum());
        m.put(REJECTED_UNSUPPORTED_ENCODING, rejectedUnsupportedEncoding.sum());
        m.put(REJECTED_BAD_REQUEST, rejectedBadRequest.sum());
        m.put(ITEMS_FAILED, itemsFailed.sum());
        m.put(QUEUE_SUBMIT_FAILURES, queueSubmitFailures.sum());
        return m;
    }
}
