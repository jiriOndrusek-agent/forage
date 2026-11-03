package io.kaoto.forage.springboot.embeddings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.springboot.common.ForageEnvironmentPostProcessor;
import io.kaoto.forage.springboot.common.SpringConfigResolver;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ForageEmbeddingModelAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> new ForageEnvironmentPostProcessor()
                    .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
            .withConfiguration(AutoConfigurations.of(ForageEmbeddingModelAutoConfiguration.class));

    @AfterEach
    void reset() {
        ConfigStore.getInstance().unregisterResolver(SpringConfigResolver.class);
        ConfigStore.getInstance().reload();
    }

    @Test
    void staysInactiveWithoutKind() {
        runner.withPropertyValues(
                        "forage.milvus.retrieve.embeddings.on.search=false",
                        "forage.ollama.embedding.model.name=all-minilm")
                .run(context -> assertThat(context).doesNotHaveBean(EmbeddingModel.class));
    }

    @Test
    void createsDefaultBean() {
        runner.withPropertyValues("forage.embeddings.kind=ollama", "forage.ollama.embedding.model.name=all-minilm")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("embeddingModel"))
                            .isInstanceOfSatisfying(OllamaEmbeddingModel.class, model -> assertThat(model.modelName())
                                    .isEqualTo("all-minilm"));
                });
    }

    @Test
    void createsNamedBeansOnly() {
        runner.withPropertyValues(
                        "forage.embeddings.kind=ollama",
                        "forage.alpha.embeddings.kind=ollama",
                        "forage.beta.embeddings.kind=ollama",
                        "forage.beta.ollama.embedding.model.name=bge-m3")
                .run(context -> {
                    assertThat(context.getBeansOfType(EmbeddingModel.class)).containsOnlyKeys("alpha", "beta");
                    assertThat(context.getBean("beta", OllamaEmbeddingModel.class)
                                    .modelName())
                            .isEqualTo("bge-m3");
                });
    }

    @Test
    void preservesUserBean() {
        EmbeddingModel external = new EmbeddingModel() {};
        runner.withBean("embeddingModel", EmbeddingModel.class, () -> external)
                .withPropertyValues("forage.embeddings.kind=ollama")
                .run(context -> assertThat(context.getBean("embeddingModel")).isSameAs(external));
    }

    @Test
    void failsForUnknownKind() {
        runner.withPropertyValues("forage.embeddings.kind=unknown")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("'unknown'"));
    }
}
