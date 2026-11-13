package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T048b (SC-007): runs event_build_bench.rb with Logstash 9.5.6's own Ruby and checks the
 * combined path (parse + event build + response write): p99 < 20 ms at 1,000 items, and growth
 * no faster than linear (10,000-item p99 within 10× the 1,000-item p99, plus 20% for noise).
 */
class EventBuildBenchIT {
    private static final double P99_LIMIT_MS = 20.0;

    @Test
    void eventBuildTimes() {
        final Path bodies = Path.of(System.getProperty("bench.dir", "target/bench")).resolve("exporter");
        final String jar = "/tmp/plugin.jar";
        try (GenericContainer<?> c = new GenericContainer<>(System.getProperty("logstash.image",
            "docker.elastic.co/logstash/logstash:9.5.6"))) {
            c.withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of("vendor/jar-dependencies/org/logstash/plugins/inputs/logstash-input-elasticsearch_compat/0.1.0/"
                        + "logstash-input-elasticsearch_compat-0.1.0.jar")), jar)
                .withCopyFileToContainer(MountableFile.forHostPath(bodies.resolve("000.ndjson")), "/tmp/bench/000.ndjson")
                .withCopyFileToContainer(MountableFile.forClasspathResource("bench/event_build_bench.rb"), "/tmp/bench.rb")
                .withCommand("bin/ruby", "-I", "logstash-core/lib", "/tmp/bench.rb", jar, "/tmp/bench")
                .withLogConsumer(frame -> System.out.print(frame.getUtf8String()))
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(10)))
                .start();
            final String out = c.getLogs();
            final double p99At1k = combinedP99(out, 1000);
            final double p99At10k = combinedP99(out, 10000);
            assertTrue(p99At1k < P99_LIMIT_MS,
                "SC-007: combined p99 at 1,000 items " + p99At1k + " ms >= " + P99_LIMIT_MS + " ms");
            assertTrue(p99At10k <= 10 * p99At1k * 1.2,
                "SC-007: growth faster than linear (" + p99At1k + " ms → " + p99At10k + " ms)");
        }
    }

    private static double combinedP99(final String out, final int items) {
        final Matcher m = Pattern.compile("combined items=" + items + " p50=[0-9.]+ms p99=([0-9.]+)ms").matcher(out);
        assertTrue(m.find(), "no combined result for " + items + " items:\n" + out);
        return Double.parseDouble(m.group(1));
    }
}
