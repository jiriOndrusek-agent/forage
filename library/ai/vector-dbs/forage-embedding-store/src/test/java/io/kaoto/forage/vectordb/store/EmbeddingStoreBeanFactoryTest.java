package io.kaoto.forage.vectordb.store;

import java.util.List;
import java.util.ServiceLoader;
import org.apache.camel.impl.DefaultCamelContext;
import io.kaoto.forage.core.common.BeanFactory;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.core.util.config.MissingConfigException;
import io.kaoto.forage.vectordb.store.common.EmbeddingStoreFactoryConfig;
import io.kaoto.forage.vectordb.store.common.EmbeddingStoreModuleDescriptor;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingStoreBeanFactoryTest {
    private static final List<String> PROPERTIES = List.of(
            "forage.vectordb.kind",
            "forage.alpha.vectordb.kind",
            "forage.beta.vectordb.kind",
            "forage.agent.embedding.store.kind",
            "forage.in.memory.store.file.source");

    private final DefaultCamelContext context = new DefaultCamelContext();
    private final EmbeddingStoreBeanFactory factory = new EmbeddingStoreBeanFactory();

    EmbeddingStoreBeanFactoryTest() {
        factory.setCamelContext(context);
    }

    @AfterEach
    void reset() {
        context.stop();
        PROPERTIES.forEach(System::clearProperty);
        TestEmbeddingStoreProvider.IDS.clear();
        ConfigStore.getInstance().reload();
    }

    @Test
    void discoversFactory() {
        assertThat(ServiceLoader.load(BeanFactory.class).stream().map(ServiceLoader.Provider::type))
                .contains(EmbeddingStoreBeanFactory.class);
    }

    @Test
    void staysInactiveWithoutKind() {
        System.setProperty("forage.agent.embedding.store.kind", TestEmbeddingStoreProvider.KIND);

        factory.configure();

        assertThat(context.getRegistry().findByType(EmbeddingStore.class)).isEmpty();
    }

    @Test
    void createsEmptyInMemoryStore() {
        System.setProperty("forage.vectordb.kind", "in-memory-store");
        // The seed file of the in-memory provider is used by the Agent only
        System.setProperty("forage.in.memory.store.file.source", "missing.txt");

        factory.configure();

        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store =
                context.getRegistry().lookupByNameAndType("embeddingStore", EmbeddingStore.class);
        assertThat(store).isInstanceOf(InMemoryEmbeddingStore.class);
        Embedding embedding = Embedding.from(new float[] {1, 0});
        store.add(embedding, TextSegment.from("Forage"));
        assertThat(store.search(EmbeddingSearchRequest.builder()
                                .queryEmbedding(embedding)
                                .build())
                        .matches())
                .singleElement()
                .satisfies(match -> assertThat(match.embedded().text()).isEqualTo("Forage"));
    }

    @Test
    void createsNamedBeansOnlyWithTheirPrefix() {
        System.setProperty("forage.vectordb.kind", "in-memory-store");
        System.setProperty("forage.alpha.vectordb.kind", TestEmbeddingStoreProvider.KIND);
        System.setProperty("forage.beta.vectordb.kind", "in-memory-store");

        factory.configure();

        assertThat(context.getRegistry().findByTypeWithName(EmbeddingStore.class))
                .containsOnlyKeys("alpha", "beta");
        assertThat(TestEmbeddingStoreProvider.IDS).containsExactly("alpha");
    }

    @Test
    void failsForUnknownKind() {
        System.setProperty("forage.vectordb.kind", "unknown");

        assertThatThrownBy(factory::configure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'unknown'")
                .hasMessageContaining("[" + TestEmbeddingStoreProvider.KIND + "]");
    }

    @Test
    void requiresKind() {
        System.setProperty("forage.alpha.vectordb.kind", " ");

        assertThatThrownBy(() -> new EmbeddingStoreFactoryConfig("alpha").kind())
                .isInstanceOf(MissingConfigException.class)
                .hasMessageContaining("forage.alpha.vectordb.kind");
    }

    @Test
    void descriptorResolvesProviderByKind() {
        System.setProperty("forage.vectordb.kind", TestEmbeddingStoreProvider.KIND);

        assertThat(new EmbeddingStoreModuleDescriptor().resolveProviderClassName(new EmbeddingStoreFactoryConfig()))
                .isEqualTo(TestEmbeddingStoreProvider.class.getName());
    }
}
