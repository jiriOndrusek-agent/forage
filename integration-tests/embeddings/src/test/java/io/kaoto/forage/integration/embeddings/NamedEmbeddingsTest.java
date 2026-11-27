package io.kaoto.forage.integration.embeddings;

import java.util.function.Consumer;
import org.citrusframework.annotations.CitrusTest;
import org.citrusframework.junit.jupiter.CitrusSupport;
import org.citrusframework.spi.Resource;
import io.kaoto.forage.integration.tests.ForageIntegrationTest;
import io.kaoto.forage.integration.tests.ForageTestCaseRunner;
import io.kaoto.forage.integration.tests.IntegrationTestSetupExtension;
import io.kaoto.forage.integration.tests.PropertiesTemplateHelper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two named models and two named stores, configured in application.properties; the route selects
 * one of each with {@code #bean:<name>}.
 */
@CitrusSupport
@ExtendWith(IntegrationTestSetupExtension.class)
public class NamedEmbeddingsTest implements ForageIntegrationTest {
    private static final String NAME = "forage-named-embeddings-test";

    @Override
    public String runBeforeAll(ForageTestCaseRunner runner, Consumer<AutoCloseable> afterAll) {
        Resource properties = PropertiesTemplateHelper.createFromTemplate(
                classResource("application.properties.template"),
                "\\{\\{ollama.url}}",
                OllamaStub.instance().baseUrl(),
                afterAll);
        runner.when(forageRun(NAME)
                .addResource(properties)
                .addResource(classResource("route.camel.yaml"))
                .dumpIntegrationOutput(true));
        return NAME;
    }

    @Test
    @CitrusTest
    void usesTheSelectedBeans(ForageTestCaseRunner runner) {
        runner.then(camel().cli()
                .verify()
                .integration(NAME)
                .waitForLogMessage("Search result: Forage creates Camel beans from properties"));
        assertThat(OllamaStub.instance().models()).contains("docs-model").doesNotContain("faq-model");
    }
}
