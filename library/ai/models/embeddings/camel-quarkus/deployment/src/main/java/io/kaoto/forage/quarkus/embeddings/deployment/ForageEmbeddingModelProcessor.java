package io.kaoto.forage.quarkus.embeddings.deployment;

import java.util.Set;
import org.apache.camel.quarkus.core.deployment.spi.CamelRuntimeBeanBuildItem;
import io.kaoto.forage.core.annotations.FactoryType;
import io.kaoto.forage.core.annotations.FactoryVariant;
import io.kaoto.forage.core.annotations.ForageFactory;
import io.kaoto.forage.core.common.AbstractInstanceBeanFactory;
import io.kaoto.forage.core.common.BeanFactory;
import io.kaoto.forage.core.common.ForageQuarkusConfigSourceAdapter;
import io.kaoto.forage.models.embeddings.EmbeddingModelFactoryConfig;
import io.kaoto.forage.models.embeddings.EmbeddingModelModuleDescriptor;
import io.kaoto.forage.quarkus.embeddings.ForageEmbeddingModelRecorder;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RemovedResourceBuildItem;
import io.quarkus.maven.dependency.ArtifactKey;
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

    // Camel Quarkus builds the CamelContext at static init, where the plain Camel factory of the same
    // jar would bind the beans too. The extension creates them at runtime init instead.
    @BuildStep
    RemovedResourceBuildItem removePlainCamelFactory() {
        return new RemovedResourceBuildItem(
                ArtifactKey.of("io.kaoto.forage", "forage-embedding-model", "", "jar"),
                Set.of("META-INF/services/" + BeanFactory.class.getName()));
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
