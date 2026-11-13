package org.logstash.plugins.inputs.escompat.server;

import org.logstash.plugins.inputs.escompat.BatchHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Real server on an ephemeral port plus a minimal raw HTTP/1.1 client. */
final class TestServer implements AutoCloseable {
    final EsCompatServer server;
    final int port;

    TestServer(final BatchHandler handler) throws InterruptedException {
        this(handler, 2, 4, 1024 * 1024);
    }

    TestServer(final BatchHandler handler, final int threads, final int maxPending,
               final int maxContentLength) throws InterruptedException {
        server = new EsCompatServer("test", "127.0.0.1", 0, threads, maxPending, maxContentLength, handler);
        port = server.start();
    }

    @Override
    public void close() {
        server.close();
    }

    record Response(int status, Map<String, String> headers, String body) {
        String header(final String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    Response post(final String path, final String body, final String... extraHeaders) throws IOException {
        return request("POST", path, body.getBytes(StandardCharsets.UTF_8), extraHeaders);
    }

    Response request(final String method, final String path, final byte[] body,
                     final String... extraHeaders) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10_000);
            send(s.getOutputStream(), method, path, body, extraHeaders);
            return read(s.getInputStream());
        }
    }

    static void send(final OutputStream out, final String method, final String path, final byte[] body,
                     final String... extraHeaders) throws IOException {
        final StringBuilder sb = new StringBuilder()
            .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Content-Type: application/x-ndjson\r\n")
            .append("Connection: close\r\n")
            .append("Content-Length: ").append(body.length).append("\r\n");
        for (final String h : extraHeaders) {
            sb.append(h).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    static Response read(final InputStream in) throws IOException {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        final String raw = buf.toString(StandardCharsets.UTF_8);
        final int split = raw.indexOf("\r\n\r\n");
        final String[] lines = raw.substring(0, split).split("\r\n");
        final int status = Integer.parseInt(lines[0].split(" ")[1]);
        final Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            final int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                lines[i].substring(colon + 1).trim());
        }
        return new Response(status, headers, raw.substring(split + 4));
    }
}
