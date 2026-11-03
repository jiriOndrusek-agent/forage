package io.kaoto.forage.springboot.embeddings;

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
import io.kaoto.forage.models.embeddings.common.EmbeddingModelCreator;
import io.kaoto.forage.models.embeddings.common.EmbeddingModelFactoryConfig;
import io.kaoto.forage.models.embeddings.common.EmbeddingModelModuleDescriptor;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * Registers the {@code embeddingModel} bean, or one bean per {@code forage.<name>.embeddings.kind}.
 * Bean definitions that already exist are kept.
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
    private static final Logger LOG = LoggerFactory.getLogger(ForageEmbeddingModelAutoConfiguration.class);

    @Bean
    static BeanDefinitionRegistryPostProcessor forageEmbeddingModelRegistrar() {
        return registry -> AbstractInstanceBeanFactory.instances(new EmbeddingModelModuleDescriptor(), Set.of())
                .forEach((name, prefix) -> {
                    if (!registry.containsBeanDefinition(name)) {
                        GenericBeanDefinition definition = new GenericBeanDefinition();
                        definition.setBeanClass(EmbeddingModel.class);
                        definition.setInstanceSupplier(() -> EmbeddingModelCreator.create(
                                prefix, Thread.currentThread().getContextClassLoader()));
                        registry.registerBeanDefinition(name, definition);
                        LOG.info("Registered EmbeddingModel bean definition '{}'", name);
                    }
                });
    }
}
