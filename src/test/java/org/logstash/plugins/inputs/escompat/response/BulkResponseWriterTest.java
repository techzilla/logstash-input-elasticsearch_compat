package org.logstash.plugins.inputs.escompat.response;

import com.fasterxml.jackson.core.JsonFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.bulk.Action;
import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;
import org.logstash.plugins.inputs.escompat.bulk.Operation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BulkResponseWriterTest {
    private final BulkResponseWriter writer = new BulkResponseWriter(new JsonFactory());

    private static Operation op(final Action a, final String index, final String id) {
        return new Operation(a, index, id, null, null, a.producesEvent() ? Map.of() : null, null);
    }

    private String write(final List<Operation> ops, final FilterPath f, final ItemOutcome... outcomes) {
        final ByteBuf buf = writer.write(ops, outcomes, 3, f, UnpooledByteBufAllocator.DEFAULT);
        try {
            return buf.toString(StandardCharsets.UTF_8);
        } finally {
            buf.release();
        }
    }

    @Test
    void successInOrderWithIdOnlyWhenSupplied() {
        final String json = write(List.of(op(Action.CREATE, "logs-a", null), op(Action.INDEX, "logs-b", "7")),
            FilterPath.ALL, ItemOutcome.QUEUED, ItemOutcome.QUEUED);
        assertEquals("{\"took\":3,\"errors\":false,\"items\":["
            + "{\"create\":{\"_index\":\"logs-a\",\"status\":201}},"
            + "{\"index\":{\"_index\":\"logs-b\",\"_id\":\"7\",\"status\":201}}]}", json);
    }

    @Test
    void neverInventsElasticsearchFields() {
        final String json = write(List.of(op(Action.INDEX, "a", "1")), FilterPath.ALL, ItemOutcome.QUEUED);
        for (final String f : new String[] {"_version", "_seq_no", "_primary_term", "_shards", "result"}) {
            assertFalse(json.contains(f), f);
        }
    }

    @Test
    void exporterDefaultFilterExactBytes() {
        final String json = write(List.of(op(Action.CREATE, "logs-a", "1"), op(Action.CREATE, "logs-a", null)),
            FilterPath.parse(FilterPathTest.EXPORTER_DEFAULT), ItemOutcome.QUEUED, ItemOutcome.QUEUED);
        assertEquals("{\"items\":[{\"create\":{\"_index\":\"logs-a\",\"status\":201}},"
            + "{\"create\":{\"_index\":\"logs-a\",\"status\":201}}]}", json);
    }

    // ---- US2 (T034) ----

    @Test
    void errorItems() {
        final String json = write(List.of(
                op(Action.INDEX, "a", null), op(Action.UPDATE, "a", "8"), op(Action.INDEX, null, null),
                op(Action.INDEX, "a", null), op(Action.INDEX, "a", null)),
            FilterPath.ALL, ItemOutcome.QUEUED, ItemOutcome.UNSUPPORTED_ACTION, ItemOutcome.MISSING_INDEX,
            ItemOutcome.SUBMIT_FAILED, ItemOutcome.NOT_ATTEMPTED);
        assertEquals("{\"took\":3,\"errors\":true,\"items\":["
            + "{\"index\":{\"_index\":\"a\",\"status\":201}},"
            + "{\"update\":{\"_index\":\"a\",\"_id\":\"8\",\"status\":400,\"error\":{\"type\":\"illegal_argument_exception\","
            + "\"reason\":\"update is not supported by this receiver\"}}},"
            + "{\"index\":{\"status\":400,\"error\":{\"type\":\"action_request_validation_exception\","
            + "\"reason\":\"index is missing\"}}},"
            + "{\"index\":{\"_index\":\"a\",\"status\":500,\"error\":{\"type\":\"exception\","
            + "\"reason\":\"failed to queue event; it may or may not have been queued\"}}},"
            + "{\"index\":{\"_index\":\"a\",\"status\":503,\"error\":{\"type\":\"unavailable_exception\","
            + "\"reason\":\"event was not queued; retry\"}}}]}", json);
    }

    @Test
    void filterDropsEmptiedItems() {
        final String json = write(List.of(op(Action.INDEX, "a", null), op(Action.DELETE, "a", "1")),
            FilterPath.parse("items.*.error.type"), ItemOutcome.QUEUED, ItemOutcome.UNSUPPORTED_ACTION);
        assertEquals("{\"items\":[{\"delete\":{\"error\":{\"type\":\"illegal_argument_exception\"}}}]}", json);
    }
}
