package io.kaoto.forage.models.embeddings;

import java.util.ServiceLoader;
import io.kaoto.forage.core.ai.EmbeddingModelProvider;
import io.kaoto.forage.core.annotations.ForageBean;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.ServiceLoaderHelper;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * Module descriptor for Forage embedding models. The configured kind selects the provider by its
 * {@link ForageBean} name (e.g., {@code ollama}).
 */
public class EmbeddingModelModuleDescriptor
        implements ForageModuleDescriptor<EmbeddingModelFactoryConfig, EmbeddingModelProvider> {

    @Override
    public String modulePrefix() {
        return "embeddings";
    }

    // Instances are found by their kind key only: keys of other modules contain ".embeddings." too
    // (e.g. forage.milvus.retrieve.embeddings.on.search).
    @Override
    public String namedPropertyRegexp() {
        return "forage\\.(.+)\\.embeddings\\.kind";
    }

    @Override
    public String defaultPropertyRegexp() {
        return "forage\\.(embeddings)\\.kind";
    }

    @Override
    public EmbeddingModelFactoryConfig createConfig(String prefix) {
        return new EmbeddingModelFactoryConfig(prefix);
    }

    @Override
    public Class<EmbeddingModelProvider> providerClass() {
        return EmbeddingModelProvider.class;
    }

    @Override
    public String resolveProviderClassName(EmbeddingModelFactoryConfig config) {
        return ServiceLoaderHelper.findProviderByBeanName(
                        ServiceLoader.load(EmbeddingModelProvider.class).stream()
                                .toList(),
                        config.kind(),
                        "embedding model")
                .type()
                .getName();
    }

    @Override
    public String defaultBeanName() {
        return "embeddingModel";
    }

    @Override
    public Class<?> primaryBeanClass() {
        return EmbeddingModel.class;
    }

    @Override
    public boolean transactionEnabled(EmbeddingModelFactoryConfig config) {
        return false;
    }
}
