package org.logstash.plugins.inputs.escompat.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

/**
 * Rejects a request from its headers alone, before the body is aggregated. The rest of a rejected
 * request's body is discarded so keep-alive connections stay usable; bodies larger than
 * {@code maxDiscardBytes} close the connection instead.
 */
abstract class EarlyRejectHandler extends ChannelInboundHandlerAdapter {

    /** Why a request is rejected. */
    record Rejection(HttpResponseStatus status, String type, String reason) {
    }

    private final long maxDiscardBytes;
    private boolean discarding;
    private long discarded;

    EarlyRejectHandler(final long maxDiscardBytes) {
        this.maxDiscardBytes = maxDiscardBytes;
    }

    /** @return a rejection, or {@code null} to pass the request on */
    abstract Rejection check(HttpRequest request);

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof HttpRequest req) {
            discarding = false;
            final Rejection rejection = check(req);
            if (rejection == null) {
                ctx.fireChannelRead(msg);
                return;
            }
            final boolean complete = msg instanceof LastHttpContent;
            ReferenceCountUtil.release(msg);
            final boolean tooBigToDrain = HttpUtil.getContentLength(req, -1L) > maxDiscardBytes;
            Responses.send(ctx, req, Responses.error(ctx.alloc(), rejection.status(),
                rejection.type(), rejection.reason()), tooBigToDrain);
            if (!complete && !tooBigToDrain) {
                discarding = true;
                discarded = 0;
            }
            return;
        }
        if (discarding && msg instanceof HttpContent content) {
            discarded += content.content().readableBytes();
            final boolean last = msg instanceof LastHttpContent;
            ReferenceCountUtil.release(msg);
            if (last) {
                discarding = false;
            } else if (discarded > maxDiscardBytes) {
                discarding = false;
                ctx.close();
            }
            return;
        }
        ctx.fireChannelRead(msg);
    }
}
