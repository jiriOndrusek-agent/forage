package io.kaoto.forage.plugin;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import io.kaoto.forage.core.util.config.ConfigStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class QuarkusNativeExportTest {

    private static final String DS1 =
            """
            forage.ds1.jdbc.db.kind=postgresql
            forage.ds1.jdbc.url=jdbc:postgresql://localhost:5432/db
            forage.ds1.jdbc.username=test
            forage.ds1.jdbc.password=secret
            forage.ds1.jdbc.pool.min.size=4
            """;

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() {
        System.clearProperty("forage.ds1.jdbc.username");
        ConfigStore.getInstance().reload();
    }

    @Test
    void translatesPlainNamedDataSource() {
        QuarkusNativeExport.Result result = QuarkusNativeExport.translate(properties(DS1));

        assertThat(result.modules()).containsExactly("jdbc");
        assertThat(result.properties())
                .containsEntry("quarkus.datasource.\"ds1\".db-kind", "postgresql")
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.url", "jdbc:postgresql://localhost:5432/db")
                .containsEntry("quarkus.datasource.\"ds1\".username", "test")
                .containsEntry("quarkus.datasource.\"ds1\".password", "secret")
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.min-size", "4");
        assertThat(result.forageKeys()).hasSize(5).allMatch(key -> key.startsWith("forage.ds1.jdbc."));
        assertThat(result.messages())
                .singleElement()
                .asString()
                .contains("QUARKUS_DATASOURCE__DS1__JDBC_URL")
                .contains("FORAGE_DS1_JDBC_*");
    }

    @Test
    void ignoresExportTimeOverrides() {
        System.setProperty("forage.ds1.jdbc.username", "leaked");

        QuarkusNativeExport.Result result = QuarkusNativeExport.translate(properties(DS1));

        assertThat(result.properties()).containsEntry("quarkus.datasource.\"ds1\".username", "test");
        assertThat(result.messages()).singleElement().asString().contains("Ignored");
    }

    @Test
    void keepsModuleWhenOneDataSourceUsesTransactions() {
        QuarkusNativeExport.Result result = QuarkusNativeExport.translate(
                properties(
                        DS1
                                + """
                forage.ds2.jdbc.db.kind=h2
                forage.ds2.jdbc.url=jdbc:h2:mem:x
                forage.ds2.jdbc.username=sa
                forage.ds2.jdbc.password=sa
                forage.ds2.jdbc.transaction.enabled=true
                """));

        assertNotTranslated(result, "'ds2' uses transactions");
    }

    @Test
    void keepsPlaceholders() {
        assertNotTranslated(
                QuarkusNativeExport.translate(properties(DS1 + "forage.ds1.jdbc.password={{env:DB_PASSWORD}}\n")),
                "placeholders");
    }

    @Test
    void keepsProfileSpecificValues() {
        assertNotTranslated(
                QuarkusNativeExport.translate(
                        properties(DS1 + "%prod.forage.ds1.jdbc.url=jdbc:postgresql://prod/db\n")),
                "profile-specific");
    }

    @Test
    void keepsIncompleteConfiguration() {
        assertNotTranslated(
                QuarkusNativeExport.translate(properties("forage.jdbc.url=jdbc:postgresql://localhost/db\n")),
                "'jdbc'");
    }

    @Test
    void translatesDefaultArtemisConnectionFactory() {
        QuarkusNativeExport.Result result = QuarkusNativeExport.translate(
                properties(
                        """
                forage.jms.kind=artemis
                forage.jms.broker.url=tcp://localhost:61616
                forage.jms.username=artemis
                forage.jms.password=artemis
                """));

        assertThat(result.modules()).containsExactly("jms");
        assertThat(result.properties())
                .containsEntry("quarkus.artemis.url", "tcp://localhost:61616")
                .containsEntry("quarkus.artemis.username", "artemis");
        assertThat(result.forageKeys()).hasSize(4);
        assertThat(result.groups()).containsExactly(QuarkusNativeExport.JMS_POOL_GROUP);
    }

    @Test
    void dropsPoolPropertiesWhenPoolingDisabled() {
        QuarkusNativeExport.Result result = QuarkusNativeExport.translate(
                properties(
                        """
                forage.jms.kind=artemis
                forage.jms.broker.url=tcp://localhost:61616
                forage.jms.pool.enabled=false
                forage.jms.pool.max.connections=5
                """));

        assertThat(result.modules()).containsExactly("jms");
        assertThat(result.properties()).containsKey("quarkus.artemis.url");
        assertThat(result.properties().keySet()).noneMatch(key -> key.startsWith("quarkus.pooled-jms."));
        assertThat(result.groups()).isEmpty();
    }

    @Test
    void keepsUnknownJmsKind() {
        assertNotTranslated(
                QuarkusNativeExport.translate(
                        properties(
                                """
                        forage.jms.kind=foo
                        forage.jms.broker.url=tcp://localhost:61616
                        """)),
                "'jms'");
    }

    @Test
    void keepsIdempotentRepositoryWithoutTransactions() {
        assertNotTranslated(
                QuarkusNativeExport.translate(properties(DS1 + "forage.ds1.jdbc.idempotent.repository.enabled=true\n")),
                "'ds1' uses Forage repositories");
    }

    @Test
    void keepsNamedJmsBrokers() {
        assertNotTranslated(
                QuarkusNativeExport.translate(
                        properties(
                                """
                        forage.mq1.jms.kind=artemis
                        forage.mq1.jms.broker.url=tcp://localhost:61616
                        """)),
                "named configurations");
    }

    @Test
    void keepsIbmMq() {
        assertNotTranslated(
                QuarkusNativeExport.translate(
                        properties(
                                """
                        forage.jms.kind=ibmmq
                        forage.jms.broker.url=localhost(1414)
                        """)),
                "no Quarkus equivalent");
    }

    @Test
    void loadsProfileFilesAsProfileSpecific() throws IOException {
        Files.writeString(dir.resolve("forage-datasource-factory.properties"), DS1);
        Files.writeString(
                dir.resolve("application-prod.properties"), "forage.ds1.jdbc.url=jdbc:postgresql://prod/db\n");

        Properties loaded = QuarkusNativeExport.load(dir);

        assertThat(loaded).containsKeys("forage.ds1.jdbc.url", "%prod.forage.ds1.jdbc.url");
    }

    @Test
    void writeKeepsUserKeysAndRemovesForageKeys() throws IOException {
        Files.writeString(
                dir.resolve("application.properties"),
                """
                camel.main.name=demo
                quarkus.datasource."ds1".jdbc.max-size=50
                forage.ds1.jdbc.url=jdbc:postgresql://localhost:5432/db
                """);
        Files.writeString(
                dir.resolve("forage-datasource-factory.properties"),
                """
                # datasource
                forage.ds1.jdbc.db.kind=postgresql
                forage.ds1.jdbc.username=test
                forage.ds1.jdbc.password=secret
                forage.ds1.jdbc.pool.min.size=4
                """);

        QuarkusNativeExport.write(QuarkusNativeExport.translate(QuarkusNativeExport.load(dir)), dir);

        Properties application = read(dir.resolve("application.properties"));
        assertThat(application)
                .containsEntry("camel.main.name", "demo")
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.max-size", "50")
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.url", "jdbc:postgresql://localhost:5432/db")
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.min-size", "4");
        assertThat(application.stringPropertyNames()).noneMatch(key -> key.startsWith("forage."));
        assertThat(Files.readString(dir.resolve("forage-datasource-factory.properties")))
                .isEqualTo("# datasource\n");
    }

    @Test
    void removeKeysDropsContinuationLines() {
        List<String> kept = QuarkusNativeExport.removeKeys(
                List.of("a=1", "forage.x=one \\", "  two", "# comment \\", "b = 2"), Set.of("forage.x"));

        assertThat(kept).containsExactly("a=1", "# comment \\", "b = 2");
    }

    private static void assertNotTranslated(QuarkusNativeExport.Result result, String reason) {
        assertThat(result.properties()).isEmpty();
        assertThat(result.forageKeys()).isEmpty();
        assertThat(result.modules()).isEmpty();
        assertThat(result.messages())
                .singleElement()
                .asString()
                .contains("stays as forage.*")
                .contains(reason);
    }

    private static Properties properties(String content) {
        try {
            Properties properties = new Properties();
            properties.load(new StringReader(content));
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Properties read(Path file) throws IOException {
        return properties(Files.readString(file));
    }
}
