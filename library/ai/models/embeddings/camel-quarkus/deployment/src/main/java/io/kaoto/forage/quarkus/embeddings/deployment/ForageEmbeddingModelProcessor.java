package io.kaoto.forage.quarkus.embeddings.deployment;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.camel.quarkus.core.deployment.spi.CamelRuntimeBeanBuildItem;
import org.apache.camel.quarkus.core.deployment.spi.CamelRuntimeTaskBuildItem;
import io.kaoto.forage.core.ai.EmbeddingModelProvider;
import io.kaoto.forage.core.annotations.FactoryType;
import io.kaoto.forage.core.annotations.FactoryVariant;
import io.kaoto.forage.core.annotations.ForageFactory;
import io.kaoto.forage.core.common.AbstractInstanceBeanFactory;
import io.kaoto.forage.core.common.ForageQuarkusConfigSourceAdapter;
import io.kaoto.forage.models.embeddings.common.EmbeddingModelFactoryConfig;
import io.kaoto.forage.models.embeddings.common.EmbeddingModelModuleDescriptor;
import io.kaoto.forage.quarkus.embeddings.ForageEmbeddingModelRecorder;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import dev.langchain4j.model.embedding.EmbeddingModel;

@ForageFactory(
        value = "Embedding Model (Quarkus)",
        components = {"camel-langchain4j-embeddings"},
        description = "Creates LangChain4j embedding model beans for Quarkus",
        type = FactoryType.EMBEDDING_MODEL,
        autowired = true,
        configClass = EmbeddingModelFactoryConfig.class,
        variant = FactoryVariant.QUARKUS)
public class ForageEmbeddingModelProcessor {
    private static final EmbeddingModelModuleDescriptor DESCRIPTOR = new EmbeddingModelModuleDescriptor();

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("forage-embedding-model");
    }

    // The creator finds the providers with ServiceLoader, also in native images
    @BuildStep
    ServiceProviderBuildItem providers() {
        return ServiceProviderBuildItem.allProvidersFromClassPath(EmbeddingModelProvider.class.getName());
    }

    // #bean:<name> finds one bean per name
    @BuildStep
    CamelRuntimeTaskBuildItem checkBeanNames(List<CamelRuntimeBeanBuildItem> beans) {
        String type = EmbeddingModel.class.getName();
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
        return new CamelRuntimeTaskBuildItem("forage-embedding-model-bean-names");
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void registerModels(ForageEmbeddingModelRecorder recorder, BuildProducer<CamelRuntimeBeanBuildItem> beans) {
        AbstractInstanceBeanFactory.instances(
                        DESCRIPTOR, ForageQuarkusConfigSourceAdapter.getDiscoveredPrefixes(DESCRIPTOR.modulePrefix()))
                .forEach((name, prefix) -> beans.produce(new CamelRuntimeBeanBuildItem(
                        name, EmbeddingModel.class.getName(), recorder.createModel(prefix))));
    }
}
