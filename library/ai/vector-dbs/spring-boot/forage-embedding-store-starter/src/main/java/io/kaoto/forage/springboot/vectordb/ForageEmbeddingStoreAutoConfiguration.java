package io.kaoto.forage.springboot.vectordb;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import io.kaoto.forage.core.annotations.FactoryType;
import io.kaoto.forage.core.annotations.FactoryVariant;
import io.kaoto.forage.core.annotations.ForageFactory;
import io.kaoto.forage.core.common.AbstractInstanceBeanFactory;
import io.kaoto.forage.vectordb.store.EmbeddingStoreCreator;
import io.kaoto.forage.vectordb.store.EmbeddingStoreFactoryConfig;
import io.kaoto.forage.vectordb.store.EmbeddingStoreModuleDescriptor;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * Registers the {@code embeddingStore} bean, or one bean per {@code forage.<name>.vectordb.kind}.
 * Bean definitions that already exist are kept.
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
    private static final Logger LOG = LoggerFactory.getLogger(ForageEmbeddingStoreAutoConfiguration.class);

    @Bean
    static BeanDefinitionRegistryPostProcessor forageEmbeddingStoreRegistrar() {
        return registry -> AbstractInstanceBeanFactory.instances(new EmbeddingStoreModuleDescriptor(), Set.of())
                .forEach((name, prefix) -> {
                    if (!registry.containsBeanDefinition(name)) {
                        GenericBeanDefinition definition = new GenericBeanDefinition();
                        definition.setBeanClass(EmbeddingStore.class);
                        definition.setInstanceSupplier(() -> EmbeddingStoreCreator.create(
                                prefix, Thread.currentThread().getContextClassLoader()));
                        registry.registerBeanDefinition(name, definition);
                        LOG.info("Registered EmbeddingStore bean definition '{}'", name);
                    }
                });
    }
}
