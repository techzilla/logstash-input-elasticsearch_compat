package org.logstash.plugins.inputs.escompat.it.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real Logstash 9.5.6 with the built gem installed (T018). Pipeline from
 * {@code src/it/resources/pipelines/}, queue type {@code memory} or {@code persisted}, and
 * {@code /out} bound to a host temp dir writable by the container's uid 1000.
 *
 * <p>The plugin is installed once per test JVM into a derived image shared by every container,
 * instead of running {@code logstash-plugin install} at each container start.
 */
public final class LogstashContainer extends GenericContainer<LogstashContainer> {
    public static final String ALIAS = "logstash";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Built lazily on first use, then reused; removed when the JVM exits. */
    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile("escompat-it-logstash", true)
        .withFileFromPath("plugin.gem", gemPath())
        .withDockerfileFromBuilder(b -> b
            .from(System.getProperty("logstash.image", "docker.elastic.co/logstash/logstash:9.5.6"))
            .copy("plugin.gem", "/tmp/plugin.gem")
            .run("bin/logstash-plugin install --no-verify --local /tmp/plugin.gem")
            .build());

    private final Path outDir;

    public LogstashContainer(final String pipeline, final String queueType, final Network network) {
        super(IMAGE);
        try {
            outDir = Files.createTempDirectory("ls-out",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
            Files.setPosixFilePermissions(outDir, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        withNetwork(network);
        withNetworkAliases(ALIAS);
        withExposedPorts(9200, 9201, 9600);
        withEnv("QUEUE_TYPE", queueType);
        withEnv("XPACK_MONITORING_ENABLED", "false");
        withEnv("LS_JAVA_OPTS", "-Xms1g -Xmx1g");
        withCopyFileToContainer(MountableFile.forClasspathResource("pipelines/" + pipeline),
            "/usr/share/logstash/pipeline/logstash.conf");
        withFileSystemBind(outDir.toString(), "/out");
        waitingFor(Wait.forLogMessage(".*elasticsearch_compat listening on 0.0.0.0:9200.*", 1)
            .withStartupTimeout(Duration.ofMinutes(4)));
    }

    /** Mounts the TLS fixtures from the test classpath at {@code /usr/share/logstash/tls/}. */
    public LogstashContainer withTlsFixtures() {
        for (final String f : new String[] {"ca.crt", "server.crt", "server.key", "server.p12", "server.jks",
            "truststore.jks"}) {
            withCopyFileToContainer(MountableFile.forClasspathResource("tls/" + f, 0644), "/usr/share/logstash/tls/" + f);
        }
        return this;
    }

    /** Waits for the listening port instead of the plugin's log line (other inputs, failing configs). */
    public LogstashContainer waitForPort(final int port) {
        waitingFor(Wait.forListeningPorts(port).withStartupTimeout(Duration.ofMinutes(4)));
        return this;
    }

    /** Extra environment, e.g. {@code PIPELINE_WORKERS}. */
    public LogstashContainer env(final String key, final String value) {
        withEnv(key, value);
        return this;
    }

    private static Path gemPath() {
        final String gem = System.getProperty("plugin.gem", "target/logstash-input-elasticsearch_compat.gem");
        final Path p = Path.of(gem);
        if (!Files.exists(p)) {
            throw new IllegalStateException("gem not built: " + p + " (run with -Pintegration)");
        }
        return p;
    }

    public URI bulkUri(final int containerPort) {
        return URI.create("http://" + getHost() + ":" + getMappedPort(containerPort) + "/_bulk");
    }

    public URI uri(final String scheme, final int containerPort, final String path) {
        return URI.create(scheme + "://" + getHost() + ":" + getMappedPort(containerPort) + path);
    }

    public URI statsUri() {
        return URI.create("http://" + getHost() + ":" + getMappedPort(9600) + "/_node/stats/pipelines/main");
    }

    /** Events written by the pipeline's file output. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readEvents() throws IOException {
        final Path f = outDir.resolve("events.ndjson");
        final List<Map<String, Object>> events = new ArrayList<>();
        if (!Files.exists(f)) {
            return events;
        }
        for (final String line : Files.readAllLines(f)) {
            if (!line.isBlank()) {
                events.add(JSON.readValue(line, Map.class));
            }
        }
        return events;
    }

    /** Polls {@link #readEvents()} until at least {@code n} events or the timeout. */
    public List<Map<String, Object>> awaitEvents(final int n, final Duration timeout) throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        List<Map<String, Object>> events = readEvents();
        while (events.size() < n && System.nanoTime() < deadline) {
            Thread.sleep(250);
            events = readEvents();
        }
        return events;
    }
}
