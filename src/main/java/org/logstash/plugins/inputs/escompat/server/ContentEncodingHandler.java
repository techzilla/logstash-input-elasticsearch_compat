package org.logstash.plugins.inputs.escompat.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.Locale;
import java.util.Set;

/**
 * Request bodies may be gzip or deflate (and their {@code x-} aliases), always decoded downstream
 * (FR-001). Any other {@code Content-Encoding} gets 415 (FR-003).
 */
final class ContentEncodingHandler extends EarlyRejectHandler {
    static final Set<String> SUPPORTED = Set.of("identity", "gzip", "x-gzip", "deflate", "x-deflate");

    private final RequestCounters counters;

    ContentEncodingHandler(final long maxDiscardBytes, final RequestCounters counters) {
        super(maxDiscardBytes);
        this.counters = counters;
    }

    @Override
    FullHttpResponse check(final ChannelHandlerContext ctx, final HttpRequest request) {
        final String encoding = request.headers().get(HttpHeaderNames.CONTENT_ENCODING);
        if (encoding == null || SUPPORTED.contains(encoding.trim().toLowerCase(Locale.ROOT))) {
            return null;
        }
        counters.rejectedUnsupportedEncoding();
        return reject(ctx, HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, "illegal_argument_exception",
            "unsupported Content-Encoding [" + encoding + "]; supported: gzip, deflate");
    }
}
