package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.it.support.Http;
import org.logstash.plugins.inputs.escompat.it.support.LogstashContainer;
import org.testcontainers.containers.Network;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T036 (US2, SC-003/SC-004): unsupported actions and malformed requests. */
class TruthfulAckIT {
    private static final List<String> MALFORMED = List.of(
        "",
        "{\"index\":{\"_index\":\"a\"}}\n{}",
        "{\"index\":{\"_index\":\"a\"}}\n{oops\n",
        "{\"index\":{\"_index\":\"a\"},\"x\":{}}\n{}\n",
        "{\"upsert\":{\"_index\":\"a\"}}\n{}\n",
        "{\"index\":{\"_index\":\"a\"}}\n",
        "{\"index\":{\"_index\":\"a\"}}\n[1]\n",
        "{\"delete\":{\"_index\":\"a\"}}\n{\"message\":\"stray\"}\n");

    @Test
    void neverFalselyAcknowledged() throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("capture.conf", "memory", net)) {
            ls.start();
            final URI bulk = ls.bulkUri(9200);

            final Http.Response mixed = Http.post(bulk,
                "{\"index\":{\"_index\":\"a\",\"_id\":\"1\"}}\n{\"message\":\"kept\"}\n"
                    + "{\"update\":{\"_index\":\"a\",\"_id\":\"2\"}}\n{\"doc\":{}}\n"
                    + "{\"delete\":{\"_index\":\"a\",\"_id\":\"3\"}}\n");
            assertEquals(200, mixed.status());
            assertTrue(mixed.body().contains("\"errors\":true"));
            assertTrue(mixed.body().matches(
                ".*\"index\":\\{[^}]*\"status\":201.*\"update\":\\{[^}]*\"status\":400.*\"delete\":\\{[^}]*\"status\":400.*"),
                mixed.body());

            for (final String body : MALFORMED) {
                final Http.Response r = Http.post(bulk, body);
                assertEquals(400, r.status(), body);
                assertEquals("Elasticsearch", r.product());
            }

            Thread.sleep(2000);
            assertEquals(1, ls.awaitEvents(1, Duration.ofSeconds(10)).size());
        }
    }
}
