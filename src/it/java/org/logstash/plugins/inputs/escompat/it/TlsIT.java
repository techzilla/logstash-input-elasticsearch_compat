package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.logstash.plugins.inputs.escompat.it.support.Http;
import org.logstash.plugins.inputs.escompat.it.support.LogstashContainer;
import org.logstash.plugins.inputs.escompat.it.support.OtelCollectorContainer;
import org.logstash.plugins.inputs.escompat.server.tls.TlsFixtures;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T021 (US2, SC-004): TLS setups in real Logstash, the exporter over TLS, invalid config. */
class TlsIT {

    @ParameterizedTest
    @ValueSource(strings = {"tls-pem.conf", "tls-keystore.conf"})
    void httpsBulkAndIdentity(final String pipeline) throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer(pipeline, "memory", net).withTlsFixtures()) {
            ls.start();
            final HttpClient https = Http.client(TlsFixtures.client(null, null));
            final Http.Response r = Http.post(https, ls.uri("https", 9200, "/_bulk"), Http.op("index", "a", "{\"m\":1}"));
            assertEquals(200, r.status());
            assertEquals(1, ls.awaitEvents(1, Duration.ofSeconds(20)).size());
            final Http.Response id = Http.send(https, "GET", ls.uri("https", 9200, "/"), null);
            assertEquals(200, id.status());
            assertTrue(id.body().contains("8.19.0"));
        }
    }

    @Test
    void clientAuthenticationRequired() throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("tls-client-auth.conf", "memory", net).withTlsFixtures()) {
            ls.start();
            final String body = Http.op("index", "a", "{\"m\":1}");
            assertThrows(IOException.class, () ->
                Http.post(Http.client(TlsFixtures.client(null, null)), ls.uri("https", 9200, "/_bulk"), body));
            assertEquals(200, Http.post(Http.client(TlsFixtures.client("client.p12", null)),
                ls.uri("https", 9200, "/_bulk"), body).status());
        }
    }

    @Test
    void exporterOverTls() throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("tls-pem.conf", "memory", net).withTlsFixtures();
             OtelCollectorContainer otel = new OtelCollectorContainer(net,
                 "https://" + LogstashContainer.ALIAS + ":9200", "otelcol/config-tls.yaml")) {
            ls.start();
            otel.start();
            otel.sendLogs(net, 1000);
            assertEquals(1000, ls.awaitEvents(1000, Duration.ofSeconds(60)).size());
            assertEquals(0, otel.metric("otelcol_exporter_send_failed_log_records"));
        }
    }

    @Test
    void invalidConfigurationFailsStartup() throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("tls-invalid.conf", "memory", net).withTlsFixtures()) {
            ls.waitingFor(Wait.forLogMessage(".*Using an `ssl_certificate` requires an `ssl_key`.*", 1)
                .withStartupTimeout(Duration.ofMinutes(4)));
            ls.start();
            assertTrue(ls.getLogs().contains("Using an `ssl_certificate` requires an `ssl_key`"));
        }
    }
}
