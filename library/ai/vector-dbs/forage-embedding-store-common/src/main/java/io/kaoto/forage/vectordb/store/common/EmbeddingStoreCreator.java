package io.kaoto.forage.vectordb.store.common;

import java.util.ServiceLoader;
import io.kaoto.forage.core.ai.EmbeddingModelAware;
import io.kaoto.forage.core.ai.EmbeddingStoreProvider;
import io.kaoto.forage.core.common.ServiceLoaderHelper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

/**
 * Creates {@link EmbeddingStore} instances for the embedding store factories of all runtimes and for the Agent.
 */
public final class EmbeddingStoreCreator {

    static final String IN_MEMORY_STORE = "in-memory-store";

    private EmbeddingStoreCreator() {}

    /**
     * Creates the store of {@code forage.[<prefix>.]vectordb.kind}. An {@code in-memory-store} starts empty:
     * its provider loads a seed file with an embedding model, which only the Agent passes.
     *
     * @param prefix the instance name, or {@code null} for the default instance
     * @throws IllegalStateException if no provider of the kind is found or it creates no store
     */
    public static EmbeddingStore<TextSegment> create(String prefix, ClassLoader classLoader) {
        String kind = new EmbeddingStoreFactoryConfig(prefix).kind();
        if (IN_MEMORY_STORE.equals(kind)) {
            return new InMemoryEmbeddingStore<>();
        }
        EmbeddingStoreProvider provider = ServiceLoaderHelper.findProviderByBeanName(
                        ServiceLoader.load(EmbeddingStoreProvider.class, classLoader).stream()
                                .toList(),
                        kind,
                        "embedding store")
                .get();
        EmbeddingStore<TextSegment> store = create(provider, prefix, null);
        if (store == null) {
            throw new IllegalStateException("The '%s' embedding store provider created no store%s"
                    .formatted(kind, prefix == null ? "" : " for '" + prefix + "'"));
        }
        return store;
    }

    /**
     * Creates a store with the provider, configured by its {@code forage.[<prefix>.]<provider>.*} keys.
     * {@link EmbeddingModelAware} providers get the model when it is set.
     *
     * @return the store, or {@code null} if the provider creates none
     */
    public static EmbeddingStore<TextSegment> create(
            EmbeddingStoreProvider provider, String prefix, EmbeddingModel model) {
        if (model != null && provider instanceof EmbeddingModelAware modelAware) {
            modelAware.withEmbeddingModel(model);
        }
        return provider.create(prefix);
    }
}
