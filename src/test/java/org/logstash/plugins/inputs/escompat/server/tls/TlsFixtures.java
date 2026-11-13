package org.logstash.plugins.inputs.escompat.server.tls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.List;

/** Paths to the committed fixtures in src/test/resources/tls and client SSLContexts built from them. */
public final class TlsFixtures {
    public static final String PASS = "changeit";

    private TlsFixtures() {
    }

    public static String path(final String name) {
        try {
            return Path.of(TlsFixtures.class.getResource("/tls/" + name).toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TlsSettings pem(final String clientAuth, final List<String> cas) {
        return new TlsSettings(path("server.crt"), path("server.key"), null, null, null, null, cas,
            null, null, clientAuth, null, null);
    }

    /** Client context trusting ca.crt, optionally presenting a PKCS12 client certificate. */
    public static SSLContext client(final String clientP12, final String protocol) throws Exception {
        final KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        try (InputStream in = Files.newInputStream(Path.of(path("ca.crt")))) {
            trust.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        KeyManagerFactory kmf = null;
        if (clientP12 != null) {
            final KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(path(clientP12)))) {
                ks.load(in, PASS.toCharArray());
            }
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, PASS.toCharArray());
        }
        final SSLContext ctx = SSLContext.getInstance(protocol == null ? "TLS" : protocol);
        ctx.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }
}
