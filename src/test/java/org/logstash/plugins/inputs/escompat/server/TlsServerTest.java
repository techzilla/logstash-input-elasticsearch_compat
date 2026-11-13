package org.logstash.plugins.inputs.escompat.server;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.BatchHandler;
import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;
import org.logstash.plugins.inputs.escompat.server.tls.SslContextFactory;
import org.logstash.plugins.inputs.escompat.server.tls.TlsFixtures;
import org.logstash.plugins.inputs.escompat.server.tls.TlsSettings;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.logstash.plugins.inputs.escompat.server.tls.TlsFixtures.path;

/** T018: TLS end to end against the real server. */
class TlsServerTest {
    private static final BatchHandler OK = (ops, results) -> Arrays.fill(results, ItemOutcome.QUEUED);
    private static final String BODY = "{\"index\":{\"_index\":\"a\"}}\n{}\n";

    private static TestServer server(final TlsSettings tls, final long handshakeMillis) throws Exception {
        return new TestServer(OK, b -> {
            try {
                b.sslContext(SslContextFactory.build(tls)).sslHandshakeTimeoutMillis(handshakeMillis);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static TestServer server(final TlsSettings tls) throws Exception {
        return server(tls, 10_000);
    }

    @Test
    void httpsBulkAndIdentity() throws Exception {
        try (TestServer s = server(TlsFixtures.pem("none", null))) {
            s.tls(TlsFixtures.client(null, null));
            assertEquals(200, s.post("/_bulk", BODY).status());
            assertEquals(200, s.request("GET", "/", new byte[0]).status());
        }
    }

    @Test
    void plainHttpOnTlsPortFails() throws Exception {
        try (TestServer s = server(TlsFixtures.pem("none", null))) {
            assertThrows(Exception.class, () -> {
                final TestServer.Response r = s.post("/_bulk", BODY);
                assertTrue(r.status() < 200, "plain HTTP must not succeed");
            });
        }
    }

    @Test
    void requiredClientAuthentication() throws Exception {
        try (TestServer s = server(TlsFixtures.pem("required", List.of(path("ca.crt"))))) {
            assertHandshakeFails(s, TlsFixtures.client(null, null));
            assertHandshakeFails(s, TlsFixtures.client("untrusted-client.p12", null));
            s.tls(TlsFixtures.client("client.p12", null));
            assertEquals(200, s.post("/_bulk", BODY).status());
        }
    }

    @Test
    void optionalClientAuthentication() throws Exception {
        try (TestServer s = server(TlsFixtures.pem("optional", List.of(path("ca.crt"))))) {
            s.tls(TlsFixtures.client(null, null));
            assertEquals(200, s.post("/_bulk", BODY).status());
        }
    }

    @Test
    void protocolMismatchFails() throws Exception {
        final TlsSettings tls13Only = new TlsSettings(path("server.crt"), path("server.key"), null, null, null, null,
            null, null, null, "none", List.of("TLSv1.3"), null);
        try (TestServer s = server(tls13Only)) {
            assertHandshakeFails(s, TlsFixtures.client(null, "TLSv1.2"));
        }
    }

    @Test
    void cipherMismatchFails() throws Exception {
        final TlsSettings oneCipher = new TlsSettings(path("server.crt"), path("server.key"), null, null, null, null,
            null, null, null, "none", List.of("TLSv1.2"), List.of("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"));
        try (TestServer s = server(oneCipher)) {
            final SSLContext ctx = TlsFixtures.client(null, "TLSv1.2");
            try (SSLSocket sock = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", s.port)) {
                sock.setEnabledProtocols(new String[] {"TLSv1.2"});
                sock.setEnabledCipherSuites(new String[] {"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"});
                sock.setSoTimeout(5000);
                assertThrows(IOException.class, sock::startHandshake);
            }
        }
    }

    @Test
    void idleHandshakeTimesOut() throws Exception {
        try (TestServer s = server(TlsFixtures.pem("none", null), 500)) {
            try (Socket raw = new Socket("127.0.0.1", s.port)) {
                raw.setSoTimeout(3000);
                final long t0 = System.nanoTime();
                final InputStream in = raw.getInputStream();
                int read;
                try {
                    do {
                        read = in.read();
                    } while (read >= 0);
                } catch (IOException closed) {
                    // reset is also a close
                }
                final long ms = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(ms < 1500, "connection closed after " + ms + " ms");
            }
        }
    }

    private static void assertHandshakeFails(final TestServer s, final SSLContext ctx) {
        s.tls(ctx);
        assertThrows(IOException.class, () -> {
            final TestServer.Response r = s.post("/_bulk", BODY);
            // TLS 1.3 may report a rejected client certificate only on first read
            assertTrue(r.status() < 0, "handshake should fail, got " + r.status());
        });
    }
}
