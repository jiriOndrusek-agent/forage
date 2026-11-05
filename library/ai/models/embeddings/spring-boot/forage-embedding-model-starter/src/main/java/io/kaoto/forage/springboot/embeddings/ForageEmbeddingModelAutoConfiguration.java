package io.kaoto.forage.springboot.embeddings;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import io.kaoto.forage.core.annotations.FactoryType;
import io.kaoto.forage.core.annotations.FactoryVariant;
import io.kaoto.forage.core.annotations.ForageFactory;
import io.kaoto.forage.models.embeddings.common.EmbeddingModelCreator;
import io.kaoto.forage.models.embeddings.common.EmbeddingModelFactoryConfig;
import io.kaoto.forage.models.embeddings.common.EmbeddingModelModuleDescriptor;
import io.kaoto.forage.springboot.common.ForageInstanceBeanRegistrar;

/**
 * Registers the {@code embeddingModel} bean, or one bean per {@code forage.<name>.embeddings.kind}.
 */
@ForageFactory(
        value = "Embedding Model (Spring Boot)",
        components = {"camel-langchain4j-embeddings"},
        description = "Creates LangChain4j embedding model beans for Spring Boot",
        type = FactoryType.EMBEDDING_MODEL,
        autowired = true,
        configClass = EmbeddingModelFactoryConfig.class,
        variant = FactoryVariant.SPRING_BOOT)
@AutoConfiguration
public class ForageEmbeddingModelAutoConfiguration {

    @Bean
    static ForageInstanceBeanRegistrar forageEmbeddingModelRegistrar() {
        return new ForageInstanceBeanRegistrar(
                new EmbeddingModelModuleDescriptor(),
                prefix -> EmbeddingModelCreator.create(
                        prefix, Thread.currentThread().getContextClassLoader()));
    }
}
