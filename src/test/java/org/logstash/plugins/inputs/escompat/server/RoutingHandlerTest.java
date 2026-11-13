package org.logstash.plugins.inputs.escompat.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;
import org.logstash.plugins.inputs.escompat.bulk.Operation;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Routes (FR-007, FR-008; T026). */
class RoutingHandlerTest {
    private static final String BODY = "{\"index\":{\"_index\":\"a\"}}\n{}\n{\"index\":{}}\n{}\n";
    private static final List<List<String>> SEEN = new CopyOnWriteArrayList<>();
    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer((ops, results) -> {
            SEEN.add(ops.stream().map(Operation::getIndex).toList());
            Arrays.fill(results, ItemOutcome.QUEUED);
        });
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @ParameterizedTest
    @CsvSource({"POST,/_bulk", "PUT,/_bulk", "POST,/logs-a/_bulk", "PUT,/logs-a/_bulk", "POST,/_bulk?refresh=true"})
    void bulkRoutesAccepted(final String method, final String path) throws Exception {
        final TestServer.Response r = server.request(method, path, BODY.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, r.status());
        assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
        assertEquals("application/json", r.header("Content-Type"));
    }

    @Test
    void urlIndexOnlyWhenActionOmitsIndex() throws Exception {
        SEEN.clear();
        server.request("PUT", "/url-idx/_bulk", BODY.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(List.of("a", "url-idx")), SEEN);
        SEEN.clear();
        final TestServer.Response r = server.request("POST", "/_bulk", BODY.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(List.of("a")), SEEN, "item without _index is not submitted on /_bulk");
        assertTrue(r.body().contains("index is missing"));
    }

    @ParameterizedTest
    @CsvSource({"GET,/_bulk", "DELETE,/logs-a/_bulk", "HEAD,/_bulk"})
    void wrongMethodOnBulkPathIs405(final String method, final String path) throws Exception {
        final TestServer.Response r = server.request(method, path, new byte[0]);
        assertEquals(405, r.status());
        assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"//_bulk", "/a/b/_bulk", "/a%2Fb/_bulk", "/x", "/_bulkx", "/x/_search"})
    void otherPathsAre404(final String path) throws Exception {
        final TestServer.Response r = server.request("POST", path, BODY.getBytes(StandardCharsets.UTF_8));
        assertEquals(404, r.status(), path);
        assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
        assertTrue(r.body().contains("resource_not_found_exception"));
    }
}
