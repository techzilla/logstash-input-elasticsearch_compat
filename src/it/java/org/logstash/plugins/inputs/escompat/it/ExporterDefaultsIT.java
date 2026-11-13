package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.it.support.LogstashContainer;
import org.logstash.plugins.inputs.escompat.it.support.OtelCollectorContainer;
import org.testcontainers.containers.Network;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T010 (US1, SC-001): exporter with only its endpoint set — gzip and version detection on. */
class ExporterDefaultsIT {
    @Test
    void exporterDefaultsWork() throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("capture.conf", "memory", net);
             OtelCollectorContainer otel = new OtelCollectorContainer(net,
                 "http://" + LogstashContainer.ALIAS + ":9200", "otelcol/config-defaults.yaml")) {
            ls.start();
            otel.start();
            otel.sendLogs(net, 1000);

            final List<Map<String, Object>> events = ls.awaitEvents(1000, Duration.ofSeconds(60));
            assertEquals(1000, events.size());
            assertEquals(1000, otel.metric("otelcol_exporter_sent_log_records"));
            assertEquals(0, otel.metric("otelcol_exporter_send_failed_log_records"));
            final String logs = otel.collectorLogs();
            assertTrue(logs.contains("Connected to Elasticsearch") && logs.contains("8.19.0"),
                "version probe should succeed");
            assertFalse(logs.contains("failed to fetch Elasticsearch info"));
        }
    }
}
