package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.it.support.LogstashContainer;
import org.testcontainers.containers.Network;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T041 (US3, SC-004/SC-005): blocked pipeline → fast 429s, 413, disconnect. */
class OverloadIT {
    record Result(int status, long millis) {
    }

    private static String one(final String message) {
        return "{\"index\":{\"_index\":\"a\"}}\n{\"message\":\"" + message + "\"}\n";
    }

    private static Socket open(final LogstashContainer ls, final String body) throws IOException {
        final Socket s = new Socket(ls.getHost(), ls.getMappedPort(9200));
        final byte[] b = body.getBytes(StandardCharsets.UTF_8);
        s.getOutputStream().write(("POST /_bulk HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-ndjson\r\n"
            + "Connection: close\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        s.getOutputStream().write(b);
        return s;
    }

    private static Result send(final LogstashContainer ls, final String body, final int timeoutMs) throws IOException {
        final long t0 = System.nanoTime();
        try (Socket s = open(ls, body)) {
            s.setSoTimeout(timeoutMs);
            final InputStream in = s.getInputStream();
            final byte[] head = new byte[12];
            int n = 0;
            while (n < head.length) {
                final int r = in.read(head, n, head.length - n);
                if (r < 0) break;
                n += r;
            }
            return new Result(Integer.parseInt(new String(head, 9, 3, StandardCharsets.ISO_8859_1)),
                (System.nanoTime() - t0) / 1_000_000);
        } catch (SocketTimeoutException e) {
            return new Result(-1, (System.nanoTime() - t0) / 1_000_000);
        }
    }

    @Test
    void refusesFastWhileBlocked() throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("blocked.conf", "memory", net)
                 .env("PIPELINE_WORKERS", "1").env("PIPELINE_BATCH_SIZE", "1")) {
            ls.start();
            final Socket blocker = open(ls, one("block"));
            Thread.sleep(2000);

            final ExecutorService pool = Executors.newFixedThreadPool(20);
            final List<Future<Result>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int k = i;
                futures.add(pool.submit(() -> send(ls, one("m" + k), 3000)));
            }
            int refused = 0;
            long slowest429 = 0;
            for (final Future<Result> f : futures) {
                final Result r = f.get();
                if (r.status() == 429) {
                    refused++;
                    slowest429 = Math.max(slowest429, r.millis());
                }
            }
            pool.shutdownNow();
            assertTrue(refused >= 1, "expected 429s while blocked");
            assertTrue(slowest429 < 1000, "429 within 1 s (SC-005), was " + slowest429 + " ms");

            assertEquals(413, send(ls, "{\"index\":{\"_index\":\"a\"}}\n{\"m\":\"" + "x".repeat(5000) + "\"}\n", 3000).status());

            // client disconnects mid-request: listener keeps serving, nothing logged above DEBUG
            open(ls, one("disconnect")).close();
            Thread.sleep(500);
            final Result after = send(ls, one("after"), 3000);
            assertTrue(after.status() == 429 || after.status() == 200, "listener still serves");
            assertFalse(ls.getLogs().contains("[ERROR]"), "no errors logged");
            blocker.close();
        }
    }
}
