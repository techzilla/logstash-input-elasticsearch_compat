package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.logstash.plugins.inputs.escompat.it.support.Http;
import org.logstash.plugins.inputs.escompat.it.support.LogstashContainer;
import org.testcontainers.containers.Network;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T025 (US1): events, metadata, input options, credentials, reserved fields. */
class BulkIngestIT {

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persisted"})
    @SuppressWarnings("unchecked")
    void ingest(final String queueType) throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("capture.conf", queueType, net)) {
            ls.start();
            final URI bulk = ls.bulkUri(9200);

            // 3 items, mixed actions, unknown params ignored
            final Http.Response r = Http.post(URI.create(bulk + "?refresh=true&timeout=1m&pipeline=x"),
                "{\"index\":{\"_index\":\"logs-a\",\"_id\":\"1\",\"routing\":\"r1\"}}\n{\"message\":\"m1\",\"nested\":{\"k\":[1,2]}}\n"
                    + "{\"create\":{\"_index\":\"logs-a\"}}\n{\"message\":\"m2\"}\n"
                    + "{\"index\":{\"_index\":\"logs-b\",\"pipeline\":\"p1\"}}\n{\"message\":\"m3\"}\n");
            assertEquals(200, r.status());
            assertEquals("Elasticsearch", r.product());
            assertTrue(r.body().contains("\"errors\":false"));

            // credentials accepted and ignored (FR-011)
            for (final String[] h : List.of(new String[0],
                new String[] {"Authorization", "Basic dXNlcjpzM2NyM3Q="},
                new String[] {"Authorization", "ApiKey c2VjcmV0LWtleQ=="})) {
                final Http.Response c = Http.post(bulk, Http.op("index", "cred", "{\"message\":\"cred\"}"), h);
                assertEquals(200, c.status());
                assertFalse(c.body().contains("dXNlcjpzM2NyM3Q") || c.body().contains("c2VjcmV0"));
            }

            // reserved fields (contracts/plugin-config.md)
            Http.post(bulk, Http.op("index", "ts", "{\"message\":\"valid-ts\",\"@timestamp\":\"2026-01-02T03:04:05Z\"}")
                + Http.op("index", "ts", "{\"message\":\"epoch-ts\",\"@timestamp\":\"1794539402764.233216\"}")
                + Http.op("index", "ts", "{\"message\":\"bad-ts\",\"@timestamp\":\"not-a-date\"}")
                + Http.op("index", "ts", "{\"message\":\"no-ts\"}")
                + Http.op("index", "ts", "{\"message\":\"own-meta\",\"@metadata\":{\"own\":\"kept\"}}"));

            // custom metadata_target on the second input
            Http.post(ls.bulkUri(9201), "{\"index\":{\"_index\":\"cust\",\"_id\":\"c1\"}}\n{\"message\":\"custom\"}\n");

            final List<Map<String, Object>> events = ls.awaitEvents(12, Duration.ofSeconds(30));
            assertEquals(12, events.size());
            final Map<String, Map<String, Object>> byMsg = events.stream()
                .collect(Collectors.toMap(e -> (String) e.get("message"), e -> e, (a, b) -> a));

            final Map<String, Object> m1 = byMsg.get("m1");
            assertEquals(Map.of("action", "index", "index", "logs-a", "id", "1", "routing", "r1"), m1.get("_meta"));
            assertEquals(Map.of("k", List.of(1, 2)), m1.get("nested"));
            assertEquals(List.of("t1"), m1.get("tags"));
            assertEquals("v1", m1.get("f1"));
            assertEquals(Map.of("action", "create", "index", "logs-a"), byMsg.get("m2").get("_meta"));
            assertEquals(Map.of("action", "index", "index", "logs-b", "pipeline", "p1"), byMsg.get("m3").get("_meta"));
            assertEquals(3, events.stream().filter(e -> "cred".equals(e.get("message"))).count());

            assertEquals("2026-01-02T03:04:05.000Z", byMsg.get("valid-ts").get("@timestamp"));
            assertEquals("2026-11-13T03:10:02.764233216Z", byMsg.get("epoch-ts").get("@timestamp"));
            assertTrue(((List<String>) byMsg.get("bad-ts").get("tags")).contains("_timestampparsefailure"));
            assertEquals("not-a-date", byMsg.get("bad-ts").get("_@timestamp"));
            assertNull(byMsg.get("no-ts").get("_@timestamp"));
            assertEquals("kept", byMsg.get("own-meta").get("_own"));

            final Map<String, Object> custom = byMsg.get("custom");
            assertEquals(Map.of("action", "index", "index", "cust", "id", "c1"), custom.get("_custom"));
            assertNull(custom.get("_meta"));

            // id option: the input shows up under it_input in node stats
            final String stats = HttpClient.newHttpClient().send(HttpRequest.newBuilder(ls.statsUri()).build(),
                HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(stats.contains("\"it_input\""));

            // credentials never logged
            assertFalse(ls.getLogs().contains("dXNlcjpzM2NyM3Q") || ls.getLogs().contains("c2VjcmV0LWtleQ"));
        }
    }
}
