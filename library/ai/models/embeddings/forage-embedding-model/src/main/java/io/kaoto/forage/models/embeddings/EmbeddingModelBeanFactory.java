package io.kaoto.forage.models.embeddings;

import io.kaoto.forage.core.annotations.FactoryType;
import io.kaoto.forage.core.annotations.ForageFactory;
import io.kaoto.forage.core.common.AbstractInstanceBeanFactory;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * Registers {@link EmbeddingModel} beans: {@code forage.embeddings.kind} creates the default
 * {@code embeddingModel} bean, {@code forage.<name>.embeddings.kind} creates a bean named {@code <name>}.
 */
@ForageFactory(
        value = "Embedding Model",
        components = {"camel-langchain4j-embeddings"},
        description = "Creates LangChain4j embedding model beans",
        type = FactoryType.EMBEDDING_MODEL,
        defaultBeanName = "embeddingModel",
        autowired = true,
        configClass = EmbeddingModelFactoryConfig.class)
public class EmbeddingModelBeanFactory extends AbstractInstanceBeanFactory<EmbeddingModel> {

    public EmbeddingModelBeanFactory() {
        super(new EmbeddingModelModuleDescriptor());
    }

    @Override
    protected EmbeddingModel createBean(String prefix) {
        return EmbeddingModelCreator.create(prefix, getCamelContext().getApplicationContextClassLoader());
    }
}
