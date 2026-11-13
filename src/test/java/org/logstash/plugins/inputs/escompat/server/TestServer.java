package org.logstash.plugins.inputs.escompat.server;

import org.logstash.plugins.inputs.escompat.BatchHandler;
import org.logstash.plugins.inputs.escompat.ServerConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Real server on an ephemeral port plus a minimal raw HTTP/1.1 client (plain or TLS). */
final class TestServer implements AutoCloseable {
    final EsCompatServer server;
    final int port;
    private SSLContext clientTls;

    TestServer(final BatchHandler handler) throws InterruptedException {
        this(handler, 2, 4, 1024 * 1024);
    }

    TestServer(final BatchHandler handler, final int threads, final int maxPending,
               final int maxContentLength) throws InterruptedException {
        this(handler, b -> b.threads(threads).maxPendingRequests(maxPending).maxContentLength(maxContentLength));
    }

    TestServer(final BatchHandler handler, final Consumer<ServerConfig.Builder> customizer)
        throws InterruptedException {
        final ServerConfig.Builder b = ServerConfig.builder().name("test").host("127.0.0.1").port(0)
            .threads(2).maxPendingRequests(4).maxContentLength(1024 * 1024);
        customizer.accept(b);
        server = new EsCompatServer(b.build(), handler);
        port = server.start();
    }

    /** Use TLS for subsequent requests made through this helper. */
    TestServer tls(final SSLContext context) {
        this.clientTls = context;
        return this;
    }

    @Override
    public void close() {
        server.close();
    }

    record Response(int status, Map<String, String> headers, byte[] rawBody) {
        String header(final String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** Body decoded according to {@code Content-Encoding}, as UTF-8. */
        String body() {
            return new String(decoded(), StandardCharsets.UTF_8);
        }

        byte[] decoded() {
            final String enc = header("Content-Encoding");
            try {
                if ("gzip".equalsIgnoreCase(enc)) {
                    return new GZIPInputStream(new ByteArrayInputStream(rawBody)).readAllBytes();
                }
                if ("deflate".equalsIgnoreCase(enc)) {
                    return new InflaterInputStream(new ByteArrayInputStream(rawBody)).readAllBytes();
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return rawBody;
        }
    }

    Response post(final String path, final String body, final String... extraHeaders) throws IOException {
        return request("POST", path, body.getBytes(StandardCharsets.UTF_8), extraHeaders);
    }

    Socket connect() throws IOException {
        if (clientTls == null) {
            return new Socket("127.0.0.1", port);
        }
        final SSLSocket s = (SSLSocket) clientTls.getSocketFactory().createSocket("127.0.0.1", port);
        s.startHandshake();
        return s;
    }

    Response request(final String method, final String path, final byte[] body,
                     final String... extraHeaders) throws IOException {
        try (Socket s = connect()) {
            s.setSoTimeout(10_000);
            send(s.getOutputStream(), method, path, body, extraHeaders);
            return read(s.getInputStream(), "HEAD".equals(method));
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
        return read(in, false);
    }

    /** Reads to EOF (requests use {@code Connection: close}); decodes chunked bodies. */
    static Response read(final InputStream in, final boolean headResponse) throws IOException {
        final byte[] raw = in.readAllBytes();
        int split = -1;
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                split = i;
                break;
            }
        }
        final String head = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
        final String[] lines = head.split("\r\n");
        final int status = Integer.parseInt(lines[0].split(" ")[1]);
        final Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            final int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                lines[i].substring(colon + 1).trim());
        }
        byte[] body = headResponse ? new byte[0] : java.util.Arrays.copyOfRange(raw, split + 4, raw.length);
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding")) && !headResponse) {
            body = dechunk(body);
        }
        return new Response(status, headers, body);
    }

    private static byte[] dechunk(final byte[] in) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < in.length) {
            int eol = pos;
            while (eol + 1 < in.length && !(in[eol] == '\r' && in[eol + 1] == '\n')) eol++;
            final String sizeLine = new String(in, pos, eol - pos, StandardCharsets.ISO_8859_1).split(";")[0].trim();
            final int size = Integer.parseInt(sizeLine, 16);
            pos = eol + 2;
            if (size == 0) break;
            out.write(in, pos, size);
            pos += size + 2;
        }
        return out.toByteArray();
    }
}
