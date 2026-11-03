package io.kaoto.forage.quarkus.vectordb.deployment;

import java.util.Set;
import org.apache.camel.quarkus.core.deployment.spi.CamelRuntimeBeanBuildItem;
import io.kaoto.forage.core.annotations.FactoryType;
import io.kaoto.forage.core.annotations.FactoryVariant;
import io.kaoto.forage.core.annotations.ForageFactory;
import io.kaoto.forage.core.common.AbstractInstanceBeanFactory;
import io.kaoto.forage.core.common.BeanFactory;
import io.kaoto.forage.core.common.ForageQuarkusConfigSourceAdapter;
import io.kaoto.forage.quarkus.vectordb.ForageEmbeddingStoreRecorder;
import io.kaoto.forage.vectordb.store.EmbeddingStoreFactoryConfig;
import io.kaoto.forage.vectordb.store.EmbeddingStoreModuleDescriptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RemovedResourceBuildItem;
import io.quarkus.maven.dependency.ArtifactKey;
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

    // Camel Quarkus builds the CamelContext at static init, where the plain Camel factory of the same
    // jar would bind the beans too. The extension creates them at runtime init instead.
    @BuildStep
    RemovedResourceBuildItem removePlainCamelFactory() {
        return new RemovedResourceBuildItem(
                ArtifactKey.of("io.kaoto.forage", "forage-embedding-store", "", "jar"),
                Set.of("META-INF/services/" + BeanFactory.class.getName()));
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
