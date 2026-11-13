package org.logstash.plugins.inputs.escompat.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;

/**
 * Routes (data-model.md): {@code POST|PUT /_bulk} and {@code POST|PUT /{index}/_bulk} pass on as
 * Bulk requests; {@code /} passes on to {@link IdentityHandler}; wrong methods get 405; anything
 * else 404. Counts Bulk requests and records the route's default index on the channel.
 */
final class RoutingHandler extends EarlyRejectHandler {
    static final String BULK_SUFFIX = "/_bulk";
    /** Default index of the current request ({@code null} for {@code /_bulk}). */
    static final AttributeKey<String> DEFAULT_INDEX = AttributeKey.valueOf("escompat.defaultIndex");

    private final RequestCounters counters;

    RoutingHandler(final long maxDiscardBytes, final RequestCounters counters) {
        super(maxDiscardBytes);
        this.counters = counters;
    }

    @Override
    FullHttpResponse check(final ChannelHandlerContext ctx, final HttpRequest request) {
        final String path = new QueryStringDecoder(request.uri()).path();
        final HttpMethod method = request.method();
        if ("/".equals(path)) {
            return null; // IdentityHandler
        }
        final boolean bulkPath;
        String defaultIndex = null;
        if ("/_bulk".equals(path)) {
            bulkPath = true;
        } else if (path.endsWith(BULK_SUFFIX) && path.length() > BULK_SUFFIX.length() + 1 && path.charAt(0) == '/') {
            defaultIndex = path.substring(1, path.length() - BULK_SUFFIX.length());
            bulkPath = !defaultIndex.isEmpty() && defaultIndex.indexOf('/') < 0;
        } else {
            bulkPath = false;
        }
        if (!bulkPath) {
            return reject(ctx, HttpResponseStatus.NOT_FOUND, "resource_not_found_exception",
                "no handler found for uri [" + path + "] and method [" + method + "]");
        }
        if (!HttpMethod.POST.equals(method) && !HttpMethod.PUT.equals(method)) {
            return reject(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "method_not_allowed_exception",
                "Incorrect HTTP method for uri [" + path + "] and method [" + method + "], allowed: [POST, PUT]");
        }
        counters.bulkRequest();
        ctx.channel().attr(DEFAULT_INDEX).set(defaultIndex);
        return null;
    }
}
