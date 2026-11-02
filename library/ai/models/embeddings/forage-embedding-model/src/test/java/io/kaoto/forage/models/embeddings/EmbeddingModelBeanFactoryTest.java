package io.kaoto.forage.models.embeddings;

import java.util.List;
import java.util.ServiceLoader;
import org.apache.camel.impl.DefaultCamelContext;
import io.kaoto.forage.core.common.BeanFactory;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.models.embeddings.ollama.OllamaEmbeddingProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingModelBeanFactoryTest {
    private static final List<String> PROPERTIES = List.of(
            "forage.embeddings.kind",
            "forage.ollama.embedding.model.name",
            "forage.alpha.embeddings.kind",
            "forage.beta.embeddings.kind",
            "forage.beta.ollama.embedding.model.name",
            "forage.milvus.retrieve.embeddings.on.search");

    private final DefaultCamelContext context = new DefaultCamelContext();
    private final EmbeddingModelBeanFactory factory = new EmbeddingModelBeanFactory();

    EmbeddingModelBeanFactoryTest() {
        factory.setCamelContext(context);
    }

    @AfterEach
    void reset() {
        context.stop();
        PROPERTIES.forEach(System::clearProperty);
        ConfigStore.getInstance().reload();
    }

    @Test
    void discoversFactory() {
        assertThat(ServiceLoader.load(BeanFactory.class).stream().map(ServiceLoader.Provider::type))
                .contains(EmbeddingModelBeanFactory.class);
    }

    @Test
    void staysInactiveWithoutKind() {
        // A key of another module that contains ".embeddings." must not create an instance
        System.setProperty("forage.milvus.retrieve.embeddings.on.search", "false");
        System.setProperty("forage.ollama.embedding.model.name", "all-minilm");

        factory.configure();

        assertThat(context.getRegistry().findByType(EmbeddingModel.class)).isEmpty();
    }

    @Test
    void createsDefaultBean() {
        System.setProperty("forage.embeddings.kind", "ollama");
        System.setProperty("forage.ollama.embedding.model.name", "all-minilm");

        factory.configure();

        assertThat(context.getRegistry().findByType(EmbeddingModel.class)).hasSize(1);
        assertThat(context.getRegistry().lookupByName("embeddingModel"))
                .isInstanceOfSatisfying(OllamaEmbeddingModel.class, model -> assertThat(model.modelName())
                        .isEqualTo("all-minilm"));
    }

    @Test
    void createsNamedBeansOnly() {
        System.setProperty("forage.embeddings.kind", "ollama");
        System.setProperty("forage.alpha.embeddings.kind", "ollama");
        System.setProperty("forage.beta.embeddings.kind", "ollama");
        System.setProperty("forage.beta.ollama.embedding.model.name", "bge-m3");

        factory.configure();

        assertThat(context.getRegistry().findByTypeWithName(EmbeddingModel.class))
                .containsOnlyKeys("alpha", "beta");
        assertThat(context.getRegistry()
                        .lookupByNameAndType("alpha", OllamaEmbeddingModel.class)
                        .modelName())
                .isEqualTo("nomic-embed-text");
        assertThat(context.getRegistry()
                        .lookupByNameAndType("beta", OllamaEmbeddingModel.class)
                        .modelName())
                .isEqualTo("bge-m3");
    }

    @Test
    void failsForUnknownKind() {
        System.setProperty("forage.embeddings.kind", "unknown");

        assertThatThrownBy(factory::configure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'unknown'")
                .hasMessageContaining("[ollama]");
    }

    @Test
    void cleanupUnbindsOnlyOwnBeans() {
        EmbeddingModel userModel = new EmbeddingModel() {};
        context.getRegistry().bind("alpha", userModel);
        System.setProperty("forage.alpha.embeddings.kind", "ollama");
        System.setProperty("forage.beta.embeddings.kind", "ollama");

        factory.configure();
        EmbeddingModel beta = context.getRegistry().lookupByNameAndType("beta", EmbeddingModel.class);
        factory.configure();

        assertThat(context.getRegistry().lookupByName("alpha")).isSameAs(userModel);
        assertThat(context.getRegistry().lookupByName("beta")).isSameAs(beta);

        factory.cleanup();

        assertThat(context.getRegistry().lookupByName("alpha")).isSameAs(userModel);
        assertThat(context.getRegistry().lookupByName("beta")).isNull();

        factory.configure();

        assertThat(context.getRegistry().lookupByName("beta"))
                .isInstanceOf(OllamaEmbeddingModel.class)
                .isNotSameAs(beta);
    }

    @Test
    void descriptorResolvesProviderByKind() {
        System.setProperty("forage.embeddings.kind", "ollama");

        assertThat(new EmbeddingModelModuleDescriptor().resolveProviderClassName(new EmbeddingModelFactoryConfig()))
                .isEqualTo(OllamaEmbeddingProvider.class.getName());
    }
}
