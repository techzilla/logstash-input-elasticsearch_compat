package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.it.support.Http;
import org.logstash.plugins.inputs.escompat.it.support.LogstashContainer;
import org.testcontainers.containers.Network;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T028 (US3, SC-003): the same body via all four method/path combinations. */
class RoutesIT {
    private static final String BODY = "{\"index\":{\"_index\":\"own\"}}\n{\"route\":\"%s\",\"n\":1}\n"
        + "{\"index\":{}}\n{\"route\":\"%s\",\"n\":2}\n";

    @Test
    @SuppressWarnings("unchecked")
    void allRoutes() throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("capture.conf", "memory", net)) {
            ls.start();
            final HttpClient c = Http.client(null);
            final String[][] routes = {{"POST", "/_bulk"}, {"PUT", "/_bulk"}, {"POST", "/url-idx/_bulk"}, {"PUT", "/url-idx/_bulk"}};
            for (final String[] r : routes) {
                final String tag = r[0] + " " + r[1];
                assertEquals(200, Http.send(c, r[0], ls.uri("http", 9200, r[1]), BODY.formatted(tag, tag)).status());
            }
            // /_bulk: 1 event each (item without _index rejected); /url-idx/_bulk: 2 each
            final List<Map<String, Object>> events = ls.awaitEvents(6, Duration.ofSeconds(20));
            assertEquals(6, events.size());
            for (final Map<String, Object> e : events) {
                final String route = (String) e.get("route");
                final String index = (String) ((Map<String, Object>) e.get("_meta")).get("index");
                final boolean first = ((Number) e.get("n")).intValue() == 1;
                assertEquals(first ? "own" : "url-idx", index, route);
            }
        }
    }
}
