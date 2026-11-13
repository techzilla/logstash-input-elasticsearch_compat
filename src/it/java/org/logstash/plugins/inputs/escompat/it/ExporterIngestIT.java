package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.logstash.plugins.inputs.escompat.it.support.LogstashContainer;
import org.logstash.plugins.inputs.escompat.it.support.OtelCollectorContainer;
import org.testcontainers.containers.Network;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T026 (US1, SC-001/SC-002): pinned exporter → plugin, both queue types. */
class ExporterIngestIT {

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persisted"})
    @SuppressWarnings("unchecked")
    void exporterLogsArriveAsEvents(final String queueType) throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("capture.conf", queueType, net);
             OtelCollectorContainer otel = new OtelCollectorContainer(net, "http://" + LogstashContainer.ALIAS + ":9200")) {
            ls.start();
            otel.start();
            otel.sendLogs(net, 1000);

            final List<Map<String, Object>> events = ls.awaitEvents(1000, Duration.ofSeconds(60));
            assertEquals(1000, events.size());
            for (final Map<String, Object> e : events) {
                final Map<String, Object> meta = (Map<String, Object>) e.get("_meta");
                assertEquals("create", meta.get("action"));
                assertFalse(((String) meta.get("index")).isEmpty());
                final Object tags = e.get("tags");
                assertTrue(tags == null || !((List<String>) tags).contains("_timestampparsefailure"),
                    "exporter @timestamp must parse");
            }
            assertEquals(1000, otel.metric("otelcol_exporter_sent_log_records"));
            assertEquals(0, otel.metric("otelcol_exporter_send_failed_log_records"));
        }
    }
}
