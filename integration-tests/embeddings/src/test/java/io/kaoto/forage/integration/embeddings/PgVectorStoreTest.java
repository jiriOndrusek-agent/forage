package io.kaoto.forage.integration.embeddings;

import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import org.citrusframework.annotations.CitrusTest;
import org.citrusframework.junit.jupiter.CitrusSupport;
import org.citrusframework.spi.Resource;
import org.citrusframework.spi.Resources;
import org.eclipse.microprofile.config.ConfigProvider;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import io.kaoto.forage.integration.tests.ForageIntegrationTest;
import io.kaoto.forage.integration.tests.ForageTestCaseRunner;
import io.kaoto.forage.integration.tests.IntegrationTestSetupExtension;
import io.kaoto.forage.integration.tests.PropertiesTemplateHelper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Like {@link EmbeddingStoreTest}, with a pgvector store. Needs a container runtime, so it runs with
 * {@code -Dpgvector.it=true} only, like the vector database tests of the library modules.
 */
@CitrusSupport
@ExtendWith(IntegrationTestSetupExtension.class)
@EnabledIfSystemProperty(named = "pgvector.it", matches = "true")
public class PgVectorStoreTest implements ForageIntegrationTest {
    private static final String NAME = "forage-pgvector-store-test";
    private static final PostgreSQLContainer<?> PGVECTOR = new PostgreSQLContainer<>(
                    DockerImageName.parse(ConfigProvider.getConfig().getValue("pgvector.container.image", String.class))
                            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("vectors")
            .withUsername("test")
            .withPassword("test");

    @Override
    public String runBeforeAll(ForageTestCaseRunner runner, Consumer<AutoCloseable> afterAll) {
        synchronized (PGVECTOR) {
            if (!PGVECTOR.isRunning()) {
                PGVECTOR.start();
            }
        }
        Resource pgvector = PropertiesTemplateHelper.createFromTemplate(
                classResource("forage-vectordb-pgvector.properties.template"),
                Map.of(
                        "\\{\\{pgvector.host}}",
                        Matcher.quoteReplacement(PGVECTOR.getHost()),
                        "\\{\\{pgvector.port}}",
                        String.valueOf(PGVECTOR.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)),
                        "\\{\\{pgvector.dimension}}",
                        String.valueOf(OllamaStub.DIMENSION)),
                afterAll);
        Resource ollama = PropertiesTemplateHelper.createFromTemplate(
                Resources.fromClasspath(
                        "EmbeddingStoreTest/forage-embedding-model-ollama.properties.template",
                        EmbeddingStoreTest.class),
                "\\{\\{ollama.url}}",
                OllamaStub.instance().baseUrl(),
                afterAll);
        runner.when(forageRun(NAME)
                .addResource(pgvector)
                .addResource(
                        EmbeddingStoreTest.copy(pgvector, classResource("forage-embedding-store.properties"), afterAll))
                .addResource(EmbeddingStoreTest.copy(pgvector, ollama, afterAll))
                .addResource(EmbeddingStoreTest.copy(
                        pgvector,
                        Resources.fromClasspath(
                                "EmbeddingStoreTest/forage-embedding-model.properties", EmbeddingStoreTest.class),
                        afterAll))
                .addResource(Resources.fromClasspath("EmbeddingStoreTest/route.camel.yaml", EmbeddingStoreTest.class))
                .dumpIntegrationOutput(true));
        return NAME;
    }

    @Test
    @CitrusTest
    void findsTheClosestText(ForageTestCaseRunner runner) {
        runner.then(camel().cli()
                .verify()
                .integration(NAME)
                .waitForLogMessage("Search result: Forage creates Camel beans from properties"));
    }
}
