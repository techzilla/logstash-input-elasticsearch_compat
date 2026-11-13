package org.logstash.plugins.inputs.escompat.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.logstash.plugins.inputs.escompat.BatchHandler;
import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;
import org.logstash.plugins.inputs.escompat.bulk.Operation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Request decompression and response compression (FR-001–FR-005, T006). */
class CompressionTest {
    private static final String BODY = "{\"index\":{\"_index\":\"a\",\"_id\":\"1\"}}\n{\"m\":\"hello\"}\n"
        + "{\"create\":{\"_index\":\"b\"}}\n{\"n\":2}\n";

    private static final class Recorder implements BatchHandler {
        final List<List<Map<String, Object>>> docs = new CopyOnWriteArrayList<>();

        @Override
        public void submit(final List<Operation> ops, final ItemOutcome[] results) {
            final List<Map<String, Object>> d = new ArrayList<>();
            for (final Operation op : ops) d.add(op.getDocument());
            docs.add(d);
            Arrays.fill(results, ItemOutcome.QUEUED);
        }
    }

    static byte[] gzip(final byte[] in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(out)) {
            z.write(in);
        }
        return out.toByteArray();
    }

    static byte[] deflate(final byte[] in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(out)) {
            z.write(in);
        }
        return out.toByteArray();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "x-gzip", "deflate", "x-deflate", "GZIP"})
    void compressedRequestsMatchPlain(final String encoding) throws Exception {
        final Recorder rec = new Recorder();
        try (TestServer s = new TestServer(rec)) {
            final byte[] plain = BODY.getBytes(StandardCharsets.UTF_8);
            final byte[] compressed = encoding.toLowerCase().contains("gzip") ? gzip(plain) : deflate(plain);
            final TestServer.Response p = s.request("POST", "/_bulk", plain);
            final TestServer.Response c = s.request("POST", "/_bulk", compressed, "Content-Encoding: " + encoding);
            assertEquals(200, c.status());
            assertEquals(p.body().replaceAll("\"took\":\\d+", ""), c.body().replaceAll("\"took\":\\d+", ""));
            assertEquals(2, rec.docs.size());
            assertEquals(rec.docs.get(0), rec.docs.get(1));
        }
    }

    @Test
    void decompressionBombStopsAtLimit() throws Exception {
        final Recorder rec = new Recorder();
        final StringBuilder big = new StringBuilder();
        while (big.length() < 400_000) {
            big.append("{\"index\":{\"_index\":\"a\"}}\n{\"m\":\"").append("x".repeat(200)).append("\"}\n");
        }
        final byte[] bomb = gzip(big.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(bomb.length < 64 * 1024, "compressed body fits the limit; only its expansion does not");
        try (TestServer s = new TestServer(rec, b -> b.maxContentLength(64 * 1024))) {
            final TestServer.Response r = s.request("POST", "/_bulk", bomb, "Content-Encoding: gzip");
            assertEquals(413, r.status());
        }
        assertEquals(0, rec.docs.size());
    }

    @Test
    void corruptGzipIs400() throws Exception {
        final Recorder rec = new Recorder();
        try (TestServer s = new TestServer(rec)) {
            final byte[] bad = Arrays.copyOf(gzip(BODY.getBytes(StandardCharsets.UTF_8)), 30);
            bad[12] ^= 0x5a;
            final TestServer.Response r = s.request("POST", "/_bulk", bad, "Content-Encoding: gzip");
            assertEquals(400, r.status());
            assertTrue(r.body().contains("parse_exception"), r.body());
            assertEquals("Elasticsearch", r.header("X-Elastic-Product"));
        }
        assertEquals(0, rec.docs.size());
    }

    @Test
    void brotliIs415() throws Exception {
        final Recorder rec = new Recorder();
        try (TestServer s = new TestServer(rec)) {
            assertEquals(415, s.request("POST", "/_bulk", BODY.getBytes(), "Content-Encoding: br").status());
        }
        assertEquals(0, rec.docs.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "deflate"})
    void responsesCompressedWhenAccepted(final String encoding) throws Exception {
        try (TestServer s = new TestServer(new Recorder())) {
            for (final String[] req : new String[][] {{"POST", "/_bulk", BODY}, {"POST", "/_bulk", "{broken\n"},
                {"POST", "/nope", ""}, {"GET", "/", ""}}) {
                final TestServer.Response plain = s.request(req[0], req[1], req[2].getBytes());
                final TestServer.Response zipped = s.request(req[0], req[1], req[2].getBytes(),
                    "Accept-Encoding: " + encoding);
                assertEquals(encoding, zipped.header("Content-Encoding"), String.join(" ", req));
                assertEquals(plain.status(), zipped.status());
                assertEquals(plain.body().replaceAll("\"took\":\\d+", ""),
                    zipped.body().replaceAll("\"took\":\\d+", ""));
                assertNull(plain.header("Content-Encoding"));
            }
        }
    }

    @Test
    void compressionDisabledStillDecodesRequests() throws Exception {
        final Recorder rec = new Recorder();
        try (TestServer s = new TestServer(rec, b -> b.compressionEnabled(false))) {
            final TestServer.Response r = s.request("POST", "/_bulk", gzip(BODY.getBytes(StandardCharsets.UTF_8)),
                "Content-Encoding: gzip", "Accept-Encoding: gzip");
            assertEquals(200, r.status());
            assertNull(r.header("Content-Encoding"));
            assertEquals(1, rec.docs.size());
        }
    }

    @Test
    void identityEncodingUnchanged() throws Exception {
        try (TestServer s = new TestServer(new Recorder())) {
            final TestServer.Response r = s.request("POST", "/_bulk", BODY.getBytes(), "Content-Encoding: identity");
            assertEquals(200, r.status());
            assertArrayEquals(r.rawBody(), r.decoded());
        }
    }
}
