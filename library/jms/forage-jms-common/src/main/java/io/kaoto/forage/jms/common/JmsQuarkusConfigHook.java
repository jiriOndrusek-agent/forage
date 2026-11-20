package io.kaoto.forage.jms.common;

import java.util.Map;
import java.util.Set;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.QuarkusConfigHook;
import io.kaoto.forage.core.util.config.ConfigStore;

public final class JmsQuarkusConfigHook implements QuarkusConfigHook<ConnectionFactoryConfig> {

    /** The catalog group whose dependency (quarkus-pooled-jms) a pooled JMS connection factory needs. */
    static final String POOL_GROUP = "jms-connection-pool";

    @Override
    public ForageModuleDescriptor<ConnectionFactoryConfig, ?> descriptor() {
        return new JmsModuleDescriptor();
    }

    @Override
    public Set<String> covered() {
        // not covered: client.id, ssl.*, pool.connection.timeout.millis, transaction.*, artemis.*
        return Set.of(
                "kind",
                "broker.url",
                "username",
                "password",
                "pool.enabled",
                "pool.max.connections",
                "pool.max.sessions.per.connection",
                "pool.idle.timeout.millis",
                "pool.expiry.timeout.millis",
                "pool.block.if.full",
                "pool.block.if.full.timeout.millis");
    }

    @Override
    public String unsupported(String name, ConnectionFactoryConfig config) {
        // per-broker components come from the Forage extension
        return name != null ? "uses named configurations, which need the Forage extension" : null;
    }

    @Override
    public Map<String, String> sources() {
        return Map.of(
                "quarkus.artemis.url", "{broker.url}",
                "quarkus.artemis.username", "{username}",
                "quarkus.artemis.password", "{password}",
                "quarkus.pooled-jms.max-connections", "{pool.max.connections}",
                "quarkus.pooled-jms.max-sessions-per-connection", "{pool.max.sessions.per.connection}",
                "quarkus.pooled-jms.connection-idle-timeout", "{pool.idle.timeout.millis}",
                "quarkus.pooled-jms.connection-check-interval", "{pool.expiry.timeout.millis}",
                "quarkus.pooled-jms.block-if-session-pool-is-full", "{pool.block.if.full}",
                "quarkus.pooled-jms.block-if-session-pool-is-full-timeout", "{pool.block.if.full.timeout.millis}");
    }

    @Override
    public Set<String> adjust(ConnectionFactoryConfig config, Map<String, String> translated) {
        // as the Forage export: quarkus-pooled-jms only for an explicit pool.enabled=true (the store holds only the
        // project values); without it the pool keys stay inert, as the Forage extension left them
        String poolEnabled = ConfigStore.getInstance()
                .get(ConnectionFactoryConfigEntries.POOL_ENABLED)
                .orElse(null);
        if (poolEnabled == null) {
            return Set.of();
        }
        if (config.poolEnabled()) {
            return Set.of(POOL_GROUP);
        }
        // switched off explicitly: quarkus-pooled-jms may still come from another dependency
        translated.keySet().removeIf(key -> key.startsWith("quarkus.pooled-jms."));
        translated.put("quarkus.pooled-jms.pooling.enabled", "false");
        return Set.of();
    }
}
