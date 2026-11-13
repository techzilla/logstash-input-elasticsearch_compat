package org.logstash.plugins.inputs.escompat.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Builds and sends every HTTP response so all carry the same headers. */
public final class Responses {
    public static final JsonFactory JSON = JsonFactory.builder().build();

    /** Required by the exporter's product check (FR-015, research R2). */
    public static final AsciiString PRODUCT_HEADER = AsciiString.cached("X-Elastic-Product");
    public static final AsciiString PRODUCT_VALUE = AsciiString.cached("Elasticsearch");

    private Responses() {
    }

    public static FullHttpResponse json(final HttpResponseStatus status, final ByteBuf body) {
        final FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, body);
        res.headers()
            .set(PRODUCT_HEADER, PRODUCT_VALUE)
            .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
            .setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
        return res;
    }

    /** {@code {"error":{"type":…,"reason":…},"status":N}} */
    public static FullHttpResponse error(final ByteBufAllocator alloc, final HttpResponseStatus status,
                                         final String type, final String reason) {
        final ByteBuf buf = alloc.buffer(128);
        try (JsonGenerator g = JSON.createGenerator((java.io.OutputStream) new ByteBufOutputStream(buf))) {
            g.writeStartObject();
            g.writeObjectFieldStart("error");
            g.writeStringField("type", type);
            g.writeStringField("reason", reason);
            g.writeEndObject();
            g.writeNumberField("status", status.code());
            g.writeEndObject();
        } catch (IOException e) {
            buf.release();
            throw new UncheckedIOException(e);
        }
        return json(status, buf);
    }

    /**
     * Writes the response. Keeps the connection open only if the request allows it and
     * {@code forceClose} is false.
     *
     * @param req the request being answered, or {@code null} if unknown
     */
    public static void send(final ChannelHandlerContext ctx, final HttpRequest req,
                            final FullHttpResponse res, final boolean forceClose) {
        final boolean keepAlive = !forceClose && req != null && HttpUtil.isKeepAlive(req);
        HttpUtil.setKeepAlive(res, keepAlive);
        if (keepAlive) {
            ctx.writeAndFlush(res, ctx.voidPromise());
        } else {
            ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
