package io.kaoto.forage.jdbc.common;

import java.util.Map;
import java.util.Set;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.QuarkusConfigHook;

public final class JdbcQuarkusConfigHook implements QuarkusConfigHook<DataSourceFactoryConfig> {

    @Override
    public ForageModuleDescriptor<DataSourceFactoryConfig, ?> descriptor() {
        return new JdbcModuleDescriptor();
    }

    @Override
    public Set<String> covered() {
        // not covered: pool.idle.validation.timeout.minutes, transaction.*, the aggregation/idempotent repositories
        return Set.of(
                "db.kind",
                "url",
                "username",
                "password",
                "pool.initial.size",
                "pool.min.size",
                "pool.max.size",
                "pool.acquisition.timeout.seconds",
                "pool.validation.timeout.seconds",
                "pool.leak.timeout.minutes",
                "properties.");
    }

    @Override
    public Map<String, String> sources() {
        String ds = "quarkus.datasource.\"{name}\".";
        return Map.ofEntries(
                Map.entry(ds + "db-kind", "{db.kind}"),
                Map.entry(ds + "jdbc.url", "{url}"),
                Map.entry(ds + "username", "{username}"),
                Map.entry(ds + "password", "{password}"),
                Map.entry(ds + "jdbc.initial-size", "{pool.initial.size}"),
                Map.entry(ds + "jdbc.min-size", "{pool.min.size}"),
                Map.entry(ds + "jdbc.max-size", "{pool.max.size}"),
                Map.entry(ds + "jdbc.acquisition-timeout", "{pool.acquisition.timeout.seconds}S"),
                Map.entry(ds + "jdbc.validation-query-timeout", "{pool.validation.timeout.seconds}S"),
                Map.entry(ds + "jdbc.leak-detection-interval", "{pool.leak.timeout.minutes}M"),
                Map.entry(ds + "jdbc.additional-jdbc-properties.", "{properties.}"));
    }

    @Override
    public Set<String> dependencyKeys() {
        // the JDBC driver is added for the Forage db.kind; kinds without a Quarkus driver in the catalog stay forage.*
        return Set.of("db-kind");
    }
}
