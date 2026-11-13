package org.logstash.plugins.inputs.escompat.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.it.support.Http;
import org.logstash.plugins.inputs.escompat.it.support.LogstashContainer;
import org.testcontainers.containers.Network;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** T034 (US4, SC-005): counters in node stats, absent with enable_metric => false. */
class MetricsIT {
    @Test
    void countersInNodeStats() throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("metrics.conf", "memory", net)) {
            ls.start();
            final URI bulk = ls.bulkUri(9200);
            final String one = Http.op("index", "a", "{}");
            for (int i = 0; i < 3; i++) Http.post(bulk, one);                                   // 3 ok
            Http.post(bulk, "{\"update\":{\"_index\":\"a\",\"_id\":\"1\"}}\n{\"doc\":{}}\n");     // ok, 1 item error
            Http.post(bulk, one + "{\"index\":{\"_index\":\"a\"}}\n{\"m\":\"" + "x".repeat(5000) + "\"}\n"); // 413
            Http.post(bulk, "{}", "Content-Encoding", "br");                                  // 415
            Http.post(bulk, "{broken\n");                                                      // 400
            Http.send(Http.client(null), "GET", ls.uri("http", 9200, "/"), null);              // not counted
            Http.post(ls.bulkUri(9201), one);                                                  // m_off
            Thread.sleep(2500);

            final JsonNode inputs = new ObjectMapper().readTree(HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(ls.statsUri()).build(), HttpResponse.BodyHandlers.ofString()).body())
                .path("pipelines").path("main").path("plugins").path("inputs");
            JsonNode on = null;
            JsonNode off = null;
            for (final JsonNode in : inputs) {
                if ("m_on".equals(in.path("id").asText())) on = in;
                if ("m_off".equals(in.path("id").asText())) off = in;
            }
            assertEquals(7, on.path("bulk_requests").asLong(), on.toString());
            assertEquals(1, on.path("bulk_requests_rejected_too_large").asLong());
            assertEquals(1, on.path("bulk_requests_rejected_unsupported_encoding").asLong());
            assertEquals(1, on.path("bulk_requests_rejected_bad_request").asLong());
            assertEquals(0, on.path("bulk_requests_rejected_overload").asLong());
            assertEquals(1, on.path("bulk_items_failed").asLong());
            assertEquals(0, on.path("queue_submit_failures").asLong());
            assertFalse(off.has("bulk_requests"), off.toString());
        }
    }
}
