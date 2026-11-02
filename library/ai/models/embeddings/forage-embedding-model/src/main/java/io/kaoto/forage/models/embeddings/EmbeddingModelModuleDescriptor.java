package io.kaoto.forage.models.embeddings;

import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import io.kaoto.forage.core.ai.EmbeddingModelProvider;
import io.kaoto.forage.core.annotations.ForageBean;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
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
        List<ServiceLoader.Provider<EmbeddingModelProvider>> providers =
                ServiceLoader.load(EmbeddingModelProvider.class).stream().toList();
        return findProvider(providers, config.kind()).type().getName();
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

    static ServiceLoader.Provider<EmbeddingModelProvider> findProvider(
            List<ServiceLoader.Provider<EmbeddingModelProvider>> providers, String kind) {
        return providers.stream()
                .filter(provider -> kind.equals(kindOf(provider)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No embedding model provider found for kind '" + kind
                        + "'. Available kinds: "
                        + providers.stream()
                                .map(EmbeddingModelModuleDescriptor::kindOf)
                                .filter(Objects::nonNull)
                                .toList()
                        + ". Add the provider dependency, e.g. forage-model-embeddings-ollama."));
    }

    private static String kindOf(ServiceLoader.Provider<EmbeddingModelProvider> provider) {
        ForageBean bean = provider.type().getAnnotation(ForageBean.class);
        return bean != null ? bean.value() : null;
    }
}
