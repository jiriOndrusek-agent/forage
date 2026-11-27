package io.kaoto.forage.springboot.vectordb;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import io.kaoto.forage.core.annotations.FactoryType;
import io.kaoto.forage.core.annotations.FactoryVariant;
import io.kaoto.forage.core.annotations.ForageFactory;
import io.kaoto.forage.springboot.common.ForageInstanceBeanRegistrar;
import io.kaoto.forage.vectordb.store.common.EmbeddingStoreCreator;
import io.kaoto.forage.vectordb.store.common.EmbeddingStoreFactoryConfig;
import io.kaoto.forage.vectordb.store.common.EmbeddingStoreModuleDescriptor;

/**
 * Registers the {@code embeddingStore} bean, or one bean per {@code forage.<name>.vectordb.kind}.
 */
@ForageFactory(
        value = "Embedding Store (Spring Boot)",
        components = {"camel-langchain4j-embeddingstore"},
        description = "Creates LangChain4j embedding store beans for Spring Boot",
        type = FactoryType.EMBEDDING_STORE,
        autowired = true,
        configClass = EmbeddingStoreFactoryConfig.class,
        variant = FactoryVariant.SPRING_BOOT)
@AutoConfiguration
public class ForageEmbeddingStoreAutoConfiguration {

    @Bean
    static ForageInstanceBeanRegistrar forageEmbeddingStoreRegistrar() {
        return new ForageInstanceBeanRegistrar(
                new EmbeddingStoreModuleDescriptor(),
                prefix -> EmbeddingStoreCreator.create(
                        prefix, Thread.currentThread().getContextClassLoader()));
    }
}
