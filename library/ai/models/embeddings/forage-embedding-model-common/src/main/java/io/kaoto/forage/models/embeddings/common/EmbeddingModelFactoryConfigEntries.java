package io.kaoto.forage.models.embeddings.common;

import io.kaoto.forage.core.util.config.ConfigEntries;
import io.kaoto.forage.core.util.config.ConfigModule;
import io.kaoto.forage.core.util.config.ConfigTag;

public final class EmbeddingModelFactoryConfigEntries extends ConfigEntries {
    public static final ConfigModule KIND = ConfigModule.ofBeanName(
            EmbeddingModelFactoryConfig.class,
            "forage.embeddings.kind",
            "The embedding model provider kind (e.g., ollama)",
            "Embedding Model Kind",
            true,
            ConfigTag.COMMON,
            "Embeddings Model");

    static {
        initModules(EmbeddingModelFactoryConfigEntries.class, KIND);
    }
}
