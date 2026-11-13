package org.logstash.plugins.inputs.escompat.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;

/** {@link HttpObjectAggregator} whose 413 responses carry this plugin's headers (FR-012). */
final class BoundedAggregator extends HttpObjectAggregator {
    private final int maxContentLength;
    private final RequestCounters counters;

    BoundedAggregator(final int maxContentLength, final RequestCounters counters) {
        super(maxContentLength, true); // close after a rejected Expect: 100-continue
        this.maxContentLength = maxContentLength;
        this.counters = counters;
    }

    @Override
    protected void handleOversizedMessage(final ChannelHandlerContext ctx, final HttpMessage oversized) {
        if (oversized instanceof HttpRequest req) {
            counters.rejectedTooLarge();
            Responses.send(ctx, req, tooLarge(ctx), true);
        } else {
            ctx.close();
        }
    }

    @Override
    protected Object newContinueResponse(final HttpMessage start, final int maxContentLength,
                                         final ChannelPipeline pipeline) {
        final Object res = super.newContinueResponse(start, maxContentLength, pipeline);
        if (res instanceof FullHttpResponse full
            && full.status().code() == HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.code()) {
            ReferenceCountUtil.release(full);
            counters.rejectedTooLarge();
            final FullHttpResponse ours = tooLarge(pipeline.firstContext());
            ours.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            return ours;
        }
        return res;
    }

    private FullHttpResponse tooLarge(final ChannelHandlerContext ctx) {
        return Responses.error(ctx.alloc(), HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
            "content_too_long_exception",
            "request body is larger than max_content_length [" + maxContentLength + "] bytes");
    }
}
