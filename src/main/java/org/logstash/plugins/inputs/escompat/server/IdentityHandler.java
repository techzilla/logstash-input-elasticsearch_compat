package org.logstash.plugins.inputs.escompat.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;

/**
 * {@code GET /} and {@code HEAD /}: minimal identity for the exporter's version detection
 * (FR-006, research R3). The version is a fixed compatibility placeholder below 9.4 so the exporter
 * keeps its baseline metrics encoding; this receiver is not Elasticsearch.
 */
final class IdentityHandler extends EarlyRejectHandler {
    static final String BODY = "{\"name\":\"logstash-input-elasticsearch_compat\","
        + "\"version\":{\"number\":\"8.19.0\",\"build_flavor\":\"default\"},"
        + "\"tagline\":\"Elasticsearch Bulk API compatibility receiver; not Elasticsearch\"}";
    private static final byte[] BODY_BYTES = BODY.getBytes(StandardCharsets.UTF_8);

    IdentityHandler(final long maxDiscardBytes) {
        super(maxDiscardBytes);
    }

    @Override
    FullHttpResponse check(final ChannelHandlerContext ctx, final HttpRequest request) {
        if (!"/".equals(new QueryStringDecoder(request.uri()).path())) {
            return null;
        }
        final HttpMethod method = request.method();
        if (HttpMethod.GET.equals(method)) {
            return Responses.json(HttpResponseStatus.OK, Unpooled.wrappedBuffer(BODY_BYTES));
        }
        if (HttpMethod.HEAD.equals(method)) {
            final FullHttpResponse res = Responses.json(HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
            res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, BODY_BYTES.length);
            return res;
        }
        return reject(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "method_not_allowed_exception",
            "Incorrect HTTP method for uri [/] and method [" + method + "], allowed: [GET, HEAD]");
    }
}
