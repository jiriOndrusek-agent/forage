package io.kaoto.forage.vectordb.store;

import io.kaoto.forage.core.util.config.ConfigEntries;
import io.kaoto.forage.core.util.config.ConfigModule;
import io.kaoto.forage.core.util.config.ConfigTag;

public final class EmbeddingStoreFactoryConfigEntries extends ConfigEntries {
    public static final ConfigModule KIND = ConfigModule.ofBeanName(
            EmbeddingStoreFactoryConfig.class,
            "forage.vectordb.kind",
            "The embedding store provider kind (e.g., in-memory-store, pgvector, qdrant)",
            "Embedding Store Kind",
            true,
            ConfigTag.COMMON,
            "Embedding Store");

    static {
        initModules(EmbeddingStoreFactoryConfigEntries.class, KIND);
    }
}
