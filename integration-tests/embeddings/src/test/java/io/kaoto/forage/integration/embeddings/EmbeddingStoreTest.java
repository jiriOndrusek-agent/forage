package io.kaoto.forage.integration.embeddings;

import java.util.function.Consumer;
import org.citrusframework.annotations.CitrusTest;
import org.citrusframework.junit.jupiter.CitrusSupport;
import org.citrusframework.spi.Resource;
import org.citrusframework.spi.Resources;
import io.kaoto.forage.integration.tests.ForageIntegrationTest;
import io.kaoto.forage.integration.tests.ForageTestCaseRunner;
import io.kaoto.forage.integration.tests.IntegrationTestSetupExtension;
import io.kaoto.forage.integration.tests.PropertiesTemplateHelper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default {@code embeddingModel} and {@code embeddingStore} beans, configured in the module
 * properties files, ingest two texts and find the closest one.
 */
@CitrusSupport
@ExtendWith(IntegrationTestSetupExtension.class)
public class EmbeddingStoreTest implements ForageIntegrationTest {
    private static final String NAME = "forage-embedding-store-test";

    @Override
    public String runBeforeAll(ForageTestCaseRunner runner, Consumer<AutoCloseable> afterAll) {
        Resource ollama = PropertiesTemplateHelper.createFromTemplate(
                classResource("forage-embedding-model-ollama.properties.template"),
                "\\{\\{ollama.url}}",
                OllamaStub.instance().baseUrl(),
                afterAll);
        runner.when(forageRun(NAME)
                // the integration runs in the directory of the first resource
                .addResource(ollama)
                .addResource(copy(ollama, classResource("forage-embedding-model.properties"), afterAll))
                .addResource(copy(ollama, classResource("forage-embedding-store.properties"), afterAll))
                .addResource(classResource("route.camel.yaml"))
                .dumpIntegrationOutput(true));
        return NAME;
    }

    static Resource copy(Resource anchor, Resource resource, Consumer<AutoCloseable> afterAll) {
        PropertiesTemplateHelper.copyIntoSameDirectory(anchor, resource, afterAll);
        return Resources.create(
                anchor.file().toPath().resolveSibling(resource.file().getName()).toFile());
    }

    @Test
    @CitrusTest
    void findsTheClosestText(ForageTestCaseRunner runner) {
        runner.then(camel().cli()
                .verify()
                .integration(NAME)
                .waitForLogMessage("Search result: Forage creates Camel beans from properties"));
        assertThat(OllamaStub.instance().models()).contains("forage-test-model");
    }
}
