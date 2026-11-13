package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.it.support.BulkLoadDriver;
import org.logstash.plugins.inputs.escompat.it.support.LogstashContainer;
import org.logstash.plugins.inputs.escompat.server.tls.TlsFixtures;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T049/T047 (SC-006, 002 FR-014): this plugin vs logstash-input-http (json_lines), both over TLS
 * with gzip-compressed bodies (same certificate, same bodies); this plugin has metrics on (default).
 * Same image, resources and captured documents. One container per plugin, both started once; 3 alternating runs each with
 * 10 senders and 20 s measured (10 s warm-up before each plugin's first run, 3 s before later
 * runs). Fails if this plugin's median events/s is below 95% of the baseline's median.
 */
class ThroughputIT {
    private static final Duration FIRST_WARMUP = Duration.ofSeconds(10);
    private static final Duration WARMUP = Duration.ofSeconds(3);
    private static final Duration MEASURE = Duration.ofSeconds(20);

    @Test
    void atLeastAsFastAsHttpInput() throws Exception {
        final Path bodies = Path.of(System.getProperty("bench.dir", "target/bench")).resolve("exporter");
        final List<byte[]> ndjson = BulkLoadDriver.gzipAll(BulkLoadDriver.loadBodies(bodies, "ndjson"));
        final List<byte[]> jsonl = BulkLoadDriver.gzipAll(BulkLoadDriver.loadBodies(bodies, "jsonl"));
        final javax.net.ssl.SSLContext tls = TlsFixtures.client(null, null);
        final List<Double> escompat = new ArrayList<>();
        final List<Double> http = new ArrayList<>();
        try (Network net = Network.newNetwork();
             LogstashContainer esc = new LogstashContainer("throughput-escompat-tls.conf", "memory", net).withTlsFixtures();
             LogstashContainer base = new LogstashContainer("throughput-http-tls.conf", "memory", net).withTlsFixtures()) {
            // the http input logs no "elasticsearch_compat listening" line
            base.waitingFor(Wait.forListeningPorts(9200).withStartupTimeout(Duration.ofMinutes(4)));
            esc.start();
            base.start();
            for (int run = 0; run < 3; run++) {
                final Duration warmup = run == 0 ? FIRST_WARMUP : WARMUP;
                escompat.add(BulkLoadDriver.run(esc.uri("https", 9200, "/_bulk"), ndjson, true, tls, esc.statsUri(),
                    10, warmup, MEASURE).eventsPerSecond());
                http.add(BulkLoadDriver.run(base.uri("https", 9200, "/_bulk"), jsonl, true, tls, base.statsUri(),
                    10, warmup, MEASURE).eventsPerSecond());
            }
        }
        final double a = median(escompat);
        final double b = median(http);
        System.out.printf("SC-006 escompat=%s http=%s medians %.0f vs %.0f events/s (%.1f%%)%n",
            escompat, http, a, b, 100 * a / b);
        assertTrue(a >= 0.95 * b, "escompat median " + a + " < 95% of http median " + b);
    }

    private static double median(final List<Double> xs) {
        final List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }
}
