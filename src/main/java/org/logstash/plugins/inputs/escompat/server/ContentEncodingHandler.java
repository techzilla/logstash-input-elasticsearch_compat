package org.logstash.plugins.inputs.escompat.server;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

/** Compressed bodies are not supported in this release (FR-014): 415. */
final class ContentEncodingHandler extends EarlyRejectHandler {

    ContentEncodingHandler(final long maxDiscardBytes) {
        super(maxDiscardBytes);
    }

    @Override
    Rejection check(final HttpRequest request) {
        final String encoding = request.headers().get(HttpHeaderNames.CONTENT_ENCODING);
        if (encoding == null || HttpHeaderValues.IDENTITY.contentEqualsIgnoreCase(encoding.trim())) {
            return null;
        }
        return new Rejection(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, "illegal_argument_exception",
            "unsupported Content-Encoding [" + encoding + "]; send uncompressed bodies");
    }
}
