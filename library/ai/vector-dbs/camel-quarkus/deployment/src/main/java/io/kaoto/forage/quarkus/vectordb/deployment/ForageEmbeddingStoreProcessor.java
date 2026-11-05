package io.kaoto.forage.quarkus.vectordb.deployment;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.camel.quarkus.core.deployment.spi.CamelRuntimeBeanBuildItem;
import org.apache.camel.quarkus.core.deployment.spi.CamelRuntimeTaskBuildItem;
import io.kaoto.forage.core.ai.EmbeddingStoreProvider;
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
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
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

    // The creator finds the providers with ServiceLoader, also in native images
    @BuildStep
    ServiceProviderBuildItem providers() {
        return ServiceProviderBuildItem.allProvidersFromClassPath(EmbeddingStoreProvider.class.getName());
    }

    // #bean:<name> finds one bean per name
    @BuildStep
    CamelRuntimeTaskBuildItem checkBeanNames(List<CamelRuntimeBeanBuildItem> beans) {
        String type = EmbeddingStore.class.getName();
        Set<String> names = beans.stream()
                .filter(bean -> type.equals(bean.getType()))
                .map(CamelRuntimeBeanBuildItem::getName)
                .collect(Collectors.toSet());
        beans.stream()
                .filter(bean -> names.contains(bean.getName()) && !type.equals(bean.getType()))
                .findFirst()
                .ifPresent(bean -> {
                    throw new IllegalStateException(
                            AbstractInstanceBeanFactory.nameInUse(bean.getName(), type, bean.getType()));
                });
        return new CamelRuntimeTaskBuildItem("forage-embedding-store-bean-names");
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
