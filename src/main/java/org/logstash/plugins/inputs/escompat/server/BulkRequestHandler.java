package org.logstash.plugins.inputs.escompat.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.logstash.plugins.inputs.escompat.BatchHandler;
import org.logstash.plugins.inputs.escompat.bulk.BulkParseException;
import org.logstash.plugins.inputs.escompat.bulk.BulkParser;
import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;
import org.logstash.plugins.inputs.escompat.bulk.Operation;
import org.logstash.plugins.inputs.escompat.response.BulkResponseWriter;
import org.logstash.plugins.inputs.escompat.response.FilterPath;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Hands each aggregated {@code POST /_bulk} request to the worker pool. The event loop never
 * parses or blocks on the Logstash queue (Principle III).
 */
final class BulkRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger LOGGER = LogManager.getLogger(BulkRequestHandler.class);

    private final Executor executor;
    private final BatchHandler batchHandler;
    private final BulkParser parser;
    private final BulkResponseWriter writer;
    private final BooleanSupplier closing;

    BulkRequestHandler(final Executor executor, final BatchHandler batchHandler,
                       final BulkParser parser, final BulkResponseWriter writer,
                       final BooleanSupplier closing) {
        super(false); // ownership of the request passes to the task
        this.executor = executor;
        this.batchHandler = batchHandler;
        this.parser = parser;
        this.writer = writer;
        this.closing = closing;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest req) {
        req.headers().remove(HttpHeaderNames.AUTHORIZATION); // accepted, never enforced (FR-011)
        final Task task = new Task(ctx, req);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            task.reject();
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        LOGGER.debug("closing connection after error", cause);
        ctx.close();
    }

    /** One request on a worker thread. */
    final class Task implements Runnable {
        private final ChannelHandlerContext ctx;
        private final FullHttpRequest req;

        Task(final ChannelHandlerContext ctx, final FullHttpRequest req) {
            this.ctx = ctx;
            this.req = req;
        }

        /** Pending-request queue full (429) or shutting down (503); nothing was queued. */
        void reject() {
            try {
                if (closing.getAsBoolean()) {
                    sendError(HttpResponseStatus.SERVICE_UNAVAILABLE, "unavailable_exception",
                        "receiver is shutting down");
                } else {
                    sendError(HttpResponseStatus.TOO_MANY_REQUESTS, "too_many_requests",
                        "pending request limit reached");
                }
            } finally {
                req.release();
            }
        }

        @Override
        public void run() {
            try {
                if (closing.getAsBoolean()) {
                    sendError(HttpResponseStatus.SERVICE_UNAVAILABLE, "unavailable_exception",
                        "receiver is shutting down");
                    return;
                }
                handle();
            } catch (Throwable t) {
                LOGGER.error("unexpected failure handling bulk request: {}", t.toString());
                sendError(HttpResponseStatus.INTERNAL_SERVER_ERROR, "exception",
                    "internal error; events from this request may or may not have been queued");
            } finally {
                req.release();
            }
        }

        private void handle() {
            final long startNanos = System.nanoTime();
            final FilterPath filter;
            final List<Operation> ops;
            try {
                filter = FilterPath.parse(filterPathParam());
                ops = parser.parse(req.content());
            } catch (FilterPath.UnsupportedException e) {
                sendError(HttpResponseStatus.BAD_REQUEST, "illegal_argument_exception", e.getMessage());
                return;
            } catch (BulkParseException e) {
                sendError(HttpResponseStatus.BAD_REQUEST, "parse_exception", e.getMessage());
                return;
            }

            final int n = ops.size();
            final ItemOutcome[] outcomes = new ItemOutcome[n];
            final List<Operation> submittable = new ArrayList<>(n);
            final int[] positions = new int[n];
            for (int i = 0; i < n; i++) {
                final Operation op = ops.get(i);
                if (op.isSubmittable()) {
                    positions[submittable.size()] = i;
                    submittable.add(op);
                } else {
                    outcomes[i] = op.getPreStatus();
                }
            }

            final ItemOutcome[] results = new ItemOutcome[submittable.size()];
            if (!submittable.isEmpty()) {
                try {
                    batchHandler.submit(submittable, results);
                } catch (Throwable t) {
                    // Known outcomes in results stay; untouched entries become NOT_ATTEMPTED.
                    LOGGER.error("event submission failed: {}", t.toString());
                }
            }
            for (int k = 0; k < results.length; k++) {
                outcomes[positions[k]] = results[k] == null ? ItemOutcome.NOT_ATTEMPTED : results[k];
            }

            final long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            final ByteBuf body = writer.write(ops, outcomes, took, filter, ctx.alloc());
            Responses.send(ctx, req, Responses.json(HttpResponseStatus.OK, body), false);
        }

        private String filterPathParam() {
            final List<String> values = new QueryStringDecoder(req.uri()).parameters().get("filter_path");
            return values == null ? null : String.join(",", values);
        }

        private void sendError(final HttpResponseStatus status, final String type, final String reason) {
            Responses.send(ctx, req, Responses.error(ctx.alloc(), status, type, reason), false);
        }
    }
}
