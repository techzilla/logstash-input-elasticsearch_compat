package org.logstash.plugins.inputs.escompat.it.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Small HTTP helper for integration tests. */
public final class Http {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();

    public record Response(int status, String product, String body) {
    }

    private Http() {
    }

    /** HTTP/1.1 client; pass an SSLContext for HTTPS, or {@code null}. */
    public static HttpClient client(final javax.net.ssl.SSLContext tls) {
        final HttpClient.Builder b = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10));
        if (tls != null) b.sslContext(tls);
        return b.build();
    }

    public static Response post(final URI uri, final String body, final String... headers)
        throws IOException, InterruptedException {
        return post(CLIENT, uri, body, headers);
    }

    public static Response post(final HttpClient client, final URI uri, final String body, final String... headers)
        throws IOException, InterruptedException {
        final HttpRequest.Builder b = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-ndjson")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        final HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.headers().firstValue("X-Elastic-Product").orElse(null), r.body());
    }

    public static Response send(final HttpClient client, final String method, final URI uri, final String body)
        throws IOException, InterruptedException {
        final HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-ndjson")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build();
        final HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.headers().firstValue("X-Elastic-Product").orElse(null), r.body());
    }

    public static String op(final String action, final String index, final String source) {
        return "{\"" + action + "\":{\"_index\":\"" + index + "\"}}\n" + (source == null ? "" : source + "\n");
    }
}
