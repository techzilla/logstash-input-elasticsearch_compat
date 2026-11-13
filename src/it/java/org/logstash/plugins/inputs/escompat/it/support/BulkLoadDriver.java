package org.logstash.plugins.inputs.escompat.it.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Replays captured request bodies with N concurrent senders and measures sustained events/s from
 * the target Logstash's node stats ({@code events.in}), as SC-006 requires. Both plugins get
 * {@code Content-Type: application/x-ndjson} so {@code logstash-input-http} uses
 * {@code json_lines}, not its {@code application/json → json} mapping.
 *
 * <p>Run standalone:
 * {@code BulkLoadDriver <url> <bodiesDir> <ext ndjson|jsonl> <statsUrl> <senders> <warmupS> <measureS>}.
 */
public final class BulkLoadDriver {
    /** First {@code events} object in pipeline stats is the pipeline-level one. */
    private static final Pattern EVENTS_IN = Pattern.compile("\"events\":\\{[^}]*\"in\":(\\d+)");

    public record Result(double eventsPerSecond, long requests, Map<Integer, Long> statuses) {
    }

    private BulkLoadDriver() {
    }

    public static List<byte[]> loadBodies(final Path dir, final String ext) throws IOException {
        final List<byte[]> bodies = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (final Path p : files.filter(f -> f.toString().endsWith("." + ext)).sorted().toList()) {
                bodies.add(Files.readAllBytes(p));
            }
        }
        if (bodies.isEmpty()) {
            throw new IllegalStateException("no *." + ext + " bodies in " + dir);
        }
        return bodies;
    }

    public static Result run(final URI target, final List<byte[]> bodies, final URI statsUrl,
                             final int senders, final Duration warmup, final Duration measure)
        throws Exception {
        final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicLong requests = new AtomicLong();
        final Map<Integer, Long> statuses = new ConcurrentHashMap<>();
        final List<Thread> threads = new ArrayList<>();
        for (int s = 0; s < senders; s++) {
            final int offset = s;
            final Thread t = Thread.ofPlatform().name("sender-" + s).start(() -> {
                int i = offset;
                while (!stop.get()) {
                    final HttpRequest req = HttpRequest.newBuilder(target)
                        .timeout(Duration.ofSeconds(90))
                        .header("Content-Type", "application/x-ndjson")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bodies.get(i++ % bodies.size())))
                        .build();
                    try {
                        final HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
                        statuses.merge(res.statusCode(), 1L, Long::sum);
                        requests.incrementAndGet();
                    } catch (IOException e) {
                        statuses.merge(-1, 1L, Long::sum);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            threads.add(t);
        }
        Thread.sleep(warmup.toMillis());
        final long in0 = eventsIn(client, statsUrl);
        final long t0 = System.nanoTime();
        final long r0 = requests.get();
        Thread.sleep(measure.toMillis());
        final long in1 = eventsIn(client, statsUrl);
        final double secs = (System.nanoTime() - t0) / 1e9;
        final long r1 = requests.get();
        stop.set(true);
        for (final Thread t : threads) {
            t.join(100_000);
        }
        return new Result((in1 - in0) / secs, r1 - r0, statuses);
    }

    static long eventsIn(final HttpClient client, final URI statsUrl) throws Exception {
        final String body = client.send(HttpRequest.newBuilder(statsUrl).GET().build(),
            HttpResponse.BodyHandlers.ofString()).body();
        final Matcher m = EVENTS_IN.matcher(body.replace(" ", ""));
        if (!m.find()) {
            throw new IllegalStateException("events.in not found in node stats");
        }
        return Long.parseLong(m.group(1));
    }

    public static void main(final String[] args) throws Exception {
        final Result r = run(URI.create(args[0]), loadBodies(Path.of(args[1]), args[2]), URI.create(args[3]),
            Integer.parseInt(args[4]), Duration.ofSeconds(Long.parseLong(args[5])),
            Duration.ofSeconds(Long.parseLong(args[6])));
        System.out.printf("events/s=%.0f requests=%d statuses=%s%n", r.eventsPerSecond(), r.requests(), r.statuses());
    }
}
