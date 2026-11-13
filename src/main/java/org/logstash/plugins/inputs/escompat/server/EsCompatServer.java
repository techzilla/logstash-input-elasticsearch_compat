package org.logstash.plugins.inputs.escompat.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.logstash.plugins.inputs.escompat.BatchHandler;
import org.logstash.plugins.inputs.escompat.bulk.BulkParser;
import org.logstash.plugins.inputs.escompat.response.BulkResponseWriter;

import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP listener with a fixed worker pool behind a bounded queue, mirroring
 * {@code logstash-input-http} (research R5).
 */
public final class EsCompatServer implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger(EsCompatServer.class);

    private final String host;
    private final int port;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup ioGroup;
    private final ThreadPoolExecutor workers;
    private final ServerBootstrap bootstrap;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private volatile Channel serverChannel;

    public EsCompatServer(final String name, final String host, final int port, final int threads,
                          final int maxPendingRequests, final int maxContentLength,
                          final BatchHandler batchHandler) {
        if (threads < 1 || maxPendingRequests < 1 || maxContentLength < 1) {
            throw new IllegalArgumentException("threads, max_pending_requests and max_content_length must be > 0");
        }
        this.host = host;
        this.port = port;
        this.bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory(name + "-boss", true));
        this.ioGroup = new NioEventLoopGroup(threads, new DefaultThreadFactory(name + "-io", true));
        this.workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(maxPendingRequests), new DefaultThreadFactory(name + "-worker", true),
            (task, executor) -> ((BulkRequestHandler.Task) task).reject());
        this.workers.prestartAllCoreThreads();

        final BulkParser parser = new BulkParser(Responses.JSON);
        final BulkResponseWriter writer = new BulkResponseWriter(Responses.JSON);
        this.bootstrap = new ServerBootstrap()
            .group(bossGroup, ioGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childHandler(new HttpInitializer(maxContentLength,
                () -> new BulkRequestHandler(workers, batchHandler, parser, writer, closing::get)));
    }

    /** Binds and blocks until {@link #close()}. */
    public void run() throws InterruptedException {
        start();
        serverChannel.closeFuture().sync();
    }

    /** Binds without blocking; returns the bound port. */
    public int start() throws InterruptedException {
        serverChannel = bootstrap.bind(new InetSocketAddress(host, port)).sync().channel();
        final int bound = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        LOGGER.info("elasticsearch_compat listening on {}:{}", host, bound);
        return bound;
    }

    public boolean isClosing() {
        return closing.get();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        final Channel ch = serverChannel;
        if (ch != null) {
            ch.close().syncUninterruptibly();
        }
        workers.shutdown(); // queued tasks still run and answer 503
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        ioGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
