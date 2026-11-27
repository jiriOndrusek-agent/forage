package io.kaoto.forage.security.tls;

import java.util.List;
import java.util.Map;
import org.apache.camel.support.jsse.SSLContextParameters;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.QuarkusBean;

/**
 * Describes the TLS module to the runtime adapters.
 *
 * <p>Each named profile ({@code forage.<name>.tls.*}) is registered under its name; without named profiles, the
 * default profile ({@code forage.tls.*}) is registered as {@code sslContextParameters}. Like {@link TlsBeanFactory},
 * the adapters only register profiles defining a keystore or a truststore.
 *
 * @since 1.6.3
 */
public class TlsModuleDescriptor implements ForageModuleDescriptor<TlsConfig, TlsProvider> {

    public static final String DEFAULT_BEAN_NAME = "sslContextParameters";

    @Override
    public String modulePrefix() {
        return "tls";
    }

    private static final String JSSE = "#class:org.apache.camel.support.jsse.";

    private static final QuarkusBean QUARKUS_BEAN = new QuarkusBean(
            List.of(JSSE + "SSLContextParameters"),
            Map.of(
                    "keystore.path", List.of("keyManagers.keyStore.resource"),
                    "keystore.password", List.of("keyManagers.keyStore.password", "keyManagers.keyPassword"),
                    "keystore.type", List.of("keyManagers.keyStore.type"),
                    "truststore.path", List.of("trustManagers.keyStore.resource"),
                    "truststore.password", List.of("trustManagers.keyStore.password"),
                    "truststore.type", List.of("trustManagers.keyStore.type"),
                    "client.authentication", List.of("serverParameters.clientAuthentication"),
                    "secure.socket.protocol", List.of("secureSocketProtocol")),
            Map.of(
                    "keyManagers", JSSE + "KeyManagersParameters",
                    "keyManagers.keyStore", JSSE + "KeyStoreParameters",
                    "trustManagers", JSSE + "TrustManagersParameters",
                    "trustManagers.keyStore", JSSE + "KeyStoreParameters",
                    "serverParameters", JSSE + "SSLContextServerParameters"),
            List.of(),
            List.of(),
            List.of("keystore.path", "truststore.path"),
            List.of());

    /**
     * The Quarkus export creates the {@code SSLContextParameters} as {@link TlsProvider} does, one nested bean per
     * level. {@code cipher.suites} (a list) keeps the module on {@code forage.*}. Read by the catalog build.
     */
    public QuarkusBean quarkusBean() {
        return QUARKUS_BEAN;
    }

    @Override
    public TlsConfig createConfig(String prefix) {
        return new TlsConfig(prefix);
    }

    @Override
    public Class<TlsProvider> providerClass() {
        return TlsProvider.class;
    }

    @Override
    public String resolveProviderClassName(TlsConfig config) {
        return TlsProvider.class.getName();
    }

    @Override
    public String defaultBeanName() {
        return DEFAULT_BEAN_NAME;
    }

    @Override
    public Class<?> primaryBeanClass() {
        return SSLContextParameters.class;
    }

    @Override
    public boolean transactionEnabled(TlsConfig config) {
        return false;
    }

    /**
     * Returns whether a profile creates a bean, that is defines a keystore or a truststore.
     *
     * @param config the profile configuration
     * @return {@code true} if the profile creates a bean
     */
    public boolean createsBean(TlsConfig config) {
        return config.keystorePath() != null || config.truststorePath() != null;
    }
}
