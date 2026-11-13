package org.logstash.plugins.inputs.escompat.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** GET/HEAD / identity (FR-006, T007). */
class IdentityTest {
    static final String BODY = "{\"name\":\"logstash-input-elasticsearch_compat\","
        + "\"version\":{\"number\":\"8.19.0\",\"build_flavor\":\"default\"},"
        + "\"tagline\":\"Elasticsearch Bulk API compatibility receiver; not Elasticsearch\"}";

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer((ops, results) -> CALLS.incrementAndGet());
    }

    @AfterAll
    static void stop() {
        server.close();
        assertEquals(0, CALLS.get(), "identity requests never reach the batch handler");
    }

    @Test
    void getReturnsIdentity() throws Exception {
        final TestServer.Response r = server.request("GET", "/", new byte[0]);
        assertEquals(200, r.status());
        assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
        assertEquals("application/json", r.header("Content-Type"));
        assertEquals(BODY, r.body());
    }

    @Test
    void getWithQueryString() throws Exception {
        assertEquals(BODY, server.request("GET", "/?pretty", new byte[0]).body());
    }

    @Test
    void headReturnsHeadersOnly() throws Exception {
        final TestServer.Response r = server.request("HEAD", "/", new byte[0]);
        assertEquals(200, r.status());
        assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
        assertEquals(0, r.rawBody().length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void otherMethodsAre405(final String method) throws Exception {
        assertEquals(405, server.request(method, "/", "{}".getBytes()).status());
    }
}
