package io.kaoto.forage.quarkus.agent.langchain4j.deployment;

import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.apache.camel.CamelContext;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
import org.apache.camel.component.langchain4j.agent.api.AiAgentWithMemoryService;
import org.apache.camel.component.langchain4j.agent.api.AiAgentWithoutMemoryService;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import io.kaoto.forage.agent.simple.ForageAgentWithMemory;
import io.kaoto.forage.agent.simple.ForageAgentWithoutMemory;
import io.kaoto.forage.quarkus.agent.ForageAgentRecorder;
import io.quarkiverse.langchain4j.deployment.ExcludeFromImpliedAiServiceBuildItem;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusUnitTest;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent with quarkus-langchain4j-ollama and no forage-model-ollama: the agent must use the Quarkus ChatModel bean,
 * configured from the translated forage.agent.* properties.
 */
class ForageAgentQuarkusChatModelTest {

    // The test class is loaded twice (JUnit and Quarkus class loaders), so share the request via a system property
    private static final String LAST_REQUEST = "forage.test.ollama.request";
    private static final HttpServer OLLAMA = fakeOllama();

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addAsResource(
                    new StringAsset("forage.agent.model.kind=ollama\n"
                            + "forage.agent.base.url=http://localhost:"
                            + OLLAMA.getAddress().getPort() + "\n"
                            + "forage.agent.model.name=granite4:3b\n"
                            + "quarkus.langchain4j.ollama.devservices.enabled=false\n"),
                    "application.properties"))
            // Exported apps add the deployment jar as a plain dependency, the agent runtime is not an extension
            .setForcedDependencies(List.of(Dependency.of(
                    "io.kaoto.forage",
                    "forage-quarkus-agent-langchain4j-deployment",
                    System.getProperty("project.version"))))
            // The Camel and Forage agent interfaces are implied AI services, which request the default ChatModel
            // as a side effect. Exclude them so the test proves the explicit request.
            .addBuildChainCustomizer(chain -> chain.addBuildStep(context -> Stream.of(
                                    AiAgentWithMemoryService.class,
                                    AiAgentWithoutMemoryService.class,
                                    ForageAgentWithMemory.class,
                                    ForageAgentWithoutMemory.class)
                            .forEach(c -> context.produce(new ExcludeFromImpliedAiServiceBuildItem(c.getName()))))
                    .produces(ExcludeFromImpliedAiServiceBuildItem.class)
                    .build())
            .setLogRecordPredicate(r -> ForageAgentRecorder.class.getName().equals(r.getLoggerName()))
            .assertLogRecords(records -> assertThat(records)
                    .extracting(r -> r.getMessage())
                    .contains("Using the default quarkus-langchain4j ChatModel for Agent '%s'"));

    @Inject
    CamelContext context;

    @Test
    void agentUsesQuarkusChatModel() {
        Agent agent = context.getRegistry().lookupByNameAndType("agent", Agent.class);

        assertThat(agent.chat(new AiAgentBody<>("Hello"), null).content()).isEqualTo("pong");
        assertThat(System.getProperty(LAST_REQUEST)).containsPattern("\"model\"\\s*:\\s*\"granite4:3b\"");
    }

    @AfterAll
    static void stopOllama() {
        OLLAMA.stop(0);
        System.clearProperty(LAST_REQUEST);
    }

    private static HttpServer fakeOllama() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/chat", exchange -> {
                System.setProperty(
                        LAST_REQUEST, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = ("{\"model\":\"granite4:3b\",\"created_at\":\"2026-01-01T00:00:00Z\","
                                + "\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},"
                                + "\"done\":true,\"done_reason\":\"stop\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
