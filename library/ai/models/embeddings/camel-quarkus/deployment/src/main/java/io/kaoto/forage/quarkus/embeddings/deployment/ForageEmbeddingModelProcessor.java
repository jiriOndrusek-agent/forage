package io.kaoto.forage.quarkus.embeddings.deployment;

import org.apache.camel.quarkus.core.deployment.spi.CamelRuntimeBeanBuildItem;
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

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void registerModels(ForageEmbeddingModelRecorder recorder, BuildProducer<CamelRuntimeBeanBuildItem> beans) {
        AbstractInstanceBeanFactory.instances(
                        DESCRIPTOR, ForageQuarkusConfigSourceAdapter.getDiscoveredPrefixes(DESCRIPTOR.modulePrefix()))
                .forEach((name, prefix) -> beans.produce(new CamelRuntimeBeanBuildItem(
                        name, EmbeddingModel.class.getName(), recorder.createModel(prefix))));
    }
}
