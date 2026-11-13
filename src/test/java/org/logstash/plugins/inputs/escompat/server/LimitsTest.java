package org.logstash.plugins.inputs.escompat.server;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** US3 limits against the real server with a fake handler (T040). */
class LimitsTest {
    private static final String ONE = "{\"index\":{\"_index\":\"a\"}}\n{\"m\":1}\n";

    @Test
    void oversizedBodyIs413AndNeverSubmitted() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        try (TestServer s = new TestServer((ops, r) -> calls.incrementAndGet(), 1, 4, 64)) {
            final TestServer.Response r = s.post("/_bulk", ONE.repeat(10));
            assertEquals(413, r.status());
            assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
            assertTrue(r.body().contains("content_too_long_exception"));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void oversizedWithExpectContinueIs413() throws Exception {
        try (TestServer s = new TestServer((ops, r) -> { }, 1, 4, 64)) {
            try (Socket sock = new Socket("127.0.0.1", s.port)) {
                sock.setSoTimeout(10_000);
                final String head = "POST /_bulk HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n"
                    + "Expect: 100-continue\r\n\r\n";
                sock.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));
                final TestServer.Response r = TestServer.read(sock.getInputStream());
                assertEquals(413, r.status());
                assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
            }
        }
    }

    @Test
    void identityEncodingAllowed() throws Exception {
        try (TestServer s = new TestServer((ops, r) -> Arrays.fill(r, ItemOutcome.QUEUED))) {
            assertEquals(200, s.post("/_bulk", ONE, "Content-Encoding: identity").status());
        }
    }

    @Test
    void fullPoolIs429Quickly() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger submitted = new AtomicInteger();
        // 1 worker + 1 pending slot: the third concurrent request must be refused
        try (TestServer s = new TestServer((ops, r) -> {
            submitted.incrementAndGet();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Arrays.fill(r, ItemOutcome.QUEUED);
        }, 1, 1, 1024 * 1024)) {
            final ExecutorService pool = Executors.newFixedThreadPool(6);
            try {
                final List<Future<TestServer.Response>> futures = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    futures.add(pool.submit(() -> s.post("/_bulk", ONE)));
                }
                // refusals arrive while the worker is still blocked (SC-005)
                final long t0 = System.nanoTime();
                int refused = 0;
                for (final Future<TestServer.Response> f : futures) {
                    if (f.isDone() || waitDone(f, 1000)) {
                        final TestServer.Response r = f.get();
                        assertEquals(429, r.status());
                        assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
                        refused++;
                    }
                }
                assertTrue(refused >= 1, "expected at least one 429");
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 7000);
                release.countDown();
                int ok = 0;
                for (final Future<TestServer.Response> f : futures) {
                    if (f.get(10, TimeUnit.SECONDS).status() == 200) ok++;
                }
                assertEquals(6 - refused, ok);
                assertEquals(ok, submitted.get(), "refused requests were never submitted");
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }

    private static boolean waitDone(final Future<?> f, final long millis) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + millis;
        while (!f.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return f.isDone();
    }
}
