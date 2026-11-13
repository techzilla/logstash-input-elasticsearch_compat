package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.it.support.Http;
import org.logstash.plugins.inputs.escompat.it.support.LogstashContainer;
import org.testcontainers.containers.Network;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T042 (US3): graceful shutdown under load. Responses are only 200 (with honest items) or 503,
 * or the connection is refused; every item acknowledged 201 is in the output.
 */
class ShutdownIT {
    @Test
    void shutdownUnderLoadNeverLies() throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("capture.conf", "memory", net)) {
            ls.start();
            final URI bulk = ls.bulkUri(9200);
            final AtomicBoolean stop = new AtomicBoolean();
            final AtomicInteger acked = new AtomicInteger();
            final Map<Integer, Integer> statuses = new ConcurrentHashMap<>();
            final Thread[] senders = new Thread[4];
            for (int t = 0; t < senders.length; t++) {
                senders[t] = Thread.ofPlatform().start(() -> {
                    while (!stop.get()) {
                        try {
                            final Http.Response r = Http.post(bulk, Http.op("index", "a", "{\"message\":\"s\"}").repeat(50));
                            statuses.merge(r.status(), 1, Integer::sum);
                            if (r.status() == 200) {
                                acked.addAndGet(countOccurrences(r.body(), "\"status\":201"));
                            }
                        } catch (IOException e) {
                            statuses.merge(-1, 1, Integer::sum); // refused/reset during shutdown
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                });
            }
            Thread.sleep(3000);
            ls.getDockerClient().stopContainerCmd(ls.getContainerId()).withTimeout(60).exec();
            stop.set(true);
            for (final Thread t : senders) t.join(30_000);

            assertTrue(Set.of(200, 503, -1).containsAll(statuses.keySet()), "unexpected statuses " + statuses);
            assertEquals(acked.get(), ls.readEvents().size(), "every 201 item was queued and flushed");
        }
    }

    private static int countOccurrences(final String s, final String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }
}
