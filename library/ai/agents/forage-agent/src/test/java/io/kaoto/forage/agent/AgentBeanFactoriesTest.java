package io.kaoto.forage.agent;

import java.util.List;
import java.util.ServiceLoader;
import org.apache.camel.impl.DefaultCamelContext;
import io.kaoto.forage.core.ForageContextServicePlugin;
import io.kaoto.forage.core.common.BeanFactory;
import io.kaoto.forage.core.util.config.ConfigStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An agent app runs the Agent factory only: the embedding model and store factories stay out of it.
 */
class AgentBeanFactoriesTest {
    // forage.<agent>.agent.embeddings.kind is the key of older docs; no embedding bean may come of it
    private static final List<String> PROPERTIES = List.of(
            "forage.agent.embeddings.kind",
            "forage.myAgent.agent.embeddings.kind",
            "forage.myAgent.agent.vectordb.kind");

    @AfterEach
    void reset() {
        PROPERTIES.forEach(System::clearProperty);
        ConfigStore.getInstance().reload();
    }

    @Test
    void runsNoEmbeddingFactory() {
        assertThat(ServiceLoader.load(BeanFactory.class).stream()
                        .map(provider -> provider.type().getName()))
                .doesNotContain(
                        "io.kaoto.forage.models.embeddings.EmbeddingModelBeanFactory",
                        "io.kaoto.forage.vectordb.store.EmbeddingStoreBeanFactory");
    }

    @Test
    void createsNoEmbeddingBeansForOldAgentKeys() throws Exception {
        System.setProperty("forage.agent.embeddings.kind", "ollama");
        System.setProperty("forage.myAgent.agent.embeddings.kind", "ollama");
        System.setProperty("forage.myAgent.agent.vectordb.kind", "in-memory-store");

        try (DefaultCamelContext context = new DefaultCamelContext()) {
            new ForageContextServicePlugin().load(context);

            assertThat(context.getRegistry().findByType(EmbeddingModel.class)).isEmpty();
            assertThat(context.getRegistry().findByType(EmbeddingStore.class)).isEmpty();
        }
    }
}
