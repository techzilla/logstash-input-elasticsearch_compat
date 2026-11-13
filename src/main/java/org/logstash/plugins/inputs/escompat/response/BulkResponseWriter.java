package org.logstash.plugins.inputs.escompat.response;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;
import org.logstash.plugins.inputs.escompat.bulk.Operation;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Writes the Bulk response straight into a pooled buffer, applying {@link FilterPath} while
 * writing (FR-008, FR-009). Never emits fields this receiver cannot justify, such as
 * {@code _version}, {@code _seq_no}, {@code _shards}, or {@code result}.
 */
public final class BulkResponseWriter {
    private final JsonFactory factory;

    public BulkResponseWriter(final JsonFactory factory) {
        this.factory = factory;
    }

    public ByteBuf write(final List<Operation> ops, final ItemOutcome[] outcomes, final long tookMillis,
                         final FilterPath filter, final ByteBufAllocator alloc) {
        final ByteBuf buf = alloc.buffer(64 + ops.size() * 64);
        try (JsonGenerator g = factory.createGenerator((OutputStream) new ByteBufOutputStream(buf))) {
            g.writeStartObject();
            if (filter.took()) {
                g.writeNumberField("took", tookMillis);
            }
            if (filter.errors()) {
                boolean errors = false;
                for (final ItemOutcome o : outcomes) {
                    if (o.isError()) {
                        errors = true;
                        break;
                    }
                }
                g.writeBooleanField("errors", errors);
            }
            if (filter.items()) {
                writeItems(g, ops, outcomes, filter);
            }
            g.writeEndObject();
        } catch (IOException e) {
            buf.release();
            throw new UncheckedIOException(e);
        }
        return buf;
    }

    private static void writeItems(final JsonGenerator g, final List<Operation> ops,
                                   final ItemOutcome[] outcomes, final FilterPath filter)
        throws IOException {
        final boolean wantIndex = filter.itemField("_index");
        final boolean wantId = filter.itemField("_id");
        final boolean wantStatus = filter.itemField("status");
        final boolean wantType = filter.errorField("type");
        final boolean wantReason = filter.errorField("reason");
        boolean started = false;
        for (int i = 0; i < ops.size(); i++) {
            final Operation op = ops.get(i);
            final ItemOutcome outcome = outcomes[i];
            final boolean index = wantIndex && op.getIndex() != null;
            final boolean id = wantId && op.getId() != null;
            final boolean error = outcome.isError() && (wantType || wantReason);
            if (!index && !id && !wantStatus && !error) {
                continue; // Elasticsearch drops objects emptied by filtering
            }
            if (!started) {
                g.writeArrayFieldStart("items");
                started = true;
            }
            g.writeStartObject();
            g.writeObjectFieldStart(op.getAction().wireName());
            if (index) {
                g.writeStringField("_index", op.getIndex());
            }
            if (id) {
                g.writeStringField("_id", op.getId());
            }
            if (wantStatus) {
                g.writeNumberField("status", outcome.status());
            }
            if (error) {
                g.writeObjectFieldStart("error");
                if (wantType) {
                    g.writeStringField("type", outcome.errorType());
                }
                if (wantReason) {
                    g.writeStringField("reason", outcome.reason(op.getAction()));
                }
                g.writeEndObject();
            }
            g.writeEndObject();
            g.writeEndObject();
        }
        if (started) {
            g.writeEndArray();
        }
    }
}
