package org.logstash.plugins.inputs.escompat.server;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;

import java.util.function.Supplier;

/** Per-connection pipeline: codec → routing → encoding check → aggregator → bulk handler. */
final class HttpInitializer extends ChannelInitializer<SocketChannel> {
    private final int maxContentLength;
    private final Supplier<BulkRequestHandler> bulkHandler;

    HttpInitializer(final int maxContentLength, final Supplier<BulkRequestHandler> bulkHandler) {
        this.maxContentLength = maxContentLength;
        this.bulkHandler = bulkHandler;
    }

    @Override
    protected void initChannel(final SocketChannel ch) {
        final ChannelPipeline p = ch.pipeline();
        p.addLast("codec", new HttpServerCodec());
        p.addLast("routing", new RoutingHandler(maxContentLength));
        p.addLast("encoding", new ContentEncodingHandler(maxContentLength));
        p.addLast("aggregator", new BoundedAggregator(maxContentLength));
        p.addLast("bulk", bulkHandler.get());
    }
}
