package io.kaoto.forage.agent;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class AgentQuarkusPropertiesTest {

    private final AgentModuleDescriptor.QuarkusSelector selector = new AgentModuleDescriptor().quarkusSelector();

    @Test
    void selectsTheKindsWithAQuarkusExtension() {
        assertThat(selector.forageKey()).isEqualTo("model.kind");
        assertThat(selector.defaultOnly()).isTrue();
        assertThat(selector.dependencies())
                .containsOnlyKeys(
                        "ollama",
                        "openai",
                        "anthropic",
                        "google-gemini",
                        "azure-openai",
                        "mistral-ai",
                        "hugging-face",
                        "watsonx-ai",
                        "bedrock")
                .containsEntry(
                        "google-gemini",
                        List.of(
                                "mvn:io.quarkiverse.langchain4j:quarkus-langchain4j-ai-gemini",
                                "mvn:io.kaoto.forage:forage-quarkus-agent-langchain4j-deployment"));
        assertThat(selector.properties())
                .containsOnlyKeys(selector.dependencies().keySet());
    }

    @Test
    void translatesWhatTheCatalogLists() {
        AgentConfig config = new AgentConfig("quarkus");
        Map.of("base.url", "http://h:11434", "model.name", "granite4:3b", "temperature", "0.5", "api.key", "unused")
                .forEach((key, value) -> config.register("forage.quarkus.agent." + key, value));

        assertThat(AgentModuleDescriptor.translate("ollama", config))
                .containsOnly(
                        entry("quarkus.langchain4j.ollama.base-url", "http://h:11434"),
                        entry("quarkus.langchain4j.ollama.chat-model.model-id", "granite4:3b"),
                        entry("quarkus.langchain4j.ollama.chat-model.temperature", "0.5"));
        assertThat(selector.properties().get("ollama"))
                .containsOnly(
                        entry("base.url", "quarkus.langchain4j.ollama.base-url"),
                        entry("model.name", "quarkus.langchain4j.ollama.chat-model.model-id"),
                        entry("temperature", "quarkus.langchain4j.ollama.chat-model.temperature"),
                        entry("top.p", "quarkus.langchain4j.ollama.chat-model.top-p"),
                        entry("top.k", "quarkus.langchain4j.ollama.chat-model.top-k"),
                        entry("log.requests", "quarkus.langchain4j.ollama.log-requests"),
                        entry("log.responses", "quarkus.langchain4j.ollama.log-responses"));
    }

    /** Only the 11 model keys have a Quarkus key, the same in every kind that has them. */
    @Test
    void mapsElevenKeys() {
        assertThat(selector.properties().values().stream()
                        .flatMap(keys -> keys.keySet().stream())
                        .distinct())
                .containsExactlyInAnyOrder(
                        "api.key",
                        "base.url",
                        "model.name",
                        "temperature",
                        "top.p",
                        "top.k",
                        "max.tokens",
                        "endpoint",
                        "deployment.name",
                        "log.requests",
                        "log.responses");
    }
}
