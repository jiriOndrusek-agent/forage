package io.kaoto.forage.jdbc.common;

import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.QuarkusNativeHook;

public final class JdbcQuarkusNativeHook implements QuarkusNativeHook<DataSourceFactoryConfig> {

    @Override
    public ForageModuleDescriptor<DataSourceFactoryConfig, ?> descriptor() {
        return new JdbcModuleDescriptor();
    }

    @Override
    public String unsupported(String name, DataSourceFactoryConfig config) {
        // the aggregation repository needs transactions
        return config.enableIdempotentRepository() ? "uses Forage repositories" : null;
    }
}
