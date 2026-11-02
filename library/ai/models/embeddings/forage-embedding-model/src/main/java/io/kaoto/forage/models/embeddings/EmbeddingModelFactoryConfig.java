package io.kaoto.forage.models.embeddings;

import io.kaoto.forage.core.util.config.AbstractConfig;
import io.kaoto.forage.core.util.config.MissingConfigException;

import static io.kaoto.forage.models.embeddings.EmbeddingModelFactoryConfigEntries.KIND;

public class EmbeddingModelFactoryConfig extends AbstractConfig {
    public EmbeddingModelFactoryConfig() {
        this(null);
    }

    public EmbeddingModelFactoryConfig(String prefix) {
        super(prefix, EmbeddingModelFactoryConfigEntries.class);
    }

    @Override
    public String name() {
        return "forage-embedding-model";
    }

    public String kind() {
        return get(KIND)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElseThrow(() -> new MissingConfigException("Missing embedding model kind, set "
                        + KIND.asNamed(prefix()).propertyName()));
    }
}
