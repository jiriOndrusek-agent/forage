package io.kaoto.forage.vectordb.store;

import io.kaoto.forage.core.annotations.FactoryType;
import io.kaoto.forage.core.annotations.ForageFactory;
import io.kaoto.forage.core.common.AbstractInstanceBeanFactory;
import io.kaoto.forage.vectordb.store.common.EmbeddingStoreCreator;
import io.kaoto.forage.vectordb.store.common.EmbeddingStoreFactoryConfig;
import io.kaoto.forage.vectordb.store.common.EmbeddingStoreModuleDescriptor;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * Registers {@link EmbeddingStore} beans: {@code forage.vectordb.kind} creates the default
 * {@code embeddingStore} bean, {@code forage.<name>.vectordb.kind} creates a bean named {@code <name>}.
 */
@ForageFactory(
        value = "Embedding Store",
        components = {"camel-langchain4j-embeddingstore"},
        description = "Creates LangChain4j embedding store beans",
        type = FactoryType.EMBEDDING_STORE,
        defaultBeanName = "embeddingStore",
        autowired = true,
        configClass = EmbeddingStoreFactoryConfig.class)
public class EmbeddingStoreBeanFactory extends AbstractInstanceBeanFactory<EmbeddingStore<TextSegment>> {

    public EmbeddingStoreBeanFactory() {
        super(new EmbeddingStoreModuleDescriptor());
    }

    @Override
    protected EmbeddingStore<TextSegment> createBean(String prefix) {
        return EmbeddingStoreCreator.create(prefix, getCamelContext().getApplicationContextClassLoader());
    }
}
