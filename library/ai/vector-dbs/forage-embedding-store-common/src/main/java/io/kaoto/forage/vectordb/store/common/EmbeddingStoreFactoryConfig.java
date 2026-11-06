package io.kaoto.forage.vectordb.store.common;

import io.kaoto.forage.core.util.config.AbstractConfig;
import io.kaoto.forage.core.util.config.MissingConfigException;

import static io.kaoto.forage.vectordb.store.common.EmbeddingStoreFactoryConfigEntries.KIND;

public class EmbeddingStoreFactoryConfig extends AbstractConfig {
    public EmbeddingStoreFactoryConfig() {
        this(null);
    }

    public EmbeddingStoreFactoryConfig(String prefix) {
        super(prefix, EmbeddingStoreFactoryConfigEntries.class);
    }

    @Override
    public String name() {
        return "forage-embedding-store";
    }

    public String kind() {
        return get(KIND)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElseThrow(() -> new MissingConfigException("Missing embedding store kind, set "
                        + KIND.asNamed(prefix()).propertyName()));
    }
}
