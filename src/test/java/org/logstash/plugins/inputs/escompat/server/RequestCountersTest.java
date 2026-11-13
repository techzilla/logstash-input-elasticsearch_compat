package org.logstash.plugins.inputs.escompat.server;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.BatchHandler;
import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Counter semantics (FR-011–FR-012, data-model.md; T032). */
class RequestCountersTest {

    @Test
    void countsMatchDrivenTraffic() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean block = new AtomicBoolean(false);
        final BatchHandler h = (ops, results) -> {
            if (block.get()) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Arrays.fill(results, ItemOutcome.QUEUED);
        };
        try (TestServer s = new TestServer(h, b -> b.threads(1).maxPendingRequests(1).maxContentLength(4096))) {
            final RequestCounters c = s.server.counters();
            // 3 accepted: one with an update item and an item without _index
            s.post("/_bulk", "{\"index\":{\"_index\":\"a\"}}\n{}\n");
            s.post("/_bulk", "{\"update\":{\"_index\":\"a\",\"_id\":\"1\"}}\n{\"doc\":{}}\n{\"index\":{}}\n{}\n");
            s.post("/x/_bulk", "{\"index\":{}}\n{}\n");
            // one of each refusal
            assertEquals(413, s.post("/_bulk", "{\"index\":{\"_index\":\"a\"}}\n{\"m\":\"" + "x".repeat(5000) + "\"}\n").status());
            assertEquals(415, s.post("/_bulk", "{}", "Content-Encoding: br").status());
            assertEquals(400, s.post("/_bulk", "{broken\n").status());
            // not counted: identity, 404, wrong method on a Bulk path
            s.request("GET", "/", new byte[0]);
            s.post("/nope", "x");
            s.request("GET", "/_bulk", new byte[0]);
            // 429: one blocked worker + one pending slot, third request refused
            block.set(true);
            final ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                final String one = "{\"index\":{\"_index\":\"a\"}}\n{}\n";
                final Future<TestServer.Response> a = pool.submit(() -> s.post("/_bulk", one));
                Thread.sleep(300);
                final Future<TestServer.Response> b = pool.submit(() -> s.post("/_bulk", one));
                Thread.sleep(300);
                assertEquals(429, s.post("/_bulk", one).status());
                release.countDown();
                assertEquals(200, a.get(10, TimeUnit.SECONDS).status());
                assertEquals(200, b.get(10, TimeUnit.SECONDS).status());
            } finally {
                release.countDown();
                pool.shutdownNow();
            }

            final Map<String, Long> snap = c.snapshot();
            assertEquals(9L, snap.get(RequestCounters.BULK_REQUESTS), snap.toString()); // 3 + 3 + 3 (2 ok, 1 refused)
            assertEquals(1L, snap.get(RequestCounters.REJECTED_OVERLOAD));
            assertEquals(1L, snap.get(RequestCounters.REJECTED_TOO_LARGE));
            assertEquals(1L, snap.get(RequestCounters.REJECTED_UNSUPPORTED_ENCODING));
            assertEquals(1L, snap.get(RequestCounters.REJECTED_BAD_REQUEST));
            assertEquals(2L, snap.get(RequestCounters.ITEMS_FAILED)); // update + missing index
            assertEquals(0L, snap.get(RequestCounters.QUEUE_SUBMIT_FAILURES));
            final long rejected = snap.get(RequestCounters.REJECTED_OVERLOAD) + snap.get(RequestCounters.REJECTED_TOO_LARGE)
                + snap.get(RequestCounters.REJECTED_UNSUPPORTED_ENCODING) + snap.get(RequestCounters.REJECTED_BAD_REQUEST);
            assertTrue(snap.get(RequestCounters.BULK_REQUESTS) >= rejected);
        }
    }

    @Test
    void submitFailuresCounted() throws Exception {
        final BatchHandler h = (ops, results) -> {
            results[0] = ItemOutcome.QUEUED;
            results[1] = ItemOutcome.SUBMIT_FAILED;
        };
        try (TestServer s = new TestServer(h)) {
            s.request("POST", "/_bulk", "{\"index\":{\"_index\":\"a\"}}\n{}\n".repeat(3).getBytes(StandardCharsets.UTF_8));
            final Map<String, Long> snap = s.server.counters().snapshot();
            assertEquals(1L, snap.get(RequestCounters.QUEUE_SUBMIT_FAILURES));
            assertEquals(2L, snap.get(RequestCounters.ITEMS_FAILED)); // 500 + 503
        }
    }
}
