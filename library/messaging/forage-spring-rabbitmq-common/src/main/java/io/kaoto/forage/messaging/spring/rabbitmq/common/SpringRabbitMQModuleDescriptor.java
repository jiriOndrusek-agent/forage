package io.kaoto.forage.messaging.spring.rabbitmq.common;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.QuarkusBean;
import io.kaoto.forage.core.messaging.SpringRabbitMQConnectionFactoryProvider;

/**
 * Module descriptor for Forage Spring RabbitMQ. Captures Spring RabbitMQ-specific knowledge:
 * prefix discovery, provider resolution, and bean naming conventions.
 *
 * <p>Unlike JMS/JDBC, Spring RabbitMQ has only one provider type (CachingConnectionFactory),
 * no auxiliary beans, and no Quarkus property translation (Spring components work natively in Quarkus).
 *
 * @since 1.4
 */
public class SpringRabbitMQModuleDescriptor
        implements ForageModuleDescriptor<SpringRabbitMQConfig, SpringRabbitMQConnectionFactoryProvider> {

    @Override
    public String modulePrefix() {
        return SpringRabbitMQConstants.MODULE_PREFIX;
    }

    private static final String RABBIT = "rabbitConnectionFactory.";

    private static final QuarkusBean QUARKUS_BEAN = new QuarkusBean(
            List.of("#class:org.springframework.amqp.rabbit.connection.CachingConnectionFactory"),
            Map.ofEntries(
                    Map.entry("host", List.of(RABBIT + "host")),
                    Map.entry("port", List.of(RABBIT + "port")),
                    Map.entry("username", List.of(RABBIT + "username")),
                    Map.entry("password", List.of(RABBIT + "password")),
                    Map.entry("virtual.host", List.of(RABBIT + "virtualHost")),
                    Map.entry("requested.heartbeat", List.of(RABBIT + "requestedHeartbeat")),
                    Map.entry("connection.timeout", List.of(RABBIT + "connectionTimeout")),
                    Map.entry("automatic.recovery.enabled", List.of(RABBIT + "automaticRecoveryEnabled")),
                    Map.entry("network.recovery.interval", List.of(RABBIT + "networkRecoveryInterval")),
                    Map.entry("channel.cache.size", List.of("channelCacheSize")),
                    Map.entry("connection.cache.size", List.of("connectionCacheSize")),
                    Map.entry("channel.checkout.timeout", List.of("channelCheckoutTimeout")),
                    Map.entry("addresses", List.of("addresses")),
                    Map.entry("cache.mode", List.of("cacheMode"))),
            Map.of(),
            List.of(
                    "org.springframework.amqp.rabbit.connection.AbstractConnectionFactory",
                    "com.rabbitmq.client.ConnectionFactory"),
            List.of("org.springframework.amqp:spring-rabbit", "com.rabbitmq:amqp-client"),
            List.of());

    /**
     * The Quarkus export creates the {@code CachingConnectionFactory} as {@link SpringRabbitMQConnectionFactoryHelper}
     * does: the RabbitMQ client keys on its {@code rabbitConnectionFactory}, the cache keys on it. Read by the catalog
     * build.
     */
    public QuarkusBean quarkusBean() {
        return QUARKUS_BEAN;
    }

    @Override
    public SpringRabbitMQConfig createConfig(String prefix) {
        return prefix == null ? new SpringRabbitMQConfig() : new SpringRabbitMQConfig(prefix);
    }

    @Override
    public Class<SpringRabbitMQConnectionFactoryProvider> providerClass() {
        return SpringRabbitMQConnectionFactoryProvider.class;
    }

    @Override
    public String resolveProviderClassName(SpringRabbitMQConfig config) {
        // Spring RabbitMQ has only one provider implementation
        return "io.kaoto.forage.messaging.spring.rabbitmq.common.SpringRabbitMQConnectionFactoryProvider";
    }

    @Override
    public String defaultBeanName() {
        return SpringRabbitMQConstants.DEFAULT_BEAN_NAME;
    }

    @Override
    public List<String> defaultBeanAliases() {
        // No aliases needed for Spring RabbitMQ
        return Collections.emptyList();
    }

    @Override
    public Class<?> primaryBeanClass() {
        return org.springframework.amqp.rabbit.connection.ConnectionFactory.class;
    }

    @Override
    public boolean transactionEnabled(SpringRabbitMQConfig config) {
        // Spring RabbitMQ does not use transactions in the same way as JMS/JDBC
        return false;
    }

    @Override
    public Map<String, String> translateProperties(String prefix, SpringRabbitMQConfig config) {
        // No property translation needed - Spring RabbitMQ works natively in Quarkus
        return Collections.emptyMap();
    }
}
