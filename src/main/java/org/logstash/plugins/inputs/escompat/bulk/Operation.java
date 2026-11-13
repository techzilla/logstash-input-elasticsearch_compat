package org.logstash.plugins.inputs.escompat.bulk;

import java.util.HashMap;
import java.util.Map;

/** One parsed Bulk operation. Getters are called from the Ruby adapter. */
public final class Operation {
    private final Action action;
    private final String index;
    private final String id;
    private final String routing;
    private final String pipeline;
    private final Map<String, Object> document;
    private final ItemOutcome preStatus;

    public Operation(final Action action, final String index, final String id, final String routing,
                     final String pipeline, final Map<String, Object> document,
                     final ItemOutcome preStatus) {
        this.action = action;
        this.index = index;
        this.id = id;
        this.routing = routing;
        this.pipeline = pipeline;
        this.document = document;
        this.preStatus = preStatus;
    }

    public Action getAction() { return action; }
    public String getIndex() { return index; }
    public String getId() { return id; }
    public String getRouting() { return routing; }
    public String getPipeline() { return pipeline; }
    /** Source document; {@code null} for {@code update}/{@code delete}. */
    public Map<String, Object> getDocument() { return document; }
    /** Outcome decided before submission, or {@code null} if the operation is submittable. */
    public ItemOutcome getPreStatus() { return preStatus; }

    public boolean isSubmittable() {
        return preStatus == null;
    }

    /** Routing metadata for the event (FR-003): optional keys only when supplied. */
    public Map<String, Object> getMetadata() {
        final Map<String, Object> meta = new HashMap<>(8);
        meta.put("action", action.wireName());
        meta.put("index", index);
        if (id != null) meta.put("id", id);
        if (routing != null) meta.put("routing", routing);
        if (pipeline != null) meta.put("pipeline", pipeline);
        return meta;
    }
}
