package org.logstash.plugins.inputs.escompat.bulk;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a whole Bulk NDJSON body before anything is submitted (FR-006). Any framing, UTF-8, or
 * JSON problem throws {@link BulkParseException}; per-item problems become
 * {@link Operation#getPreStatus()}.
 */
public final class BulkParser {
    private static final String METADATA_FIELD = "@metadata";
    private static final String TIMESTAMP_FIELD = "@timestamp";

    private final JsonFactory factory;

    public BulkParser(final JsonFactory factory) {
        this.factory = factory;
    }

    public List<Operation> parse(final ByteBuf body) throws BulkParseException {
        final byte[] data;
        final int start;
        final int len = body.readableBytes();
        if (body.hasArray()) {
            data = body.array();
            start = body.arrayOffset() + body.readerIndex();
        } else {
            data = ByteBufUtil.getBytes(body);
            start = 0;
        }
        return parse(data, start, len);
    }

    public List<Operation> parse(final byte[] data, final int start, final int len)
        throws BulkParseException {
        final int end = start + len;
        if (len == 0 || isBlank(data, start, end)) {
            throw new BulkParseException("request body is required");
        }
        if (data[end - 1] != '\n') {
            throw new BulkParseException("The bulk request must be terminated by a newline [\\n]");
        }
        final List<Operation> ops = new ArrayList<>();
        int pos = start;
        int lineNo = 0;
        while (pos < end) {
            int nl = indexOfNewline(data, pos, end);
            lineNo++;
            if (isBlank(data, pos, nl)) {
                pos = nl + 1;
                continue;
            }
            final ActionLine actionLine = parseActionLine(data, pos, trimCr(data, pos, nl), lineNo);
            pos = nl + 1;

            Map<String, Object> document = null;
            if (actionLine.action.hasSource()) {
                if (pos >= end) {
                    throw new BulkParseException("Malformed action/metadata line [" + lineNo
                        + "]: [" + actionLine.action.wireName() + "] requires a source line");
                }
                nl = indexOfNewline(data, pos, end);
                lineNo++;
                final int lineEnd = trimCr(data, pos, nl);
                if (isBlank(data, pos, lineEnd)) {
                    throw new BulkParseException("Malformed source line [" + lineNo
                        + "]: [" + actionLine.action.wireName() + "] requires a source document");
                }
                if (actionLine.action.producesEvent()) {
                    document = parseSource(data, pos, lineEnd, lineNo);
                } else {
                    skipSource(data, pos, lineEnd, lineNo);
                }
                pos = nl + 1;
            }
            ops.add(toOperation(actionLine, document));
        }
        if (ops.isEmpty()) {
            throw new BulkParseException("request body is required");
        }
        return ops;
    }

    private static Operation toOperation(final ActionLine a, final Map<String, Object> document) {
        ItemOutcome pre = null;
        if (!a.action.producesEvent()) {
            pre = ItemOutcome.UNSUPPORTED_ACTION;
        } else if (a.index == null || a.index.isEmpty()) {
            pre = ItemOutcome.MISSING_INDEX;
        } else if (document.containsKey(METADATA_FIELD)
            && !(document.get(METADATA_FIELD) instanceof Map)) {
            pre = ItemOutcome.INVALID_DOCUMENT;
        }
        return new Operation(a.action, a.index, a.id, a.routing, a.pipeline, document, pre);
    }

    private record ActionLine(Action action, String index, String id, String routing,
                              String pipeline) {
    }

    private ActionLine parseActionLine(final byte[] data, final int from, final int to,
                                       final int lineNo) throws BulkParseException {
        try (JsonParser p = factory.createParser(data, from, to - from)) {
            expect(p.nextToken(), JsonToken.START_OBJECT, lineNo);
            if (p.nextToken() != JsonToken.FIELD_NAME) {
                throw malformed(lineNo, "expected FIELD_NAME but found [" + p.currentToken() + "]");
            }
            final String name = p.currentName();
            final Action action = Action.fromWireName(name);
            if (action == null) {
                throw malformed(lineNo,
                    "expected one of [create, delete, index, update] but found [" + name + "]");
            }
            expect(p.nextToken(), JsonToken.START_OBJECT, lineNo);
            String index = null;
            String id = null;
            String routing = null;
            String pipeline = null;
            JsonToken t;
            while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
                final String key = p.currentName();
                final JsonToken v = p.nextToken();
                if (v.isStructStart()) {
                    p.skipChildren(); // e.g. dynamic_templates: ignored (FR-010)
                    continue;
                }
                final String value = v == JsonToken.VALUE_NULL ? null : p.getValueAsString();
                switch (key) {
                    case "_index": index = value; break;
                    case "_id": id = value; break;
                    case "routing":
                    case "_routing": routing = value; break;
                    case "pipeline": pipeline = value; break;
                    default: break; // unknown options are ignored (FR-010)
                }
            }
            expect(t, JsonToken.END_OBJECT, lineNo);
            expect(p.nextToken(), JsonToken.END_OBJECT, lineNo);
            if (p.nextToken() != null) {
                throw malformed(lineNo, "expected end of line but found [" + p.currentToken() + "]");
            }
            return new ActionLine(action, index, id, routing, pipeline);
        } catch (JsonProcessingException e) {
            throw new BulkParseException("Failed to parse line [" + lineNo + "]: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new BulkParseException("Failed to parse line [" + lineNo + "]: " + e.getMessage());
        }
    }

    private Map<String, Object> parseSource(final byte[] data, final int from, final int to,
                                            final int lineNo) throws BulkParseException {
        try (JsonParser p = factory.createParser(data, from, to - from)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new BulkParseException("Malformed source line [" + lineNo
                    + "]: expected an object but found [" + p.currentToken() + "]");
            }
            final Map<String, Object> doc = readDocument(p);
            if (p.nextToken() != null) {
                throw new BulkParseException("Malformed source line [" + lineNo
                    + "]: expected end of line but found [" + p.currentToken() + "]");
            }
            return doc;
        } catch (JsonProcessingException e) {
            throw new BulkParseException("Failed to parse line [" + lineNo + "]: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new BulkParseException("Failed to parse line [" + lineNo + "]: " + e.getMessage());
        }
    }

    /** Validates an {@code update} source line without building it. */
    private void skipSource(final byte[] data, final int from, final int to, final int lineNo)
        throws BulkParseException {
        try (JsonParser p = factory.createParser(data, from, to - from)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new BulkParseException("Malformed source line [" + lineNo
                    + "]: expected an object but found [" + p.currentToken() + "]");
            }
            p.skipChildren();
            if (p.nextToken() != null) {
                throw new BulkParseException("Malformed source line [" + lineNo
                    + "]: expected end of line but found [" + p.currentToken() + "]");
            }
        } catch (JsonProcessingException e) {
            throw new BulkParseException("Failed to parse line [" + lineNo + "]: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new BulkParseException("Failed to parse line [" + lineNo + "]: " + e.getMessage());
        }
    }

    /**
     * Top-level source object. A numeric {@code @timestamp}, or a string of digits such as the
     * exporter's {@code "1794539402764.233216"}, is epoch milliseconds, as Elasticsearch's default
     * {@code strict_date_optional_time||epoch_millis} reads it. Logstash parses neither, so it is
     * converted to ISO-8601 from the exact text (nanosecond precision, no floating-point rounding).
     */
    private static Map<String, Object> readDocument(final JsonParser p) throws IOException {
        final Map<String, Object> map = new HashMap<>();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            final String key = p.currentName();
            final JsonToken t = p.nextToken();
            if (TIMESTAMP_FIELD.equals(key)
                && (t.isNumeric() || (t == JsonToken.VALUE_STRING && isEpochMillisText(p.getText())))) {
                Object value;
                try {
                    value = epochMillisToIso(p.getText());
                } catch (ArithmeticException | java.time.DateTimeException e) {
                    value = readValue(p, t); // out of range: Logstash tags _timestampparsefailure
                }
                map.put(key, value);
            } else {
                map.put(key, readValue(p, t));
            }
        }
        return map;
    }

    /** {@code -?digits(.digits)?} — never matches an ISO-8601 date. */
    static boolean isEpochMillisText(final String s) {
        final int n = s.length();
        int i = n > 0 && s.charAt(0) == '-' ? 1 : 0;
        final int intStart = i;
        while (i < n && Character.isDigit(s.charAt(i))) i++;
        if (i == intStart) return false;
        if (i == n) return true;
        if (s.charAt(i) != '.') return false;
        final int fracStart = ++i;
        while (i < n && Character.isDigit(s.charAt(i))) i++;
        return i == n && i > fracStart;
    }

    static String epochMillisToIso(final String millisText) {
        final BigDecimal seconds = new BigDecimal(millisText).movePointLeft(3);
        final BigDecimal whole = seconds.setScale(0, RoundingMode.FLOOR);
        final long nanos = seconds.subtract(whole).movePointRight(9).setScale(0, RoundingMode.FLOOR).longValueExact();
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(whole.longValueExact(), nanos));
    }

    private static Map<String, Object> readObject(final JsonParser p) throws IOException {
        final Map<String, Object> map = new HashMap<>();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            final String key = p.currentName();
            map.put(key, readValue(p, p.nextToken()));
        }
        return map;
    }

    private static List<Object> readArray(final JsonParser p) throws IOException {
        final List<Object> list = new ArrayList<>();
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
            list.add(readValue(p, t));
        }
        return list;
    }

    private static Object readValue(final JsonParser p, final JsonToken t) throws IOException {
        switch (t) {
            case START_OBJECT: return readObject(p);
            case START_ARRAY: return readArray(p);
            case VALUE_STRING: return p.getText();
            case VALUE_NUMBER_INT:
                return p.getNumberType() == JsonParser.NumberType.BIG_INTEGER
                    ? p.getBigIntegerValue() : (Object) p.getLongValue();
            case VALUE_NUMBER_FLOAT: return p.getDoubleValue();
            case VALUE_TRUE: return Boolean.TRUE;
            case VALUE_FALSE: return Boolean.FALSE;
            case VALUE_NULL: return null;
            default: throw new IOException("unexpected token [" + t + "]");
        }
    }

    private static void expect(final JsonToken actual, final JsonToken expected, final int lineNo)
        throws BulkParseException {
        if (actual != expected) {
            throw malformed(lineNo, "expected " + expected + " but found [" + actual + "]");
        }
    }

    private static BulkParseException malformed(final int lineNo, final String detail) {
        return new BulkParseException("Malformed action/metadata line [" + lineNo + "], " + detail);
    }

    private static int indexOfNewline(final byte[] data, final int from, final int end) {
        for (int i = from; i < end; i++) {
            if (data[i] == '\n') {
                return i;
            }
        }
        return end; // unreachable: body ends with '\n'
    }

    private static int trimCr(final byte[] data, final int from, final int nl) {
        return nl > from && data[nl - 1] == '\r' ? nl - 1 : nl;
    }

    private static boolean isBlank(final byte[] data, final int from, final int to) {
        for (int i = from; i < to; i++) {
            final byte b = data[i];
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                return false;
            }
        }
        return true;
    }
}
