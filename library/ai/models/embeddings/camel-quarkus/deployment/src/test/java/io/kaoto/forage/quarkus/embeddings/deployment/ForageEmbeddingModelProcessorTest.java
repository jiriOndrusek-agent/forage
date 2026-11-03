package io.kaoto.forage.quarkus.embeddings.deployment;

import java.util.ArrayList;
import java.util.List;
import org.apache.camel.quarkus.core.deployment.spi.CamelRuntimeBeanBuildItem;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.quarkus.embeddings.ForageEmbeddingModelConfigSourceFactory;
import io.kaoto.forage.quarkus.embeddings.ForageEmbeddingModelRecorder;
import io.quarkus.deployment.builditem.RemovedResourceBuildItem;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.runtime.RuntimeValue;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigValue;
import dev.langchain4j.model.embedding.EmbeddingModel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
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
    void removesThePlainCamelFactory() {
        RemovedResourceBuildItem removed = new ForageEmbeddingModelProcessor().removePlainCamelFactory();
        assertThat(removed.getArtifact())
                .isEqualTo(ArtifactKey.of("io.kaoto.forage", "forage-embedding-model", "", "jar"));
        assertThat(removed.getResources()).containsExactly("META-INF/services/io.kaoto.forage.core.common.BeanFactory");
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
