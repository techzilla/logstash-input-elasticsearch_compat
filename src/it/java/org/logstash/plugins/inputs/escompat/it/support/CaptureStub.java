package org.logstash.plugins.inputs.escompat.it.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal Bulk endpoint that records real exporter request bodies (T047). Each body is saved as
 * {@code NNN.ndjson} and, with action lines removed, {@code NNN.jsonl} (input for
 * {@code logstash-input-http} with {@code json_lines}). Answers every item 201 with the product
 * header so the exporter does not retry.
 *
 * <p>Run standalone: {@code CaptureStub <port> <outDir> <maxBodies>}.
 */
public final class CaptureStub implements AutoCloseable {
    private final HttpServer server;
    private final Path outDir;
    private final int maxBodies;
    private final AtomicInteger saved = new AtomicInteger();

    public CaptureStub(final int port, final Path outDir, final int maxBodies) throws IOException {
        this.outDir = Files.createDirectories(outDir);
        this.maxBodies = maxBodies;
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 64);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public int saved() {
        return saved.get();
    }

    private void handle(final HttpExchange ex) throws IOException {
        final byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readAllBytes();
        }
        final String path = ex.getRequestURI().getPath();
        final byte[] reply;
        if ("POST".equals(ex.getRequestMethod()) && path.endsWith("/_bulk")) {
            reply = bulkReply(new String(body, StandardCharsets.UTF_8), save(body));
        } else {
            reply = "{}".getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().set("X-Elastic-Product", "Elasticsearch");
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, reply.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(reply);
        }
    }

    private boolean save(final byte[] body) throws IOException {
        final int n = saved.get();
        if (n >= maxBodies || !saved.compareAndSet(n, n + 1)) {
            return false;
        }
        final String name = String.format("%03d", n);
        Files.write(outDir.resolve(name + ".ndjson"), body);
        final StringBuilder docs = new StringBuilder(body.length);
        final String[] lines = new String(body, StandardCharsets.UTF_8).split("\n");
        for (int i = 1; i < lines.length; i += 2) { // exporter sends only index/create pairs
            docs.append(lines[i]).append('\n');
        }
        Files.writeString(outDir.resolve(name + ".jsonl"), docs);
        return true;
    }

    private static byte[] bulkReply(final String body, final boolean ignored) {
        final String[] lines = body.split("\n");
        final StringBuilder sb = new StringBuilder("{\"took\":1,\"errors\":false,\"items\":[");
        for (int i = 0; i + 1 < lines.length; i += 2) {
            final String action = lines[i].startsWith("{\"index\"") ? "index" : "create";
            if (i > 0) sb.append(',');
            sb.append("{\"").append(action).append("\":{\"status\":201}}");
        }
        return sb.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static void main(final String[] args) throws Exception {
        final CaptureStub stub = new CaptureStub(Integer.parseInt(args[0]), Path.of(args[1]),
            Integer.parseInt(args[2]));
        System.out.println("capture stub on :" + stub.port());
        while (stub.saved() < stub.maxBodies) {
            Thread.sleep(200);
        }
        System.out.println("captured " + stub.saved() + " bodies");
        stub.close();
    }
}
