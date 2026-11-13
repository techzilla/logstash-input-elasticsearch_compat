package org.logstash.plugins.inputs.escompat.it.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * otelcol-contrib with the {@code elasticsearch} exporter configured per research R1
 * ({@code src/it/resources/otelcol/config.yaml}), plus a {@code telemetrygen} log source (T020).
 */
public final class OtelCollectorContainer extends GenericContainer<OtelCollectorContainer> {
    public static final String ALIAS = "otelcol";

    public OtelCollectorContainer(final Network network, final String esEndpoint) {
        super("otel/opentelemetry-collector-contrib:" + version());
        withNetwork(network);
        withNetworkAliases(ALIAS);
        withEnv("ES_ENDPOINT", esEndpoint);
        withCopyFileToContainer(MountableFile.forClasspathResource("otelcol/config.yaml"),
            "/etc/otelcol-contrib/config.yaml");
        withExposedPorts(4317, 4318, 8888);
        withLogConsumer(frame -> {
            final String line = frame.getUtf8String();
            if (line.contains("\twarn\t") || line.contains("\terror\t")) {
                System.out.print("[otelcol] " + line);
            }
        });
        waitingFor(Wait.forLogMessage(".*Everything is ready.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
    }

    public static String version() {
        return System.getProperty("otelcol.version", "0.165.0");
    }

    /** Runs {@code telemetrygen logs} to completion against this collector, unthrottled. */
    public void sendLogs(final Network network, final int count) {
        sendLogs(network, count, 0);
    }

    /**
     * Runs {@code telemetrygen logs} to completion; {@code ratePerSecond} 0 means unthrottled
     * (telemetrygen's own default is 1 log/s).
     */
    public void sendLogs(final Network network, final int count, final int ratePerSecond) {
        try (GenericContainer<?> gen = new GenericContainer<>(
            "ghcr.io/open-telemetry/opentelemetry-collector-contrib/telemetrygen:v" + version())) {
            gen.withNetwork(network)
                .withCommand("logs", "--otlp-endpoint", ALIAS + ":4317", "--otlp-insecure",
                    "--logs", String.valueOf(count), "--workers", "1",
                    "--rate", String.valueOf(ratePerSecond),
                    // Exporter defaults: queue of 10 requests (blocking) + 1 MB minimum batch with a
                    // 10 s flush. telemetrygen's default 100-log requests fill the queue below 1 MB
                    // and stall the receiver past telemetrygen's 10 s timeout.
                    "--batch-size", "2000")
                .withLogConsumer(frame -> System.out.print("[telemetrygen] " + frame.getUtf8String()))
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))
                .start();
        }
    }

    /** Reads a counter from the collector's own Prometheus metrics; 0 when absent. */
    public long metric(final String name) throws IOException, InterruptedException {
        final String body = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://" + getHost() + ":" + getMappedPort(8888) + "/metrics")).build(),
            HttpResponse.BodyHandlers.ofString()).body();
        final Matcher m = Pattern.compile("(?m)^" + Pattern.quote(name) + "(\\{[^}]*\\})? ([0-9.e+]+)$").matcher(body);
        long total = 0;
        while (m.find()) {
            total += (long) Double.parseDouble(m.group(2));
        }
        return total;
    }
}
