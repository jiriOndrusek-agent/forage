package io.kaoto.forage.quarkus.vectordb;

import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.ForageQuarkusConfigSourceAdapter;
import io.kaoto.forage.vectordb.store.EmbeddingStoreFactoryConfig;
import io.kaoto.forage.vectordb.store.EmbeddingStoreModuleDescriptor;

/**
 * Discovers the embedding store instances from all Quarkus config sources, for the deployment processor.
 */
public class ForageEmbeddingStoreConfigSourceFactory
        extends ForageQuarkusConfigSourceAdapter<EmbeddingStoreFactoryConfig> {
    @Override
    protected ForageModuleDescriptor<EmbeddingStoreFactoryConfig, ?> descriptor() {
        return new EmbeddingStoreModuleDescriptor();
    }
}
