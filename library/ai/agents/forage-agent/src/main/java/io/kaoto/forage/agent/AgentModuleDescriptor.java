package io.kaoto.forage.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import io.kaoto.forage.core.ai.ModelProvider;
import io.kaoto.forage.core.common.ForageModuleDescriptor;

/**
 * Module descriptor for Forage Agent. Captures agent-specific knowledge:
 * prefix discovery, provider resolution, and Quarkus property translation.
 *
 * @since 1.1
 */
public class AgentModuleDescriptor implements ForageModuleDescriptor<AgentConfig, ModelProvider> {

    @Override
    public String modulePrefix() {
        return "agent";
    }

    @Override
    public AgentConfig createConfig(String prefix) {
        return prefix == null ? new AgentConfig() : new AgentConfig(prefix);
    }

    @Override
    public Class<ModelProvider> providerClass() {
        return ModelProvider.class;
    }

    @Override
    public String resolveProviderClassName(AgentConfig config) {
        String modelKind = config.modelKind();
        if (modelKind == null) {
            return null;
        }
        return AgentCreator.getProviderConfigPrefix(modelKind);
    }

    @Override
    public String defaultBeanName() {
        return "agent";
    }

    @Override
    public Class<?> primaryBeanClass() {
        return Agent.class;
    }

    @Override
    public boolean transactionEnabled(AgentConfig config) {
        return false;
    }

    /** A Forage key (after {@code forage.[<name>.]agent.}) written as a Quarkus key with the value of {@code value}. */
    public record QuarkusProperty(String forageKey, String quarkusKey, Function<AgentConfig, Object> value) {}

    /**
     * A model kind with a quarkus-langchain4j extension: a class of the extension (translate only when present), its
     * artifactId and the keys translated.
     */
    private record QuarkusModel(String extensionClass, String artifactId, List<QuarkusProperty> properties) {}

    private static final Map<String, Function<AgentConfig, Object>> VALUES = Map.ofEntries(
            Map.entry("api.key", AgentConfig::apiKey),
            Map.entry("base.url", AgentConfig::baseUrl),
            Map.entry("model.name", AgentConfig::modelName),
            Map.entry("temperature", AgentConfig::temperature),
            Map.entry("top.p", AgentConfig::topP),
            Map.entry("top.k", AgentConfig::topK),
            Map.entry("max.tokens", AgentConfig::maxTokens),
            Map.entry("endpoint", AgentConfig::endpoint),
            Map.entry("deployment.name", AgentConfig::deploymentName),
            Map.entry("log.requests", AgentConfig::logRequests),
            Map.entry("log.responses", AgentConfig::logResponses));

    // Each model kind maps to a separate Quarkus provider namespace (e.g., quarkus.langchain4j.ollama.*), so the
    // default (non-named) config is used. Quarkus named configs would only be needed for multiple agents with the same
    // model kind, which is not yet supported.
    private static final Map<String, QuarkusModel> QUARKUS_MODELS = Map.of(
            "ollama",
            model(
                    "ollama.runtime.config.LangChain4jOllamaConfig",
                    "ollama",
                    "ollama",
                    "base.url",
                    "base-url",
                    "model.name",
                    "chat-model.model-id",
                    "temperature",
                    "chat-model.temperature",
                    "top.p",
                    "chat-model.top-p",
                    "top.k",
                    "chat-model.top-k"),
            "openai",
            model(
                    "openai.runtime.config.LangChain4jOpenAiConfig",
                    "openai",
                    "openai",
                    "base.url",
                    "base-url",
                    "api.key",
                    "api-key",
                    "model.name",
                    "chat-model.model-name",
                    "temperature",
                    "chat-model.temperature",
                    "top.p",
                    "chat-model.top-p",
                    "max.tokens",
                    "chat-model.max-tokens"),
            "anthropic",
            model(
                    "anthropic.runtime.config.LangChain4jAnthropicConfig",
                    "anthropic",
                    "anthropic",
                    "base.url",
                    "base-url",
                    "api.key",
                    "api-key",
                    "model.name",
                    "chat-model.model-name",
                    "temperature",
                    "chat-model.temperature",
                    "top.p",
                    "chat-model.top-p",
                    "top.k",
                    "chat-model.top-k",
                    "max.tokens",
                    "chat-model.max-tokens"),
            "google-gemini",
            model(
                    "ai.runtime.gemini.config.LangChain4jAiGeminiConfig",
                    "ai-gemini",
                    "ai.gemini",
                    "api.key",
                    "api-key",
                    "base.url",
                    "base-url",
                    "model.name",
                    "chat-model.model-id",
                    "temperature",
                    "chat-model.temperature",
                    "top.p",
                    "chat-model.top-p",
                    "top.k",
                    "chat-model.top-k",
                    "max.tokens",
                    "chat-model.max-output-tokens"),
            // endpoint + deployment-name instead of base-url + model-name
            "azure-openai",
            model(
                    "azure.openai.runtime.config.LangChain4jAzureOpenAiConfig",
                    "azure-openai",
                    "azure-openai",
                    "api.key",
                    "api-key",
                    "endpoint",
                    "endpoint",
                    "deployment.name",
                    "deployment-name",
                    "temperature",
                    "chat-model.temperature",
                    "top.p",
                    "chat-model.top-p",
                    "max.tokens",
                    "chat-model.max-tokens"),
            "mistral-ai",
            model(
                    "mistralai.runtime.config.LangChain4jMistralAiConfig",
                    "mistral-ai",
                    "mistralai",
                    "base.url",
                    "base-url",
                    "api.key",
                    "api-key",
                    "model.name",
                    "chat-model.model-name",
                    "temperature",
                    "chat-model.temperature",
                    "top.p",
                    "chat-model.top-p",
                    "max.tokens",
                    "chat-model.max-tokens"),
            "hugging-face",
            model(
                    "huggingface.runtime.config.LangChain4jHuggingFaceConfig",
                    "hugging-face",
                    "huggingface",
                    "api.key",
                    "api-key",
                    "base.url",
                    "chat-model.inference-endpoint-url",
                    "temperature",
                    "chat-model.temperature",
                    "top.p",
                    "chat-model.top-p",
                    "top.k",
                    "chat-model.top-k",
                    "max.tokens",
                    "chat-model.max-new-tokens"),
            "watsonx-ai",
            model(
                    "watsonx.runtime.config.LangChain4jWatsonxConfig",
                    "watsonx",
                    "watsonx",
                    "base.url",
                    "base-url",
                    "api.key",
                    "api-key",
                    "model.name",
                    "chat-model.model-name",
                    "temperature",
                    "chat-model.temperature",
                    "top.p",
                    "chat-model.top-p",
                    "max.tokens",
                    "chat-model.max-output-tokens"),
            "bedrock",
            model(
                    "bedrock.runtime.config.LangChain4jBedrockConfig",
                    "bedrock",
                    "bedrock",
                    "model.name",
                    "chat-model.model-id",
                    "temperature",
                    "chat-model.temperature",
                    "top.p",
                    "chat-model.top-p",
                    "top.k",
                    "chat-model.top-k",
                    "max.tokens",
                    "chat-model.max-tokens"));

    /**
     * @param extensionClass after {@code io.quarkiverse.langchain4j.}
     * @param artifactId after {@code quarkus-langchain4j-}
     * @param namespace after {@code quarkus.langchain4j.}
     * @param keys pairs: Forage key, Quarkus key after the namespace; the log keys are added
     */
    private static QuarkusModel model(String extensionClass, String artifactId, String namespace, String... keys) {
        List<QuarkusProperty> properties = new ArrayList<>();
        for (int i = 0; i < keys.length; i += 2) {
            properties.add(new QuarkusProperty(
                    keys[i], "quarkus.langchain4j." + namespace + "." + keys[i + 1], VALUES.get(keys[i])));
        }
        for (String log : List.of("log.requests", "log.responses")) {
            properties.add(new QuarkusProperty(
                    log, "quarkus.langchain4j." + namespace + "." + log.replace('.', '-'), VALUES.get(log)));
        }
        return new QuarkusModel(
                "io.quarkiverse.langchain4j." + extensionClass, "quarkus-langchain4j-" + artifactId, properties);
    }

    /**
     * The Forage key (after {@code forage.[<name>.]agent.}) selecting the bean, the kinds the export translates with the
     * Quarkus keys each adds ({@code null}: none), whether only the default configuration is translated, and per kind
     * the Quarkus key of each Forage key and the Quarkus artifacts replacing the kind's Forage bean.
     */
    public record QuarkusSelector(
            String forageKey,
            Map<String, Map<String, String>> kinds,
            boolean defaultOnly,
            Map<String, Map<String, String>> properties,
            Map<String, List<String>> dependencies) {}

    /**
     * The selector of the Quarkus export: the chat model kinds with a quarkus-langchain4j extension, default agent
     * only (the translation writes the default Quarkus model). The Forage agent stays; its other keys stay forage.*.
     * Read by the catalog build.
     */
    public QuarkusSelector quarkusSelector() {
        Map<String, Map<String, String>> properties = new HashMap<>();
        Map<String, List<String>> dependencies = new HashMap<>();
        QUARKUS_MODELS.forEach((kind, model) -> {
            properties.put(
                    kind,
                    model.properties().stream()
                            .collect(Collectors.toMap(QuarkusProperty::forageKey, QuarkusProperty::quarkusKey)));
            dependencies.put(
                    kind,
                    List.of(
                            "mvn:io.quarkiverse.langchain4j:" + model.artifactId(),
                            "mvn:io.kaoto.forage:forage-quarkus-agent-langchain4j-deployment"));
        });
        return new QuarkusSelector("model.kind", null, true, properties, dependencies);
    }

    @Override
    public Map<String, String> translateProperties(String prefix, AgentConfig config) {
        QuarkusModel model = config.modelKind() == null ? null : QUARKUS_MODELS.get(config.modelKind());
        if (model == null) {
            return Map.of();
        }

        // Only translate when the corresponding quarkus-langchain4j extension is on
        // the classpath. Otherwise Forage creates the ChatModel via ServiceLoader and
        // the translated properties would produce "Unrecognized configuration key" warnings.
        try {
            Class.forName(model.extensionClass(), false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            return Map.of();
        }
        return translate(config.modelKind(), config);
    }

    /** The Quarkus keys of a model kind with a quarkus-langchain4j extension. */
    static Map<String, String> translate(String modelKind, AgentConfig config) {
        Map<String, String> props = new HashMap<>();
        for (QuarkusProperty property : QUARKUS_MODELS.get(modelKind).properties()) {
            putIfNotNull(props, property.quarkusKey(), property.value().apply(config));
        }
        return props;
    }

    private static void putIfNotNull(Map<String, String> props, String key, Object value) {
        if (value != null) {
            props.put(key, String.valueOf(value));
        }
    }
}
