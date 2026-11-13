package org.logstash.plugins.inputs.escompat.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingHandlerTest {
    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer((ops, results) -> Arrays.fill(results, ItemOutcome.QUEUED));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void rootIsNotFound() throws Exception {
        final TestServer.Response r = server.request("GET", "/", new byte[0]);
        assertEquals(404, r.status());
        assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
        assertTrue(r.body().contains("resource_not_found_exception"));
    }

    @Test
    void putBulkIsMethodNotAllowed() throws Exception {
        final TestServer.Response put = server.request("PUT", "/_bulk",
            "{\"index\":{\"_index\":\"a\"}}\n{}\n".getBytes());
        assertEquals(405, put.status());
        assertEquals("Elasticsearch", put.header("X-Elastic-Product"));
    }

    @Test
    void indexScopedBulkIsNotFound() throws Exception {
        final TestServer.Response r = server.post("/x/_bulk", "{\"index\":{}}\n{}\n");
        assertEquals(404, r.status());
        assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
    }

    @Test
    void bulkWithQueryStringRoutes() throws Exception {
        final TestServer.Response r = server.post("/_bulk?refresh=true&timeout=1m",
            "{\"index\":{\"_index\":\"a\"}}\n{}\n");
        assertEquals(200, r.status());
        assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
        assertEquals("application/json", r.header("Content-Type"));
    }
}
