package org.logstash.plugins.inputs.escompat.bulk;

import com.fasterxml.jackson.core.JsonFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkParserTest {
    private final BulkParser parser = new BulkParser(new JsonFactory());

    private List<Operation> parse(final String body) throws BulkParseException {
        final byte[] b = body.getBytes(StandardCharsets.UTF_8);
        return parser.parse(b, 0, b.length);
    }

    private void assertRejected(final byte[] body) {
        assertThrows(BulkParseException.class, () -> parser.parse(body, 0, body.length));
    }

    private void assertRejected(final String body) {
        assertRejected(body.getBytes(StandardCharsets.UTF_8));
    }

    // ---- happy paths (US1, T022) ----

    @Test
    void indexAndCreateMapMetadata() throws Exception {
        final List<Operation> ops = parse(
            "{\"index\":{\"_index\":\"logs-a\",\"_id\":\"1\",\"routing\":\"r1\",\"pipeline\":\"p1\"}}\n"
                + "{\"message\":\"hi\"}\n"
                + "{\"create\":{\"_index\":\"logs-b\"}}\n"
                + "{\"message\":\"yo\"}\n");
        assertEquals(2, ops.size());
        final Operation a = ops.get(0);
        assertEquals(Action.INDEX, a.getAction());
        assertEquals("logs-a", a.getIndex());
        assertEquals("1", a.getId());
        assertEquals("r1", a.getRouting());
        assertEquals("p1", a.getPipeline());
        assertEquals("hi", a.getDocument().get("message"));
        assertTrue(a.isSubmittable());
        assertEquals(Map.of("action", "index", "index", "logs-a", "id", "1", "routing", "r1",
            "pipeline", "p1"), a.getMetadata());

        final Operation b = ops.get(1);
        assertEquals(Action.CREATE, b.getAction());
        assertNull(b.getId());
        assertEquals(Map.of("action", "create", "index", "logs-b"), b.getMetadata());
    }

    @Test
    void legacyRoutingKey() throws Exception {
        assertEquals("r", parse("{\"index\":{\"_index\":\"a\",\"_routing\":\"r\"}}\n{}\n").get(0).getRouting());
    }

    @Test
    void missingIndexIsItemError() throws Exception {
        final Operation op = parse("{\"index\":{}}\n{\"a\":1}\n").get(0);
        assertEquals(ItemOutcome.MISSING_INDEX, op.getPreStatus());
        assertFalse(op.isSubmittable());
    }

    @Test
    void unknownActionOptionsIgnored() throws Exception {
        final Operation op = parse("{\"create\":{\"_index\":\"a\",\"dynamic_templates\":{\"x\":\"y\"},"
            + "\"require_alias\":true,\"require_data_stream\":true,\"version\":3,\"list\":[1,2]}}\n"
            + "{\"a\":1}\n").get(0);
        assertTrue(op.isSubmittable());
        assertEquals("a", op.getIndex());
    }

    @Test
    void valuesAndNesting() throws Exception {
        final Map<String, Object> doc = parse("{\"index\":{\"_index\":\"a\"}}\n"
            + "{\"s\":\"x\",\"i\":42,\"big\":123456789012345678901234567890,\"f\":1.5,\"t\":true,"
            + "\"n\":null,\"o\":{\"p\":{\"q\":[1,\"two\",{\"three\":3}]}}}\n").get(0).getDocument();
        assertEquals("x", doc.get("s"));
        assertEquals(42L, doc.get("i"));
        assertEquals(new BigInteger("123456789012345678901234567890"), doc.get("big"));
        assertEquals(1.5, doc.get("f"));
        assertEquals(Boolean.TRUE, doc.get("t"));
        assertTrue(doc.containsKey("n"));
        assertNull(doc.get("n"));
        @SuppressWarnings("unchecked")
        final Map<String, Object> p = (Map<String, Object>) ((Map<String, Object>) doc.get("o")).get("p");
        assertEquals(List.of(1L, "two", Map.of("three", 3L)), p.get("q"));
    }

    @Test
    void thousandOpsInOrder() throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("{\"create\":{\"_index\":\"a\",\"_id\":\"").append(i).append("\"}}\n{\"n\":").append(i).append("}\n");
        }
        final List<Operation> ops = parse(sb.toString());
        assertEquals(1000, ops.size());
        for (int i = 0; i < 1000; i++) {
            assertEquals(String.valueOf(i), ops.get(i).getId());
        }
    }

    @Test
    void crlfAndBlankLinesBetweenOperations() throws Exception {
        assertEquals(2, parse("{\"index\":{\"_index\":\"a\"}}\r\n{}\r\n\n{\"index\":{\"_index\":\"a\"}}\n{}\n").size());
    }

    @Test
    void nonObjectMetadataIsInvalidDocument() throws Exception {
        assertEquals(ItemOutcome.INVALID_DOCUMENT,
            parse("{\"index\":{\"_index\":\"a\"}}\n{\"@metadata\":\"x\"}\n").get(0).getPreStatus());
        assertEquals(ItemOutcome.INVALID_DOCUMENT,
            parse("{\"index\":{\"_index\":\"a\"}}\n{\"@metadata\":null}\n").get(0).getPreStatus());
        assertNull(parse("{\"index\":{\"_index\":\"a\"}}\n{\"@metadata\":{\"k\":1}}\n").get(0).getPreStatus());
    }

    @Test
    void numericTimestampIsEpochMillisLikeElasticsearch() throws Exception {
        // otel mapping mode sends epoch millis with a nanosecond fraction
        assertEquals("2026-11-13T03:10:02.764233216Z", parse("{\"create\":{\"_index\":\"a\"}}\n"
            + "{\"@timestamp\":1794539402764.233216}\n").get(0).getDocument().get("@timestamp"));
        assertEquals("2026-11-13T03:10:02.764Z", parse("{\"create\":{\"_index\":\"a\"}}\n"
            + "{\"@timestamp\":1794539402764}\n").get(0).getDocument().get("@timestamp"));
        assertEquals("1969-12-31T23:59:59.999500Z", parse("{\"create\":{\"_index\":\"a\"}}\n"
            + "{\"@timestamp\":-0.5}\n").get(0).getDocument().get("@timestamp"));
        // the exporter's actual wire format: a string "<millis>.<nanos>"
        assertEquals("2026-11-13T03:10:02.764233216Z", parse("{\"create\":{\"_index\":\"a\"}}\n"
            + "{\"@timestamp\":\"1794539402764.233216\"}\n").get(0).getDocument().get("@timestamp"));
        assertEquals("2026-11-13T03:10:02.764Z", parse("{\"create\":{\"_index\":\"a\"}}\n"
            + "{\"@timestamp\":\"1794539402764\"}\n").get(0).getDocument().get("@timestamp"));
        for (final String notEpoch : new String[] {"abc", "12a", "1.", ".5", "-", "", "2026-01-02"}) {
            assertEquals(notEpoch, parse("{\"create\":{\"_index\":\"a\"}}\n{\"@timestamp\":\"" + notEpoch
                + "\"}\n").get(0).getDocument().get("@timestamp"), notEpoch);
        }
        // out of range: kept as a number, never a request failure
        assertEquals(1e30, parse("{\"create\":{\"_index\":\"a\"}}\n"
            + "{\"@timestamp\":1e30}\n").get(0).getDocument().get("@timestamp"));
        // strings are left for Logstash to parse; nested @timestamp keys are untouched
        assertEquals("2026-01-02T03:04:05Z", parse("{\"create\":{\"_index\":\"a\"}}\n"
            + "{\"@timestamp\":\"2026-01-02T03:04:05Z\"}\n").get(0).getDocument().get("@timestamp"));
        @SuppressWarnings("unchecked")
        final Map<String, Object> nested = (Map<String, Object>) parse("{\"create\":{\"_index\":\"a\"}}\n"
            + "{\"o\":{\"@timestamp\":5}}\n").get(0).getDocument().get("o");
        assertEquals(5L, nested.get("@timestamp"));
    }

    // ---- failure paths (US2, T033) ----

    @Test
    void updateConsumesSourceAndIsUnsupported() throws Exception {
        final List<Operation> ops = parse("{\"update\":{\"_index\":\"a\",\"_id\":\"1\"}}\n{\"doc\":{\"x\":1}}\n"
            + "{\"index\":{\"_index\":\"a\"}}\n{}\n");
        assertEquals(2, ops.size());
        assertEquals(ItemOutcome.UNSUPPORTED_ACTION, ops.get(0).getPreStatus());
        assertNull(ops.get(0).getDocument());
        assertTrue(ops.get(1).isSubmittable());
    }

    @Test
    void deleteHasNoSourceAndIsUnsupported() throws Exception {
        final List<Operation> ops = parse("{\"delete\":{\"_index\":\"a\",\"_id\":\"1\"}}\n"
            + "{\"index\":{\"_index\":\"a\"}}\n{}\n");
        assertEquals(2, ops.size());
        assertEquals(ItemOutcome.UNSUPPORTED_ACTION, ops.get(0).getPreStatus());
        assertEquals(Action.INDEX, ops.get(1).getAction());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",                                                     // empty body
        "\n\n",                                                 // only blank lines
        "{\"index\":{\"_index\":\"a\"}}\n{}",                   // missing final newline
        "{\"index\":{\"_index\":\"a\"}\n{}\n",                  // invalid JSON
        "{\"index\":{\"_index\":\"a\"},\"x\":{}}\n{}\n",        // action line with 2 keys
        "{}\n{}\n",                                             // action line with 0 keys
        "{\"upsert\":{\"_index\":\"a\"}}\n{}\n",                // unknown action
        "{\"index\":{\"_index\":\"a\"}}\n",                     // action without source line
        "{\"index\":{\"_index\":\"a\"}}\n\n",                   // blank source line
        "{\"index\":{\"_index\":\"a\"}}\n[1,2]\n",              // non-object source
        "{\"index\":{\"_index\":\"a\"}}\n\"str\"\n",            // non-object source
        "{\"index\":{\"_index\":\"a\"}}\n{} {}\n",              // two values on one line
        "{\"index\":\"a\"}\n{}\n",                              // action value not an object
        "{\"delete\":{\"_index\":\"a\"}}\n{\"message\":\"stray\"}\n", // delete + stray source
        "{\"delete\":{\"_index\":\"a\"}}\n{\"a\":1,\"b\":2}\n", // delete + stray multi-key source
        "{\"update\":{\"_index\":\"a\"}}\n{\"doc\":\n",         // update with broken source
    })
    void malformedRequestsRejected(final String body) {
        assertRejected(body);
    }

    @Test
    void invalidUtf8Rejected() {
        final byte[] prefix = "{\"index\":{\"_index\":\"a\"}}\n{\"m\":\"".getBytes(StandardCharsets.UTF_8);
        final byte[] suffix = "\"}\n".getBytes(StandardCharsets.UTF_8);
        final byte[] body = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        body[prefix.length] = (byte) 0xC3;     // start of a 2-byte sequence
        body[prefix.length + 1] = (byte) 0x28; // invalid continuation byte
        System.arraycopy(suffix, 0, body, prefix.length + 2, suffix.length);
        assertRejected(body);
    }
}
