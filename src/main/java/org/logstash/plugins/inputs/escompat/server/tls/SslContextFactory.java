/*
 * Portions of this file are derived from logstash-input-http's SslSimpleBuilder
 * (https://github.com/logstash-plugins/logstash-input-http), Copyright Elasticsearch B.V.,
 * licensed under the Apache License, Version 2.0. See NOTICE.TXT.
 */
package org.logstash.plugins.inputs.escompat.server.tls;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.Cipher;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the server {@link SslContext} with the same behavior as {@code logstash-input-http}
 * (research R5): JDK TLS engine, PEM or keystore key material, client trust from certificate
 * authorities, a truststore, or the keystore itself, and its default cipher list.
 */
public final class SslContextFactory {
    private static final Logger LOGGER = LogManager.getLogger(SslContextFactory.class);

    private static final Set<String> SUPPORTED_CIPHERS = Set.of(
        ((SSLServerSocketFactory) SSLServerSocketFactory.getDefault()).getSupportedCipherSuites());

    /** Mozilla server-side TLS compatibility list, as in logstash-input-http. */
    private static final String[] DEFAULT_CIPHER_CANDIDATES = {
        // Modern compatibility
        "TLS_AES_256_GCM_SHA384",
        "TLS_AES_128_GCM_SHA256",
        "TLS_CHACHA20_POLY1305_SHA256",
        // Intermediate compatibility
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        // Backward compatibility
        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
    };

    private static final String[] DEFAULT_CIPHERS_LIMITED = {
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
    };

    private SslContextFactory() {
    }

    /** Cipher suites the runtime supports (allowed values of {@code ssl_cipher_suites}). */
    public static String[] supportedCiphers() {
        return SUPPORTED_CIPHERS.toArray(String[]::new);
    }

    /** Default of {@code ssl_cipher_suites}. */
    public static String[] defaultCiphers() {
        if (isUnlimitedJceAvailable()) {
            return Arrays.stream(DEFAULT_CIPHER_CANDIDATES).filter(SUPPORTED_CIPHERS::contains).toArray(String[]::new);
        }
        LOGGER.warn("JCE Unlimited Strength Jurisdiction Policy not installed - max key length is 128 bits");
        return DEFAULT_CIPHERS_LIMITED.clone();
    }

    public static SslContext build(final TlsSettings s) throws Exception {
        final String[] ciphers = s.cipherSuites().isEmpty()
            ? defaultCiphers() : s.cipherSuites().toArray(String[]::new);
        for (final String cipher : ciphers) {
            if (!SUPPORTED_CIPHERS.contains(cipher)) {
                throw new IllegalArgumentException("Cipher `" + cipher + "` is not available");
            }
        }
        final ClientAuth clientAuth = clientAuth(s.clientAuthentication());

        final KeyStore keyStore = s.keystorePath() == null ? null
            : readKeyStore(readable(s.keystorePath(), "Keystore"), s.keystoreType(), s.keystorePassword());
        final SslContextBuilder builder;
        if (s.certificate() != null) {
            builder = SslContextBuilder.forServer(readable(s.certificate(), "Certificate").toFile(),
                readable(s.key(), "Private key").toFile(), s.keyPassphrase());
        } else if (keyStore != null) {
            final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, chars(s.keystorePassword()));
            builder = SslContextBuilder.forServer(kmf);
        } else {
            throw new IllegalStateException("Either a KeyStore or an SSL certificate must be provided");
        }
        builder.sslProvider(SslProvider.JDK)
            .ciphers(Arrays.asList(ciphers))
            .protocols(s.supportedProtocols().toArray(String[]::new))
            .clientAuth(clientAuth);

        if (clientAuth != ClientAuth.NONE) {
            if (!s.certificateAuthorities().isEmpty()) {
                builder.trustManager(loadCertificates(s.certificateAuthorities()));
            } else if (s.truststorePath() != null) {
                final KeyStore trustStore = readKeyStore(readable(s.truststorePath(), "Trust Store"), null,
                    s.truststorePassword());
                if (!hasTrustEntry(trustStore) && clientAuth == ClientAuth.REQUIRE) {
                    throw new IllegalArgumentException("The provided Trust Store file does not contains any "
                        + "trusted certificate entry: " + s.truststorePath());
                }
                builder.trustManager(trustManagers(trustStore));
            } else if (keyStore != null) {
                LOGGER.info("SSL Trust Store not configured, using the provided Key Store instead.");
                builder.trustManager(trustManagers(keyStore));
            } else {
                throw new IllegalStateException("Either an SSL certificate or an SSL Trust Store is required when SSL is enabled");
            }
        }

        try {
            return builder.build();
        } catch (SSLException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static ClientAuth clientAuth(final String mode) {
        switch (mode) {
            case "none": return ClientAuth.NONE;
            case "optional": return ClientAuth.OPTIONAL;
            case "required": return ClientAuth.REQUIRE;
            default: throw new IllegalArgumentException("Invalid `ssl_client_authentication` value " + mode);
        }
    }

    private static Path readable(final String file, final String what) {
        final Path p = Path.of(file);
        if (!Files.isReadable(p)) {
            throw new IllegalArgumentException(what + " file cannot be read. Please confirm the user running "
                + "Logstash has permissions to read: " + file);
        }
        return p;
    }

    private static KeyStore readKeyStore(final Path path, final String type, final String password)
        throws GeneralSecurityException, IOException {
        final KeyStore ks = KeyStore.getInstance(type == null || type.isEmpty() ? inferType(path) : type);
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, chars(password));
        }
        return ks;
    }

    static String inferType(final Path path) {
        final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".pkcs12") ? "PKCS12" : "jks";
    }

    private static TrustManagerFactory trustManagers(final KeyStore store) throws GeneralSecurityException {
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        return tmf;
    }

    private static boolean hasTrustEntry(final KeyStore store) throws GeneralSecurityException {
        final Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            if (store.isCertificateEntry(aliases.nextElement())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static X509Certificate[] loadCertificates(final List<String> files) throws Exception {
        final CertificateFactory cf = CertificateFactory.getInstance("X.509");
        final List<X509Certificate> all = new ArrayList<>();
        for (final String f : files) {
            try (InputStream in = Files.newInputStream(readable(f, "Certificate authority"))) {
                all.addAll((java.util.Collection<X509Certificate>) cf.generateCertificates(in));
            }
        }
        return all.toArray(new X509Certificate[0]);
    }

    private static char[] chars(final String s) {
        return s == null ? null : s.toCharArray();
    }

    private static boolean isUnlimitedJceAvailable() {
        try {
            return Cipher.getMaxAllowedKeyLength("AES") > 128;
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }
}
