package org.logstash.plugins.inputs.escompat.server;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

/** Only {@code POST /_bulk} passes; other paths get 404, other methods 405. */
final class RoutingHandler extends EarlyRejectHandler {
    static final String BULK_PATH = "/_bulk";

    RoutingHandler(final long maxDiscardBytes) {
        super(maxDiscardBytes);
    }

    @Override
    Rejection check(final HttpRequest request) {
        final String path = new QueryStringDecoder(request.uri()).path();
        final HttpMethod method = request.method();
        if (!BULK_PATH.equals(path)) {
            return new Rejection(HttpResponseStatus.NOT_FOUND, "resource_not_found_exception",
                "no handler found for uri [" + path + "] and method [" + method + "]");
        }
        if (!HttpMethod.POST.equals(method)) {
            return new Rejection(HttpResponseStatus.METHOD_NOT_ALLOWED, "method_not_allowed_exception",
                "Incorrect HTTP method for uri [" + path + "] and method [" + method + "], allowed: [POST]");
        }
        return null;
    }
}
