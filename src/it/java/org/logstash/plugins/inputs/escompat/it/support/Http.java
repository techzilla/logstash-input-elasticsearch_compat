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

    public static Response post(final URI uri, final String body, final String... headers)
        throws IOException, InterruptedException {
        final HttpRequest.Builder b = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-ndjson")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        final HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.headers().firstValue("X-Elastic-Product").orElse(null), r.body());
    }

    public static String op(final String action, final String index, final String source) {
        return "{\"" + action + "\":{\"_index\":\"" + index + "\"}}\n" + (source == null ? "" : source + "\n");
    }
}
