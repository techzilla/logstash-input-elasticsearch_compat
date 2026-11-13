package org.logstash.plugins.inputs.escompat.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Last stage. Corrupt compressed request bodies become 400 (FR-003), nothing queued; the stream
 * state is unknown afterwards, so the connection is closed. Other errors close the connection.
 */
final class DecompressionErrorHandler extends ChannelInboundHandlerAdapter {
    private static final Logger LOGGER = LogManager.getLogger(DecompressionErrorHandler.class);

    private final RequestCounters counters;

    DecompressionErrorHandler(final RequestCounters counters) {
        this.counters = counters;
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        if (isDecompressionError(cause) && ctx.channel().isActive()) {
            counters.rejectedBadRequest();
            Responses.send(ctx, null, Responses.error(ctx.alloc(), HttpResponseStatus.BAD_REQUEST,
                "parse_exception", "invalid compressed request body"), true);
            return;
        }
        LOGGER.debug("closing connection after error", cause);
        ctx.close();
    }

    static boolean isDecompressionError(final Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof DecompressionException) {
                return true;
            }
        }
        return false;
    }
}
