package io.kaoto.forage.springboot.vectordb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.springboot.common.ForageEnvironmentPostProcessor;
import io.kaoto.forage.springboot.common.SpringConfigResolver;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ForageEmbeddingStoreAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> new ForageEnvironmentPostProcessor()
                    .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
            .withConfiguration(AutoConfigurations.of(ForageEmbeddingStoreAutoConfiguration.class));

    @AfterEach
    void reset() {
        ConfigStore.getInstance().unregisterResolver(SpringConfigResolver.class);
        ConfigStore.getInstance().reload();
    }

    @Test
    void staysInactiveWithoutKind() {
        runner.withPropertyValues("forage.agent.embedding.store.kind=in-memory-store")
                .run(context -> assertThat(context).doesNotHaveBean(EmbeddingStore.class));
    }

    @Test
    void createsDefaultBean() {
        runner.withPropertyValues("forage.vectordb.kind=in-memory-store").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("embeddingStore")).isInstanceOf(InMemoryEmbeddingStore.class);
        });
    }

    @Test
    void createsNamedBeansOnly() {
        runner.withPropertyValues(
                        "forage.vectordb.kind=in-memory-store",
                        "forage.alpha.vectordb.kind=in-memory-store",
                        "forage.beta.vectordb.kind=in-memory-store")
                .run(context ->
                        assertThat(context.getBeansOfType(EmbeddingStore.class)).containsOnlyKeys("alpha", "beta"));
    }

    @Test
    void preservesUserBean() {
        EmbeddingStore<?> external = new InMemoryEmbeddingStore<>();
        runner.withBean("embeddingStore", EmbeddingStore.class, () -> external)
                .withPropertyValues("forage.vectordb.kind=in-memory-store")
                .run(context -> assertThat(context.getBean("embeddingStore")).isSameAs(external));
    }

    @Test
    void failsForUnknownKind() {
        runner.withPropertyValues("forage.vectordb.kind=unknown")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("'unknown'"));
    }
}
