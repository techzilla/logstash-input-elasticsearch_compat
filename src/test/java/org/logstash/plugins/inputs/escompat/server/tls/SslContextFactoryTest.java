package org.logstash.plugins.inputs.escompat.server.tls;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.logstash.plugins.inputs.escompat.server.tls.TlsFixtures.PASS;
import static org.logstash.plugins.inputs.escompat.server.tls.TlsFixtures.path;

/** T017: context building per supported setup. */
class SslContextFactoryTest {

    @Test
    void pemCertificateAndKey() throws Exception {
        assertNotNull(SslContextFactory.build(TlsFixtures.pem("none", null)));
    }

    @Test
    void encryptedPkcs8KeyWithPassphrase() throws Exception {
        assertNotNull(SslContextFactory.build(new TlsSettings(path("server.crt"), path("server.encrypted.key"), PASS,
            null, null, null, null, null, null, "none", null, null)));
    }

    @Test
    void pkcs1KeyMatchesLogstashInputHttp() {
        // logstash-input-http uses the same Netty PEM loader: PKCS#1 keys are rejected (use PKCS#8)
        assertThrows(Exception.class, () -> SslContextFactory.build(new TlsSettings(path("server.crt"),
            path("server.pkcs1.key"), null, null, null, null, null, null, null, "none", null, null)));
    }

    @ParameterizedTest
    @CsvSource({"server.p12,pkcs12", "server.p12,", "server.jks,jks", "server.jks,"})
    void keystores(final String file, final String type) throws Exception {
        assertNotNull(SslContextFactory.build(new TlsSettings(null, null, null, path(file), PASS, type,
            null, null, null, "none", null, null)));
    }

    @Test
    void clientAuthFromCertificateAuthorities() throws Exception {
        final SslContext ctx = SslContextFactory.build(TlsFixtures.pem("required", List.of(path("ca.crt"))));
        assertNotNull(ctx);
    }

    @Test
    void clientAuthFromTruststore() throws Exception {
        assertNotNull(SslContextFactory.build(new TlsSettings(path("server.crt"), path("server.key"), null, null, null,
            null, null, path("truststore.jks"), PASS, "required", null, null)));
    }

    @Test
    void clientAuthFromKeystoreAsTrust() throws Exception {
        assertNotNull(SslContextFactory.build(new TlsSettings(null, null, null, path("server.p12"), PASS, null,
            null, null, null, "optional", null, null)));
    }

    @Test
    void unknownCipherRejected() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            SslContextFactory.build(new TlsSettings(path("server.crt"), path("server.key"), null, null, null, null,
                null, null, null, "none", null, List.of("TLS_NOT_A_CIPHER"))));
        assertEquals("Cipher `TLS_NOT_A_CIPHER` is not available", e.getMessage());
    }

    @Test
    void protocolsAndCiphersApplied() throws Exception {
        final SslContext ctx = SslContextFactory.build(new TlsSettings(path("server.crt"), path("server.key"), null,
            null, null, null, null, null, null, "none", List.of("TLSv1.3"), List.of("TLS_AES_128_GCM_SHA256")));
        assertEquals(List.of("TLS_AES_128_GCM_SHA256"), ctx.cipherSuites());
    }

    @Test
    void defaultCiphersAreModernAndSupported() {
        final List<String> defaults = Arrays.asList(SslContextFactory.defaultCiphers());
        assertTrue(defaults.contains("TLS_AES_256_GCM_SHA384"));
        assertTrue(defaults.contains("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
        assertTrue(defaults.stream().noneMatch(c -> c.contains("RC4") || c.contains("3DES") || c.contains("_RSA_WITH_")
            && !c.contains("ECDHE")));
        assertTrue(Arrays.asList(SslContextFactory.supportedCiphers()).containsAll(defaults));
    }

    @Test
    void unreadableFileRejected() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            SslContextFactory.build(new TlsSettings("/nope.crt", path("server.key"), null, null, null, null, null,
                null, null, "none", null, null)));
        assertTrue(e.getMessage().startsWith("Certificate file cannot be read"));
    }
}
