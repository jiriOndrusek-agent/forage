package io.kaoto.forage.models.embeddings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import org.apache.camel.CamelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.kaoto.forage.core.ai.EmbeddingModelProvider;
import io.kaoto.forage.core.annotations.FactoryType;
import io.kaoto.forage.core.annotations.ForageFactory;
import io.kaoto.forage.core.common.BeanFactory;
import io.kaoto.forage.core.util.config.ConfigStore;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * Registers {@link EmbeddingModel} beans: {@code forage.embeddings.kind} creates the default
 * {@code embeddingModel} bean, {@code forage.<name>.embeddings.kind} creates a bean named {@code <name>}.
 */
@ForageFactory(
        value = "Embedding Model",
        components = {"camel-langchain4j-embeddings"},
        description = "Creates LangChain4j embedding model beans",
        type = FactoryType.EMBEDDING_MODEL,
        defaultBeanName = "embeddingModel",
        autowired = true,
        configClass = EmbeddingModelFactoryConfig.class)
public class EmbeddingModelBeanFactory implements BeanFactory {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingModelBeanFactory.class);

    // Instances are found by their kind key only: keys of other modules contain ".embeddings." too
    // (e.g. forage.milvus.retrieve.embeddings.on.search).
    private static final String NAMED_KIND_REGEXP = "forage\\.(.+)\\.embeddings\\.kind";
    private static final String DEFAULT_KIND_REGEXP = "forage\\.(embeddings)\\.kind";

    private final EmbeddingModelModuleDescriptor descriptor = new EmbeddingModelModuleDescriptor();
    private final Map<String, EmbeddingModel> models = new LinkedHashMap<>();
    private CamelContext camelContext;

    @Override
    public void configure() {
        EmbeddingModelFactoryConfig config = descriptor.createConfig(null);
        Set<String> names = ConfigStore.getInstance().readPrefixes(config, NAMED_KIND_REGEXP);
        if (!names.isEmpty()) {
            names.stream().sorted().forEach(name -> register(name, name));
        } else if (!ConfigStore.getInstance()
                .readPrefixes(config, DEFAULT_KIND_REGEXP)
                .isEmpty()) {
            register(descriptor.defaultBeanName(), null);
        }
    }

    private void register(String name, String prefix) {
        if (camelContext.getRegistry().lookupByNameAndType(name, EmbeddingModel.class) != null) {
            return;
        }
        String kind = descriptor.createConfig(prefix).kind();
        List<ServiceLoader.Provider<EmbeddingModelProvider>> providers = findProviders(EmbeddingModelProvider.class);
        EmbeddingModel model = EmbeddingModelModuleDescriptor.findProvider(providers, kind)
                .get()
                .create(prefix);
        camelContext.getRegistry().bind(name, model);
        models.put(name, model);
        LOG.info("Registered EmbeddingModel bean '{}' of kind '{}'", name, kind);
    }

    @Override
    public void cleanup() {
        models.forEach((name, model) -> {
            if (camelContext.getRegistry().lookupByNameAndType(name, EmbeddingModel.class) == model) {
                camelContext.getRegistry().unbind(name);
            }
        });
        models.clear();
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
        ConfigStore.getInstance().setClassLoader(camelContext.getApplicationContextClassLoader());
    }

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }
}
