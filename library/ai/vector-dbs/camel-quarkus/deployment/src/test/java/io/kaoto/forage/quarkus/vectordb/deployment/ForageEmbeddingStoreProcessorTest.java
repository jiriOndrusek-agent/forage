package io.kaoto.forage.quarkus.vectordb.deployment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.camel.quarkus.core.deployment.spi.CamelRuntimeBeanBuildItem;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.quarkus.vectordb.ForageEmbeddingStoreConfigSourceFactory;
import io.kaoto.forage.quarkus.vectordb.ForageEmbeddingStoreRecorder;
import io.quarkus.deployment.builditem.RemovedResourceBuildItem;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.runtime.RuntimeValue;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigValue;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ForageEmbeddingStoreProcessorTest {
    @AfterEach
    void reset() {
        System.clearProperty("forage.vectordb.kind");
        System.clearProperty("forage.docs.vectordb.kind");
        ConfigStore.getInstance().reload();
        // The adapter keeps the discovered instance names in a static map
        ConfigSourceContext empty = mock(ConfigSourceContext.class);
        when(empty.iterateNames()).thenAnswer(ignored -> Collections.emptyIterator());
        new ForageEmbeddingStoreConfigSourceFactory().getConfigSources(empty);
    }

    @Test
    void registersDefaultStore() {
        System.setProperty("forage.vectordb.kind", "in-memory-store");
        verifyRegistration(null, "embeddingStore");
    }

    @Test
    void registersNamedStore() {
        System.setProperty("forage.docs.vectordb.kind", "in-memory-store");
        verifyRegistration("docs", "docs");
    }

    @Test
    void registersNamedStoreFromProfileScopedConfiguration() {
        String key = "%dev.forage.docs.vectordb.kind";
        ConfigSourceContext sourceContext = mock(ConfigSourceContext.class);
        when(sourceContext.iterateNames()).thenAnswer(ignored -> List.of(key).iterator());
        when(sourceContext.getValue(key))
                .thenReturn(ConfigValue.builder()
                        .withName(key)
                        .withValue("in-memory-store")
                        .build());
        new ForageEmbeddingStoreConfigSourceFactory().getConfigSources(sourceContext);
        verifyRegistration("docs", "docs");
    }

    @Test
    void removesThePlainCamelFactory() {
        RemovedResourceBuildItem removed = new ForageEmbeddingStoreProcessor().removePlainCamelFactory();
        assertThat(removed.getArtifact())
                .isEqualTo(ArtifactKey.of("io.kaoto.forage", "forage-embedding-store", "", "jar"));
        assertThat(removed.getResources()).containsExactly("META-INF/services/io.kaoto.forage.core.common.BeanFactory");
    }

    @Test
    void staysInactiveWithoutConfiguration() {
        List<CamelRuntimeBeanBuildItem> beans = new ArrayList<>();
        ForageEmbeddingStoreRecorder recorder = mock(ForageEmbeddingStoreRecorder.class);
        new ForageEmbeddingStoreProcessor().registerStores(recorder, beans::add);
        assertThat(beans).isEmpty();
        verifyNoInteractions(recorder);
    }

    private static void verifyRegistration(String prefix, String name) {
        List<CamelRuntimeBeanBuildItem> beans = new ArrayList<>();
        ForageEmbeddingStoreRecorder recorder = mock(ForageEmbeddingStoreRecorder.class);
        when(recorder.createStore(prefix)).thenReturn(new RuntimeValue<>(new InMemoryEmbeddingStore<>()));
        new ForageEmbeddingStoreProcessor().registerStores(recorder, beans::add);
        assertThat(beans).singleElement().satisfies(bean -> {
            assertThat(bean.getName()).isEqualTo(name);
            assertThat(bean.getType()).isEqualTo(EmbeddingStore.class.getName());
        });
        verify(recorder).createStore(prefix);
    }
}
