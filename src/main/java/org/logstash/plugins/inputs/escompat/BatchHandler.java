package org.logstash.plugins.inputs.escompat;

import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;
import org.logstash.plugins.inputs.escompat.bulk.Operation;

import java.util.List;

/**
 * Java → Ruby handoff, called once per request from a worker thread (never an event loop).
 * Implemented by the Ruby adapter.
 */
public interface BatchHandler {
    /**
     * Queue one event per operation, in order.
     *
     * @param ops     submittable operations only, in request order
     * @param results same length as {@code ops}; set {@link ItemOutcome#QUEUED} after a push
     *                completes or {@link ItemOutcome#SUBMIT_FAILED} if it raised. Entries left
     *                {@code null} are reported as {@link ItemOutcome#NOT_ATTEMPTED}.
     */
    void submit(List<Operation> ops, ItemOutcome[] results);
}
