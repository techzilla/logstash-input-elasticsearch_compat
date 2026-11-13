package org.logstash.plugins.inputs.escompat.server.tls;

import java.util.List;

/**
 * TLS settings as configured by the user (contracts/plugin-config.md). Cross-setting validation
 * happens in the Ruby adapter, matching {@code logstash-input-http}; {@link SslContextFactory}
 * only checks what it needs to build a context.
 *
 * @param certificate        PEM certificate path, or {@code null}
 * @param key                PEM key path, or {@code null}
 * @param keyPassphrase      key passphrase, or {@code null}
 * @param keystorePath       keystore path, or {@code null}
 * @param keystorePassword   keystore password, or {@code null}
 * @param keystoreType       {@code pkcs12}/{@code jks}, or {@code null} to infer from the file name
 * @param certificateAuthorities PEM CA paths (empty if none)
 * @param truststorePath     truststore path, or {@code null}
 * @param truststorePassword truststore password, or {@code null}
 * @param clientAuthentication {@code none}, {@code optional} or {@code required}
 * @param supportedProtocols e.g. {@code TLSv1.2}, {@code TLSv1.3}
 * @param cipherSuites       cipher suite names; empty means the default list
 */
public record TlsSettings(String certificate, String key, String keyPassphrase,
                          String keystorePath, String keystorePassword, String keystoreType,
                          List<String> certificateAuthorities,
                          String truststorePath, String truststorePassword,
                          String clientAuthentication, List<String> supportedProtocols,
                          List<String> cipherSuites) {
    public TlsSettings {
        certificateAuthorities = certificateAuthorities == null ? List.of() : List.copyOf(certificateAuthorities);
        supportedProtocols = supportedProtocols == null || supportedProtocols.isEmpty()
            ? List.of("TLSv1.2", "TLSv1.3") : List.copyOf(supportedProtocols);
        cipherSuites = cipherSuites == null ? List.of() : List.copyOf(cipherSuites);
        clientAuthentication = clientAuthentication == null ? "none" : clientAuthentication;
    }
}
