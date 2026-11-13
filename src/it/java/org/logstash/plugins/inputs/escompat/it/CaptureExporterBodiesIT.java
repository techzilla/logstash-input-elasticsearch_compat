package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.it.support.OtelCollectorContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T047: record real exporter Bulk bodies into target/bench/exporter for T048/T049. The stub runs
 * inside the Logstash image (which ships a JDK) on the test network, avoiding the slow
 * host-port tunnel.
 */
class CaptureExporterBodiesIT {
    private static final String STUB_CLASS = "org/logstash/plugins/inputs/escompat/it/support/CaptureStub.class";

    @Test
    void capture() throws Exception {
        final Path target = Path.of(System.getProperty("bench.dir", "target/bench")).resolve("exporter");
        final Path out = Files.createTempDirectory("capture",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
        Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rwxrwxrwx"));
        final Path classFile = Path.of(CaptureExporterBodiesIT.class.getResource("/" + STUB_CLASS).toURI());

        try (Network net = Network.newNetwork();
             GenericContainer<?> stub = new GenericContainer<>(System.getProperty("logstash.image",
                 "docker.elastic.co/logstash/logstash:9.5.6"))) {
            stub.withNetwork(net).withNetworkAliases("stub")
                .withCopyFileToContainer(MountableFile.forHostPath(classFile), "/stub/" + STUB_CLASS)
                .withFileSystemBind(out.toString(), "/out")
                .withCommand("/usr/share/logstash/jdk/bin/java", "-cp", "/stub",
                    "org.logstash.plugins.inputs.escompat.it.support.CaptureStub", "9250", "/out", "20")
                .waitingFor(Wait.forLogMessage(".*capture stub on :9250.*", 1));
            stub.start();
            try (OtelCollectorContainer otel = new OtelCollectorContainer(net, "http://stub:9250")) {
                otel.start();
                otel.sendLogs(net, 200_000); // ~8 bodies per 70k telemetrygen logs
                final long deadline = System.currentTimeMillis() + 60_000;
                while (count(out) < 15 && System.currentTimeMillis() < deadline) Thread.sleep(500);
            }
        }

        final int captured = count(out);
        assertTrue(captured >= 15, "captured " + captured + " bodies");
        Files.createDirectories(target);
        try (Stream<Path> files = Files.list(out)) {
            for (final Path f : files.toList()) {
                Files.copy(f, target.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static int count(final Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(f -> f.toString().endsWith(".jsonl")).count();
        }
    }
}
