package io.kaoto.forage.models.embeddings;

import java.util.ServiceLoader;
import io.kaoto.forage.core.ai.EmbeddingModelProvider;
import io.kaoto.forage.core.common.ServiceLoaderHelper;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * Creates {@link EmbeddingModel} instances for the embedding model factories of all runtimes and for the Agent.
 */
public final class EmbeddingModelCreator {

    private EmbeddingModelCreator() {}

    /**
     * Creates the model of {@code forage.[<prefix>.]embeddings.kind}.
     *
     * @param prefix the instance name, or {@code null} for the default instance
     * @throws IllegalStateException if no provider of the kind is found or it creates no model
     */
    public static EmbeddingModel create(String prefix, ClassLoader classLoader) {
        String kind = new EmbeddingModelFactoryConfig(prefix).kind();
        EmbeddingModel model = create(kind, prefix, classLoader);
        if (model == null) {
            throw new IllegalStateException("The '%s' embedding model provider created no model%s"
                    .formatted(kind, prefix == null ? "" : " for '" + prefix + "'"));
        }
        return model;
    }

    /**
     * Creates a model with the provider of the kind ({@code @ForageBean} name), configured by its
     * {@code forage.[<prefix>.]<provider>.*} keys.
     *
     * @return the model, or {@code null} if the provider creates none
     * @throws IllegalStateException if no provider of the kind is found
     */
    public static EmbeddingModel create(String kind, String prefix, ClassLoader classLoader) {
        return ServiceLoaderHelper.findProviderByBeanName(
                        ServiceLoader.load(EmbeddingModelProvider.class, classLoader).stream()
                                .toList(),
                        kind,
                        "embedding model")
                .get()
                .create(prefix);
    }
}
