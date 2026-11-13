package org.logstash.plugins.inputs.escompat.server;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.compression.StandardCompressionOptions;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslHandler;
import org.logstash.plugins.inputs.escompat.ServerConfig;

import java.util.function.Supplier;

/**
 * Per-connection pipeline, in order:
 * {@code ssl → codec → compressor → routing → identity → encoding → decompressor → aggregator → bulk → errors}.
 * {@code ssl} is present only with TLS; {@code compressor} only with {@code compression_enabled}.
 */
final class HttpInitializer extends ChannelInitializer<SocketChannel> {
    /** Elasticsearch's default {@code http.compression_level}. */
    static final int COMPRESSION_LEVEL = 3;

    private final ServerConfig config;
    private final Supplier<BulkRequestHandler> bulkHandler;

    HttpInitializer(final ServerConfig config, final Supplier<BulkRequestHandler> bulkHandler) {
        this.config = config;
        this.bulkHandler = bulkHandler;
    }

    @Override
    protected void initChannel(final SocketChannel ch) {
        final ChannelPipeline p = ch.pipeline();
        final int max = config.maxContentLength();
        final RequestCounters counters = config.counters();
        if (config.sslContext() != null) {
            final SslHandler ssl = config.sslContext().newHandler(ch.alloc());
            ssl.setHandshakeTimeoutMillis(config.sslHandshakeTimeoutMillis());
            p.addLast("ssl", ssl);
        }
        p.addLast("codec", new HttpServerCodec());
        if (config.compressionEnabled()) {
            // Elasticsearch: every response compressed when accepted, level 3, no size threshold.
            p.addLast("compressor", new HttpContentCompressor(0,
                StandardCompressionOptions.gzip(COMPRESSION_LEVEL, 15, 8),
                StandardCompressionOptions.deflate(COMPRESSION_LEVEL, 15, 8)));
        }
        p.addLast("routing", new RoutingHandler(max, counters));
        p.addLast("identity", new IdentityHandler(max));
        p.addLast("encoding", new ContentEncodingHandler(max, counters));
        // Always on, as in Elasticsearch. No per-buffer cap (a capped buffer throws, which would be a
        // 400 instead of 413): each input chunk expands at most ~1000x (deflate's limit), and the
        // aggregator below stops the request at max_content_length decompressed bytes.
        p.addLast("decompressor", new HttpContentDecompressor(false, 0));
        p.addLast("aggregator", new BoundedAggregator(max, counters));
        p.addLast("bulk", bulkHandler.get());
        p.addLast("errors", new DecompressionErrorHandler(counters));
    }
}
