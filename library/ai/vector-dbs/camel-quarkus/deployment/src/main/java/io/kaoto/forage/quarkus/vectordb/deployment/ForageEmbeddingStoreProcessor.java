package io.kaoto.forage.quarkus.vectordb.deployment;

import org.apache.camel.quarkus.core.deployment.spi.CamelRuntimeBeanBuildItem;
import io.kaoto.forage.core.annotations.FactoryType;
import io.kaoto.forage.core.annotations.FactoryVariant;
import io.kaoto.forage.core.annotations.ForageFactory;
import io.kaoto.forage.core.common.AbstractInstanceBeanFactory;
import io.kaoto.forage.core.common.ForageQuarkusConfigSourceAdapter;
import io.kaoto.forage.quarkus.vectordb.ForageEmbeddingStoreRecorder;
import io.kaoto.forage.vectordb.store.common.EmbeddingStoreFactoryConfig;
import io.kaoto.forage.vectordb.store.common.EmbeddingStoreModuleDescriptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import dev.langchain4j.store.embedding.EmbeddingStore;

@ForageFactory(
        value = "Embedding Store (Quarkus)",
        components = {"camel-langchain4j-embeddingstore"},
        description = "Creates LangChain4j embedding store beans for Quarkus",
        type = FactoryType.EMBEDDING_STORE,
        autowired = true,
        configClass = EmbeddingStoreFactoryConfig.class,
        variant = FactoryVariant.QUARKUS)
public class ForageEmbeddingStoreProcessor {
    private static final EmbeddingStoreModuleDescriptor DESCRIPTOR = new EmbeddingStoreModuleDescriptor();

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("forage-embedding-store");
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void registerStores(ForageEmbeddingStoreRecorder recorder, BuildProducer<CamelRuntimeBeanBuildItem> beans) {
        AbstractInstanceBeanFactory.instances(
                        DESCRIPTOR, ForageQuarkusConfigSourceAdapter.getDiscoveredPrefixes(DESCRIPTOR.modulePrefix()))
                .forEach((name, prefix) -> beans.produce(new CamelRuntimeBeanBuildItem(
                        name, EmbeddingStore.class.getName(), recorder.createStore(prefix))));
    }
}
