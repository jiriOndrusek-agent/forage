package io.kaoto.forage.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import io.kaoto.forage.core.exceptions.RuntimeForageException;
import io.kaoto.forage.core.util.config.ConfigEntries;
import io.kaoto.forage.core.util.config.ConfigModule;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.models.chat.anthropic.AnthropicConfigEntries;
import io.kaoto.forage.models.chat.azureopenai.AzureOpenAiConfigEntries;
import io.kaoto.forage.models.chat.bedrock.BedrockConfigEntries;
import io.kaoto.forage.models.chat.dashscope.DashscopeConfigEntries;
import io.kaoto.forage.models.chat.google.GoogleConfigEntries;
import io.kaoto.forage.models.chat.huggingface.HuggingFaceConfigEntries;
import io.kaoto.forage.models.chat.localai.LocalAiConfigEntries;
import io.kaoto.forage.models.chat.mistralai.MistralAiConfigEntries;
import io.kaoto.forage.models.chat.ollama.OllamaConfigEntries;
import io.kaoto.forage.models.chat.openai.OpenAIConfigEntries;
import io.kaoto.forage.models.chat.watsonxai.WatsonxAiConfigEntries;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("AgentCreator Tests")
class AgentCreatorTest {

    private static final String TEST_PROVIDER = "test.provider";
    private static final String TEST_KEY = "test.key";
    private static final String FULL_KEY = "forage." + TEST_PROVIDER + "." + TEST_KEY;

    @AfterEach
    void clearTestProperty() {
        System.clearProperty(FULL_KEY);
    }

    @Nested
    @DisplayName("getProviderConfigPrefix() — drift-prevention")
    class PrefixMappingTests {

        /**
         * Cross-checks each model kind's mapped prefix against the actual configuration keys
         * registered by the corresponding module's ConfigEntries class, instead of hardcoded
         * string literals. If a module ever renames its keys (e.g. {@code forage.mistralai.*}
         * to {@code forage.mistral.ai.*}) this test fails until the switch in
         * {@code AgentCreator#getProviderConfigPrefix} is updated.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.kaoto.forage.agent.AgentCreatorTest#modelKindToConfigEntries")
        @DisplayName("Model kind prefix matches the keys registered by the module's ConfigEntries")
        void modelKindPrefixMatchesModuleConfigEntries(String modelKind, Class<? extends ConfigEntries> entriesClass)
                throws ClassNotFoundException {
            // getModules() does not force static initialization of the subclass on all branches,
            // so initialize the registry explicitly before reading it
            Class.forName(entriesClass.getName(), true, entriesClass.getClassLoader());

            String prefix = AgentCreator.getProviderConfigPrefix(modelKind);

            Set<String> propertyNames = ConfigEntries.getModules(entriesClass).keySet().stream()
                    .map(ConfigModule::propertyName)
                    .collect(Collectors.toSet());
            assertThat(propertyNames).isNotEmpty();

            // The bridge writes keys of the form forage.<prefix>.<key>; the module must actually
            // read at least one of them ("temperature" is defined by every chat model module)
            assertThat(propertyNames).contains("forage." + prefix + ".temperature");

            // ...and every key registered by the module must live under the same prefix,
            // so a partial prefix (e.g. "azure" instead of "azure.openai") cannot pass
            assertThat(propertyNames).allSatisfy(name -> assertThat(name).startsWith("forage." + prefix + "."));
        }

        @Test
        @DisplayName("Unknown model kinds fall back to dash-to-dot conversion")
        void unknownModelKindFallsBackToDashDotConversion() {
            assertThat(AgentCreator.getProviderConfigPrefix("some-new-provider"))
                    .isEqualTo("some.new.provider");
        }
    }

    static Stream<Arguments> modelKindToConfigEntries() {
        return Stream.of(
                arguments("openai", OpenAIConfigEntries.class),
                arguments("ollama", OllamaConfigEntries.class),
                arguments("google-gemini", GoogleConfigEntries.class),
                arguments("azure-openai", AzureOpenAiConfigEntries.class),
                arguments("anthropic", AnthropicConfigEntries.class),
                arguments("mistral-ai", MistralAiConfigEntries.class),
                arguments("hugging-face", HuggingFaceConfigEntries.class),
                arguments("watsonx-ai", WatsonxAiConfigEntries.class),
                arguments("local-ai", LocalAiConfigEntries.class),
                arguments("dashscope", DashscopeConfigEntries.class),
                // not in the switch: exercises the dash-to-dot fallback against a real module
                arguments("bedrock", BedrockConfigEntries.class));
    }

    @Nested
    @DisplayName("setSystemPropertyIfNotNull() + restoreSystemProperties()")
    class SnapshotRestoreTests {

        @Test
        @DisplayName("Previous property value is restored after set")
        void previousPropertyValueRestoredAfterSet() {
            System.setProperty(FULL_KEY, "original-value");

            Map<String, String> snapshot = new LinkedHashMap<>();
            AgentCreator.setSystemPropertyIfNotNull(snapshot, null, TEST_PROVIDER, TEST_KEY, "overridden-value");

            assertThat(System.getProperty(FULL_KEY)).isEqualTo("overridden-value");

            AgentCreator.restoreSystemProperties(snapshot);

            assertThat(System.getProperty(FULL_KEY)).isEqualTo("original-value");
        }

        @Test
        @DisplayName("Property is cleared when it was not previously set")
        void propertyIsClearedWhenNotPreviouslySet() {
            assertThat(System.getProperty(FULL_KEY)).isNull();

            Map<String, String> snapshot = new LinkedHashMap<>();
            AgentCreator.setSystemPropertyIfNotNull(snapshot, null, TEST_PROVIDER, TEST_KEY, "new-value");

            assertThat(System.getProperty(FULL_KEY)).isEqualTo("new-value");

            AgentCreator.restoreSystemProperties(snapshot);

            assertThat(System.getProperty(FULL_KEY)).isNull();
        }

        @Test
        @DisplayName("Null value does not set property and snapshot stays empty")
        void nullValueDoesNotSetProperty() {
            Map<String, String> snapshot = new LinkedHashMap<>();
            AgentCreator.setSystemPropertyIfNotNull(snapshot, null, TEST_PROVIDER, TEST_KEY, null);

            assertThat(System.getProperty(FULL_KEY)).isNull();
            assertThat(snapshot).isEmpty();
        }

        @Test
        @DisplayName("Prefixed key is built correctly")
        void prefixedKeyIsBuiltCorrectly() {
            String prefixedKey = "forage.myagent." + TEST_PROVIDER + "." + TEST_KEY;
            try {
                Map<String, String> snapshot = new LinkedHashMap<>();
                AgentCreator.setSystemPropertyIfNotNull(snapshot, "myagent", TEST_PROVIDER, TEST_KEY, "val");

                assertThat(System.getProperty(prefixedKey)).isEqualTo("val");

                AgentCreator.restoreSystemProperties(snapshot);
                assertThat(System.getProperty(prefixedKey)).isNull();
            } finally {
                System.clearProperty(prefixedKey);
            }
        }
    }

    @Nested
    @DisplayName("createChatModel() bridge — real call site")
    class ChatModelBridgeTests {

        private final ClassLoader classLoader = getClass().getClassLoader();

        @AfterEach
        void cleanup() {
            System.clearProperty("forage.agent.api.key");
            System.clearProperty("forage.agent.model.name");
            System.clearProperty("forage.agent.temperature");
            System.clearProperty(CapturingChatModelProvider.PROPERTY_PREFIX + "api.key");
            System.clearProperty(CapturingChatModelProvider.PROPERTY_PREFIX + "model.name");
            CapturingChatModelProvider.reset();
            ConfigStore.getInstance().reload();
        }

        @Test
        @DisplayName("forage.agent.* properties are bridged to the provider's keys during create()")
        void bridgesAgentPropertiesToProviderKeys() {
            System.setProperty("forage.agent.api.key", "bridged-key");
            System.setProperty("forage.agent.model.name", "bridged-model");
            ConfigStore.getInstance().reload();
            AgentConfig config = new AgentConfig();
            CapturingChatModelProvider.reset();

            AgentCreator.createChatModel(
                    config, CapturingChatModelProvider.KIND, AgentCreator.DEFAULT_AGENT, classLoader);

            Map<String, String> seen = CapturingChatModelProvider.captured();
            assertThat(seen)
                    .containsEntry("forage.test.chat.api.key", "bridged-key")
                    .containsEntry("forage.test.chat.model.name", "bridged-model");

            // bridged properties are restored (cleared) once creation completes
            assertThat(System.getProperty("forage.test.chat.api.key")).isNull();
            assertThat(System.getProperty("forage.test.chat.model.name")).isNull();
        }

        @Test
        @DisplayName("A malformed config value does not leak already-bridged system properties")
        void malformedConfigValueDoesNotLeakBridgedProperties() {
            // simulate a user-supplied -D value that must survive the failed bridge
            System.setProperty("forage.test.chat.api.key", "user-supplied");
            System.setProperty("forage.agent.api.key", "bridged-key");
            System.setProperty("forage.agent.temperature", "not-a-number");
            ConfigStore.getInstance().reload();
            AgentConfig config = new AgentConfig();

            // api.key is bridged before temperature is evaluated; parsing "not-a-number" throws
            assertThatThrownBy(() -> AgentCreator.createChatModel(
                            config, CapturingChatModelProvider.KIND, AgentCreator.DEFAULT_AGENT, classLoader))
                    .isInstanceOf(NumberFormatException.class);

            // the pre-existing user value must be restored, not clobbered by "bridged-key"
            assertThat(System.getProperty("forage.test.chat.api.key")).isEqualTo("user-supplied");
            assertThat(System.getProperty("forage.test.chat.model.name")).isNull();
        }
    }

    @Nested
    @DisplayName("createEmbeddingModel() bridge — real call site")
    class EmbeddingBridgeTests {

        private final ClassLoader classLoader = getClass().getClassLoader();

        @AfterEach
        void cleanup() {
            System.clearProperty("forage.agent.embedding.model.base.url");
            System.clearProperty("forage.agent.embedding.model.name");
            System.clearProperty("forage.agent.embedding.model.max.retries");
            System.clearProperty("forage.agent.embedding.model.timeout");
            CapturingEmbeddingModelProvider.reset();
            ConfigStore.getInstance().reload();
        }

        @Test
        @DisplayName("Embedding bridge sets embedding.model.* keys, not the shorter embedding.* keys")
        void embeddingBridgePassesModelKeysToProvider() {
            System.setProperty("forage.agent.embedding.model.base.url", "http://captured:1234");
            System.setProperty("forage.agent.embedding.model.name", "bridged-embed-model");
            System.setProperty("forage.agent.embedding.model.max.retries", "7");
            System.setProperty("forage.agent.embedding.model.timeout", "PT30S");
            ConfigStore.getInstance().reload();
            AgentConfig config = new AgentConfig();
            CapturingEmbeddingModelProvider.reset();

            AgentCreator.createEmbeddingModel(
                    config, CapturingEmbeddingModelProvider.KIND, AgentCreator.DEFAULT_AGENT, classLoader);

            Map<String, String> seen = CapturingEmbeddingModelProvider.captured();
            // the provider must observe the bridged embedding.model.* keys at creation time
            assertThat(seen)
                    .containsEntry("forage.test.embed.embedding.model.base.url", "http://captured:1234")
                    .containsEntry("forage.test.embed.embedding.model.name", "bridged-embed-model")
                    .containsEntry("forage.test.embed.embedding.model.max.retries", "7")
                    .containsEntry("forage.test.embed.embedding.model.timeout", "PT30S");

            // regression guard: the historically wrong (shorter) keys must NOT be set
            assertThat(seen)
                    .doesNotContainKey("forage.test.embed.embedding.base.url")
                    .doesNotContainKey("forage.test.embed.embedding.name")
                    .doesNotContainKey("forage.test.embed.embedding.max.retries")
                    .doesNotContainKey("forage.test.embed.embedding.timeout");

            // bridged properties are restored (cleared) once creation completes
            assertThat(System.getProperty("forage.test.embed.embedding.model.base.url"))
                    .isNull();
            assertThat(System.getProperty("forage.test.embed.embedding.model.name"))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("createEmbeddingStore() — in-memory store of an agent")
    class EmbeddingStoreTests {

        private final ClassLoader classLoader = getClass().getClassLoader();

        // Every text gets the same vector, enough to find the seeded segments
        private final EmbeddingModel model = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                return Response.from(segments.stream()
                        .map(segment -> Embedding.from(new float[] {1, 0}))
                        .toList());
            }
        };

        @AfterEach
        void cleanup() {
            System.clearProperty("forage.agent.embedding.store.kind");
            System.clearProperty("forage.agent.in.memory.store.file.source");
            ConfigStore.getInstance().reload();
        }

        @Test
        @DisplayName("The seed file is embedded with the agent's embedding model")
        void seedsInMemoryStore() {
            System.setProperty("forage.agent.embedding.store.kind", "in-memory-store");
            System.setProperty("forage.agent.in.memory.store.file.source", "agent-seed.txt");
            ConfigStore.getInstance().reload();

            EmbeddingStore<TextSegment> store = AgentCreator.createEmbeddingStore(
                    new AgentConfig(), "ollama", AgentCreator.DEFAULT_AGENT, classLoader, model);

            assertThat(store.search(EmbeddingSearchRequest.builder()
                                    .queryEmbedding(Embedding.from(new float[] {1, 0}))
                                    .build())
                            .matches())
                    .isNotEmpty()
                    .allSatisfy(match -> assertThat(match.embedded().text()).contains("Forage"));
            // bridged properties are restored (cleared) once creation completes
            assertThat(System.getProperty("forage.in.memory.store.file.source")).isNull();
        }

        @Test
        @DisplayName("Without a seed file the agent gets no in-memory store")
        void noInMemoryStoreWithoutSeed() {
            System.setProperty("forage.agent.embedding.store.kind", "in-memory-store");
            ConfigStore.getInstance().reload();

            assertThat(AgentCreator.createEmbeddingStore(
                            new AgentConfig(), "ollama", AgentCreator.DEFAULT_AGENT, classLoader, model))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("loadInputGuardrails() — opt-in selection")
    class GuardrailLoadingTests {

        private final ClassLoader classLoader = getClass().getClassLoader();

        @AfterEach
        void cleanup() {
            System.clearProperty("forage.agent.guardrails.input");
            TestInputGuardrailProvider.reset();
            ConfigStore.getInstance().reload();
        }

        @Test
        @DisplayName("No config → no guardrails loaded")
        void noConfigReturnsEmpty() {
            ConfigStore.getInstance().reload();
            AgentConfig config = new AgentConfig();

            List<InputGuardrail> result =
                    AgentCreator.loadInputGuardrails(config, AgentCreator.DEFAULT_AGENT, classLoader);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Selected guardrail is loaded by @ForageBean value")
        void selectedGuardrailIsLoadedByName() {
            System.setProperty("forage.agent.guardrails.input", TestInputGuardrailProvider.NAME);
            ConfigStore.getInstance().reload();
            AgentConfig config = new AgentConfig();

            List<InputGuardrail> result =
                    AgentCreator.loadInputGuardrails(config, AgentCreator.DEFAULT_AGENT, classLoader);

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).isInstanceOf(NoopInputGuardrail.class);
        }

        @Test
        @DisplayName("Unknown guardrail name throws RuntimeForageException")
        void unknownNameThrows() {
            System.setProperty("forage.agent.guardrails.input", "nonexistent-guardrail");
            ConfigStore.getInstance().reload();
            AgentConfig config = new AgentConfig();

            assertThatThrownBy(() -> AgentCreator.loadInputGuardrails(config, AgentCreator.DEFAULT_AGENT, classLoader))
                    .isInstanceOf(RuntimeForageException.class)
                    .hasMessageContaining("nonexistent-guardrail");
        }

        @Test
        @DisplayName("Failing provider creation propagates exception (fail closed)")
        void failingProviderPropagatesException() {
            System.setProperty("forage.agent.guardrails.input", FailingInputGuardrailProvider.NAME);
            ConfigStore.getInstance().reload();
            AgentConfig config = new AgentConfig();

            assertThatThrownBy(() -> AgentCreator.loadInputGuardrails(config, AgentCreator.DEFAULT_AGENT, classLoader))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Simulated guardrail creation failure");
        }
    }
}
