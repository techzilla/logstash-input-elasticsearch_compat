package org.logstash.plugins.inputs.escompat.server;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.BatchHandler;
import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;
import org.logstash.plugins.inputs.escompat.bulk.Operation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Request → handler → response through the real server, with a fake Ruby handler. */
class BulkFlowTest {
    private static final String EXPORTER_FILTER =
        "items.*._index,items.*.status,items.*.failure_store,items.*.error.type,items.*.error.reason";

    private static final class Recorder implements BatchHandler {
        final List<List<String>> calls = new CopyOnWriteArrayList<>();

        @Override
        public void submit(final List<Operation> ops, final ItemOutcome[] results) {
            final List<String> ids = new ArrayList<>();
            for (final Operation op : ops) ids.add(op.getAction().wireName() + ":" + op.getId());
            calls.add(ids);
            Arrays.fill(results, ItemOutcome.QUEUED);
        }
    }

    @Test
    void onlySubmittableOpsReachHandlerInOrder() throws Exception {
        final Recorder rec = new Recorder();
        try (TestServer s = new TestServer(rec)) {
            final TestServer.Response r = s.post("/_bulk",
                "{\"index\":{\"_index\":\"a\",\"_id\":\"1\"}}\n{}\n"
                    + "{\"update\":{\"_index\":\"a\",\"_id\":\"2\"}}\n{\"doc\":{}}\n"
                    + "{\"delete\":{\"_index\":\"a\",\"_id\":\"3\"}}\n"
                    + "{\"create\":{\"_index\":\"a\",\"_id\":\"4\"}}\n{}\n");
            assertEquals(200, r.status());
            assertEquals(List.of(List.of("index:1", "create:4")), rec.calls);
            assertTrue(r.body().startsWith("{\"took\":"));
            assertTrue(r.body().contains("\"errors\":true"));
            final int i1 = r.body().indexOf("\"_id\":\"1\"");
            final int i2 = r.body().indexOf("\"_id\":\"2\"");
            final int i3 = r.body().indexOf("\"_id\":\"3\"");
            final int i4 = r.body().indexOf("\"_id\":\"4\"");
            assertTrue(i1 < i2 && i2 < i3 && i3 < i4, "items in request order");
        }
    }

    @Test
    void exporterFilterPathResponse() throws Exception {
        try (TestServer s = new TestServer(new Recorder())) {
            final TestServer.Response r = s.post("/_bulk?filter_path=" + EXPORTER_FILTER + "&require_data_stream=true",
                "{\"create\":{\"_index\":\"logs-generic.otel-default\"}}\n{\"@timestamp\":\"2026-11-12T00:00:00Z\"}\n");
            assertEquals("{\"items\":[{\"create\":{\"_index\":\"logs-generic.otel-default\",\"status\":201}}]}", r.body());
        }
    }

    @Test
    void credentialsAcceptedAndIgnored() throws Exception {
        final Recorder rec = new Recorder();
        try (TestServer s = new TestServer(rec)) {
            final String body = "{\"index\":{\"_index\":\"a\"}}\n{}\n";
            final TestServer.Response none = s.post("/_bulk", body);
            final TestServer.Response basic = s.post("/_bulk", body, "Authorization: Basic dXNlcjpzZWNyZXQ=");
            final TestServer.Response apiKey = s.post("/_bulk", body, "Authorization: ApiKey c2VjcmV0LWtleQ==");
            final TestServer.Response bogus = s.post("/_bulk", body, "Authorization: Bearer nonsense");
            for (final TestServer.Response r : List.of(none, basic, apiKey, bogus)) {
                assertEquals(200, r.status());
                assertTrue(!r.body().contains("dXNlcjpzZWNyZXQ") && !r.body().contains("c2VjcmV0"));
            }
            assertEquals(4, rec.calls.size());
        }
    }

    @Test
    void malformedRequestIs400AndNothingSubmitted() throws Exception {
        final Recorder rec = new Recorder();
        try (TestServer s = new TestServer(rec)) {
            final TestServer.Response r = s.post("/_bulk",
                "{\"index\":{\"_index\":\"a\"}}\n{}\n{\"index\":{\"_index\":\"a\"}}\n{broken\n");
            assertEquals(400, r.status());
            assertTrue(r.body().contains("parse_exception"));
            assertEquals(0, rec.calls.size());
        }
    }

    @Test
    void unsupportedFilterPathIs400() throws Exception {
        final Recorder rec = new Recorder();
        try (TestServer s = new TestServer(rec)) {
            final TestServer.Response r = s.post("/_bulk?filter_path=took,hits.*", "{\"index\":{\"_index\":\"a\"}}\n{}\n");
            assertEquals(400, r.status());
            assertTrue(r.body().contains("unsupported filter_path"));
            assertEquals(0, rec.calls.size());
        }
    }

    @Test
    void partialFailureReportsOnlyKnownOutcomes() throws Exception {
        // first queued, second raised, third never attempted
        final BatchHandler h = (ops, results) -> {
            results[0] = ItemOutcome.QUEUED;
            results[1] = ItemOutcome.SUBMIT_FAILED;
        };
        try (TestServer s = new TestServer(h)) {
            final TestServer.Response r = s.post("/_bulk", ("{\"index\":{\"_index\":\"a\"}}\n{}\n").repeat(3));
            assertEquals(200, r.status());
            assertTrue(r.body().matches(".*\"status\":201.*\"status\":500.*\"status\":503.*"), r.body());
        }
    }

    @Test
    void handlerExceptionKeepsKnownOutcomes() throws Exception {
        final BatchHandler h = (ops, results) -> {
            results[0] = ItemOutcome.QUEUED;
            throw new IllegalStateException("boom");
        };
        try (TestServer s = new TestServer(h)) {
            final TestServer.Response r = s.post("/_bulk", ("{\"index\":{\"_index\":\"a\"}}\n{}\n").repeat(2));
            assertEquals(200, r.status());
            assertTrue(r.body().matches(".*\"status\":201.*\"status\":503.*"), r.body());
        }
    }

    @Test
    void shutdownAnswers503ForQueuedRequests() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger submitted = new AtomicInteger();
        final BatchHandler h = (ops, results) -> {
            submitted.incrementAndGet();
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Arrays.fill(results, ItemOutcome.QUEUED);
        };
        final TestServer s = new TestServer(h, 1, 4, 1024 * 1024);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final String body = "{\"index\":{\"_index\":\"a\"}}\n{}\n";
            final Future<TestServer.Response> first = pool.submit(() -> s.post("/_bulk", body));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            final Future<TestServer.Response> queued = pool.submit(() -> s.post("/_bulk", body));
            Thread.sleep(300); // let it reach the pending queue
            final Thread closer = new Thread(s::close);
            closer.start();
            Thread.sleep(300);
            release.countDown();
            closer.join(10_000);
            assertEquals(200, first.get(10, TimeUnit.SECONDS).status());
            assertEquals(503, queued.get(10, TimeUnit.SECONDS).status());
            assertEquals(1, submitted.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
            s.close();
        }
    }
}
