package io.kaoto.forage.quarkus.embeddings;

import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.ForageQuarkusConfigSourceAdapter;
import io.kaoto.forage.models.embeddings.common.EmbeddingModelFactoryConfig;
import io.kaoto.forage.models.embeddings.common.EmbeddingModelModuleDescriptor;

/**
 * Discovers the embedding model instances from all Quarkus config sources, for the deployment processor.
 */
public class ForageEmbeddingModelConfigSourceFactory
        extends ForageQuarkusConfigSourceAdapter<EmbeddingModelFactoryConfig> {
    @Override
    protected ForageModuleDescriptor<EmbeddingModelFactoryConfig, ?> descriptor() {
        return new EmbeddingModelModuleDescriptor();
    }
}
