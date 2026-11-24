package io.kaoto.forage.quarkus.agent.deployment;

import jakarta.inject.Inject;

import java.util.List;
import org.apache.camel.CamelContext;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import io.kaoto.forage.quarkus.agent.ForageAgentRecorder;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusUnitTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent with forage-model-ollama and without quarkus-langchain4j: the agent must create its model via ServiceLoader.
 */
class ForageAgentServiceLoaderTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addAsResource(
                    new StringAsset("forage.agent.model.kind=ollama\n"
                            + "forage.agent.base.url=http://localhost:11434\n"
                            + "forage.agent.model.name=granite4:3b\n"),
                    "application.properties"))
            // Exported apps add the deployment jar as a plain dependency, the agent runtime is not an extension
            .setForcedDependencies(List.of(Dependency.of(
                    "io.kaoto.forage", "forage-quarkus-agent-deployment", System.getProperty("project.version"))))
            .setLogRecordPredicate(r -> ForageAgentRecorder.class.getName().equals(r.getLoggerName()))
            .assertLogRecords(records -> assertThat(records)
                    .extracting(r -> r.getMessage())
                    .contains("No ChatModel '%s' in registry, creating Agent via ServiceLoader")
                    .doesNotContain("Using the default quarkus-langchain4j ChatModel for Agent '%s'"));

    @Inject
    CamelContext context;

    @Test
    void agentUsesServiceLoaderModel() {
        assertThat(context.getRegistry().lookupByNameAndType("agent", Agent.class))
                .isNotNull();
    }
}
