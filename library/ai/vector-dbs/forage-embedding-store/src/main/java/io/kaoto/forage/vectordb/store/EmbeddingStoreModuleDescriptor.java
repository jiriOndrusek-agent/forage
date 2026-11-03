package io.kaoto.forage.vectordb.store;

import java.util.ServiceLoader;
import io.kaoto.forage.core.ai.EmbeddingStoreProvider;
import io.kaoto.forage.core.annotations.ForageBean;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.ServiceLoaderHelper;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * Module descriptor for Forage embedding stores. The configured kind selects the provider by its
 * {@link ForageBean} name (e.g., {@code pgvector}).
 */
public class EmbeddingStoreModuleDescriptor
        implements ForageModuleDescriptor<EmbeddingStoreFactoryConfig, EmbeddingStoreProvider> {

    @Override
    public String modulePrefix() {
        return "vectordb";
    }

    // Instances are found by their kind key only, like the embedding models
    @Override
    public String namedPropertyRegexp() {
        return "forage\\.(.+)\\.vectordb\\.kind";
    }

    @Override
    public String defaultPropertyRegexp() {
        return "forage\\.(vectordb)\\.kind";
    }

    @Override
    public EmbeddingStoreFactoryConfig createConfig(String prefix) {
        return new EmbeddingStoreFactoryConfig(prefix);
    }

    @Override
    public Class<EmbeddingStoreProvider> providerClass() {
        return EmbeddingStoreProvider.class;
    }

    @Override
    public String resolveProviderClassName(EmbeddingStoreFactoryConfig config) {
        return ServiceLoaderHelper.findProviderByBeanName(
                        ServiceLoader.load(EmbeddingStoreProvider.class).stream()
                                .toList(),
                        config.kind(),
                        "embedding store")
                .type()
                .getName();
    }

    @Override
    public String defaultBeanName() {
        return "embeddingStore";
    }

    @Override
    public Class<?> primaryBeanClass() {
        return EmbeddingStore.class;
    }

    @Override
    public boolean transactionEnabled(EmbeddingStoreFactoryConfig config) {
        return false;
    }
}
