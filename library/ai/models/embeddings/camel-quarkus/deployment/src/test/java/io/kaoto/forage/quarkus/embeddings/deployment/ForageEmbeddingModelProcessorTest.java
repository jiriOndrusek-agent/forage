package io.kaoto.forage.quarkus.embeddings.deployment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import org.apache.camel.quarkus.core.deployment.spi.CamelRuntimeBeanBuildItem;
import io.kaoto.forage.core.common.BeanFactory;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.quarkus.embeddings.ForageEmbeddingModelConfigSourceFactory;
import io.kaoto.forage.quarkus.embeddings.ForageEmbeddingModelRecorder;
import io.quarkus.runtime.RuntimeValue;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigValue;
import dev.langchain4j.model.embedding.EmbeddingModel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ForageEmbeddingModelProcessorTest {
    @AfterEach
    void reset() {
        System.clearProperty("forage.embeddings.kind");
        System.clearProperty("forage.docs.embeddings.kind");
        ConfigStore.getInstance().reload();
        // The adapter keeps the discovered instance names in a static map
        ConfigSourceContext empty = mock(ConfigSourceContext.class);
        when(empty.iterateNames()).thenAnswer(ignored -> Collections.emptyIterator());
        new ForageEmbeddingModelConfigSourceFactory().getConfigSources(empty);
    }

    @Test
    void registersDefaultModel() {
        System.setProperty("forage.embeddings.kind", "ollama");
        verifyRegistration(null, "embeddingModel");
    }

    @Test
    void registersNamedModel() {
        System.setProperty("forage.docs.embeddings.kind", "ollama");
        verifyRegistration("docs", "docs");
    }

    @Test
    void registersDefaultModelFromProfileScopedConfiguration() {
        loadConfigSources("%dev.forage.embeddings.kind", "ollama");
        verifyRegistration(null, "embeddingModel");
    }

    @Test
    void ignoresKeysOfOtherModules() {
        loadConfigSources("forage.milvus.retrieve.embeddings.on.search", "false");
        assertNoRegistration();
    }

    @Test
    void failsForNameOfAnotherBeanType() {
        RuntimeValue<Object> value = new RuntimeValue<>(new Object());
        List<CamelRuntimeBeanBuildItem> beans = List.of(
                new CamelRuntimeBeanBuildItem("docs", EmbeddingModel.class.getName(), value),
                new CamelRuntimeBeanBuildItem("notes", "dev.langchain4j.store.embedding.EmbeddingStore", value),
                new CamelRuntimeBeanBuildItem("docs", "dev.langchain4j.store.embedding.EmbeddingStore", value));

        assertThatThrownBy(() -> new ForageEmbeddingModelProcessor().checkBeanNames(beans))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(
                        "Cannot register EmbeddingModel bean 'docs': the name is taken by a bean of type ");
        assertThat(new ForageEmbeddingModelProcessor().checkBeanNames(beans.subList(0, 2)))
                .isNotNull();
    }

    @Test
    void doesNotBringThePlainCamelFactory() {
        // It would bind the beans a second time when Camel Quarkus builds the CamelContext
        assertThat(ServiceLoader.load(BeanFactory.class).stream()
                        .map(provider -> provider.type().getName()))
                .doesNotContain("io.kaoto.forage.models.embeddings.EmbeddingModelBeanFactory");
    }

    @Test
    void staysInactiveWithoutConfiguration() {
        assertNoRegistration();
    }

    private static void loadConfigSources(String key, String value) {
        ConfigSourceContext sourceContext = mock(ConfigSourceContext.class);
        when(sourceContext.iterateNames()).thenAnswer(ignored -> List.of(key).iterator());
        when(sourceContext.getValue(key))
                .thenReturn(ConfigValue.builder().withName(key).withValue(value).build());
        new ForageEmbeddingModelConfigSourceFactory().getConfigSources(sourceContext);
    }

    private static void assertNoRegistration() {
        List<CamelRuntimeBeanBuildItem> beans = new ArrayList<>();
        ForageEmbeddingModelRecorder recorder = mock(ForageEmbeddingModelRecorder.class);
        new ForageEmbeddingModelProcessor().registerModels(recorder, beans::add);
        assertThat(beans).isEmpty();
        verifyNoInteractions(recorder);
    }

    private static void verifyRegistration(String prefix, String name) {
        List<CamelRuntimeBeanBuildItem> beans = new ArrayList<>();
        ForageEmbeddingModelRecorder recorder = mock(ForageEmbeddingModelRecorder.class);
        when(recorder.createModel(prefix)).thenReturn(new RuntimeValue<>(mock(EmbeddingModel.class)));
        new ForageEmbeddingModelProcessor().registerModels(recorder, beans::add);
        assertThat(beans).singleElement().satisfies(bean -> {
            assertThat(bean.getName()).isEqualTo(name);
            assertThat(bean.getType()).isEqualTo(EmbeddingModel.class.getName());
        });
        verify(recorder).createModel(prefix);
    }
}
