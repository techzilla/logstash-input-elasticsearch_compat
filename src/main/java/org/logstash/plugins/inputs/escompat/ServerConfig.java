package org.logstash.plugins.inputs.escompat;

import io.netty.handler.ssl.SslContext;
import org.logstash.plugins.inputs.escompat.server.RequestCounters;

import java.util.Objects;

/** Everything the server needs from the plugin configuration. Built by the Ruby adapter. */
public final class ServerConfig {
    private final String name;
    private final String host;
    private final int port;
    private final int threads;
    private final int maxPendingRequests;
    private final int maxContentLength;
    private final boolean compressionEnabled;
    private final SslContext sslContext;
    private final long sslHandshakeTimeoutMillis;
    private final RequestCounters counters;

    private ServerConfig(final Builder b) {
        this.name = Objects.requireNonNull(b.name, "name");
        this.host = Objects.requireNonNull(b.host, "host");
        this.port = b.port;
        this.threads = b.threads;
        this.maxPendingRequests = b.maxPendingRequests;
        this.maxContentLength = b.maxContentLength;
        this.compressionEnabled = b.compressionEnabled;
        this.sslContext = b.sslContext;
        this.sslHandshakeTimeoutMillis = b.sslHandshakeTimeoutMillis;
        this.counters = b.counters == null ? new RequestCounters() : b.counters;
        if (port < 0 || threads < 1 || maxPendingRequests < 1 || maxContentLength < 1 || sslHandshakeTimeoutMillis < 1) {
            throw new IllegalArgumentException(
                "port must be >= 0; threads, max_pending_requests, max_content_length and ssl_handshake_timeout must be > 0");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name() { return name; }
    public String host() { return host; }
    public int port() { return port; }
    public int threads() { return threads; }
    public int maxPendingRequests() { return maxPendingRequests; }
    public int maxContentLength() { return maxContentLength; }
    public boolean compressionEnabled() { return compressionEnabled; }
    /** {@code null} when TLS is off. */
    public SslContext sslContext() { return sslContext; }
    public long sslHandshakeTimeoutMillis() { return sslHandshakeTimeoutMillis; }
    public RequestCounters counters() { return counters; }

    public static final class Builder {
        private String name = "escompat";
        private String host = "0.0.0.0";
        private int port = 9200;
        private int threads = Runtime.getRuntime().availableProcessors();
        private int maxPendingRequests = 200;
        private int maxContentLength = 100 * 1024 * 1024;
        private boolean compressionEnabled = true;
        private SslContext sslContext;
        private long sslHandshakeTimeoutMillis = 10_000;
        private RequestCounters counters;

        public Builder name(final String v) { name = v; return this; }
        public Builder host(final String v) { host = v; return this; }
        public Builder port(final int v) { port = v; return this; }
        public Builder threads(final int v) { threads = v; return this; }
        public Builder maxPendingRequests(final int v) { maxPendingRequests = v; return this; }
        public Builder maxContentLength(final int v) { maxContentLength = v; return this; }
        public Builder compressionEnabled(final boolean v) { compressionEnabled = v; return this; }
        public Builder sslContext(final SslContext v) { sslContext = v; return this; }
        public Builder sslHandshakeTimeoutMillis(final long v) { sslHandshakeTimeoutMillis = v; return this; }
        public Builder counters(final RequestCounters v) { counters = v; return this; }

        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }
}
