package io.kaoto.forage.plugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.kaoto.forage.core.util.config.ConfigStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class QuarkusPropertyTranslatorTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanUp() {
        ConfigStore.getInstance().reload();
    }

    @Nested
    class ExtractPrefixTest {

        @Test
        void defaultInstance() {
            assertThat(QuarkusPropertyTranslator.extractNamedPrefix("forage.jdbc.url", "jdbc"))
                    .isNull();
        }

        @Test
        void namedInstance() {
            assertThat(QuarkusPropertyTranslator.extractNamedPrefix("forage.ds1.jdbc.url", "jdbc"))
                    .isEqualTo("ds1");
        }

        @Test
        void modulePrefixOnly() {
            assertThat(QuarkusPropertyTranslator.extractNamedPrefix("forage.jdbc", "jdbc"))
                    .isNull();
        }

        @Test
        void namedInstanceModulePrefixOnly() {
            assertThat(QuarkusPropertyTranslator.extractNamedPrefix("forage.ds1.jdbc", "jdbc"))
                    .isEqualTo("ds1");
        }

        @Test
        void multiSegmentModulePrefix() {
            assertThat(QuarkusPropertyTranslator.extractNamedPrefix("forage.spring.rabbitmq.host", "spring.rabbitmq"))
                    .isNull();
        }

        @Test
        void multiSegmentModulePrefixWithNamedInstance() {
            assertThat(QuarkusPropertyTranslator.extractNamedPrefix(
                            "forage.mq1.spring.rabbitmq.host", "spring.rabbitmq"))
                    .isEqualTo("mq1");
        }

        @Test
        void nonForageProperty() {
            assertThat(QuarkusPropertyTranslator.extractNamedPrefix("quarkus.datasource.url", "jdbc"))
                    .isNull();
        }

        @Test
        void noMatchingModulePrefix() {
            assertThat(QuarkusPropertyTranslator.extractNamedPrefix("forage.unknown.property", "jdbc"))
                    .isNull();
        }
    }

    @Nested
    class TranslateTest {

        @Test
        void jdbcPropertiesTranslated() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.jdbc.db.kind=postgresql",
                    "forage.jdbc.url=jdbc:postgresql://localhost:5432/mydb",
                    "forage.jdbc.username=admin",
                    "forage.jdbc.password=secret");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isFalse();
            Map<String, String> qProps = flattenGroups(result);
            assertThat(qProps).containsEntry("quarkus.datasource.\"dataSource\".db-kind", "postgresql");
            assertThat(qProps)
                    .containsEntry(
                            "quarkus.datasource.\"dataSource\".jdbc.url", "jdbc:postgresql://localhost:5432/mydb");
            assertThat(qProps).containsEntry("quarkus.datasource.\"dataSource\".username", "admin");
            assertThat(qProps).containsEntry("quarkus.datasource.\"dataSource\".password", "secret");

            Set<String> translatedKeys = result.translatedForageKeys();
            assertThat(translatedKeys)
                    .contains("forage.jdbc.db.kind", "forage.jdbc.url", "forage.jdbc.username", "forage.jdbc.password");
        }

        @Test
        void namedJdbcInstancesTranslated() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.ds1.jdbc.db.kind=postgresql",
                    "forage.ds1.jdbc.url=jdbc:postgresql://localhost/db1",
                    "forage.ds1.jdbc.username=user1",
                    "forage.ds1.jdbc.password=pass1",
                    "forage.ds2.jdbc.db.kind=mysql",
                    "forage.ds2.jdbc.url=jdbc:mysql://localhost/db2",
                    "forage.ds2.jdbc.username=user2",
                    "forage.ds2.jdbc.password=pass2");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            Map<String, String> qProps = flattenGroups(result);
            assertThat(qProps).containsEntry("quarkus.datasource.\"ds1\".db-kind", "postgresql");
            assertThat(qProps).containsEntry("quarkus.datasource.\"ds2\".db-kind", "mysql");

            assertThat(result.groups())
                    .extracting(QuarkusPropertyTranslator.TranslationGroup::moduleType)
                    .containsOnly("jdbc");
            assertThat(result.groups())
                    .extracting(QuarkusPropertyTranslator.TranslationGroup::prefix)
                    .contains("ds1", "ds2");
        }

        @Test
        void jdbcDriverPropertiesTranslated() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.jdbc.db.kind=oracle",
                    "forage.jdbc.url=jdbc:oracle:thin:@localhost:1521/db",
                    "forage.jdbc.username=admin",
                    "forage.jdbc.password=secret",
                    "forage.jdbc.properties.oracle.jdbc.ReadTimeout=5000");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(flattenGroups(result))
                    .containsEntry(
                            "quarkus.datasource.\"dataSource\".jdbc.additional-jdbc-properties.\"oracle.jdbc.ReadTimeout\"",
                            "5000");
            assertThat(result.translatedForageKeys()).contains("forage.jdbc.properties.oracle.jdbc.ReadTimeout");
            assertThat(result.warnings()).isEmpty();
        }

        @Test
        void jdbcAuxiliaryBeanAndTransactionPropertiesKeepTheGroup() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.ds1.jdbc.db.kind=postgresql",
                    "forage.ds1.jdbc.url=jdbc:postgresql://localhost/db1",
                    "forage.ds1.jdbc.username=user1",
                    "forage.ds1.jdbc.password=pass1",
                    "forage.ds1.jdbc.transaction.enabled=true",
                    "forage.ds1.jdbc.aggregation.repository.name=myAggregation",
                    "forage.ds1.jdbc.idempotent.repository.enabled=true",
                    "forage.ds2.jdbc.db.kind=mysql",
                    "forage.ds2.jdbc.url=jdbc:mysql://localhost/db2",
                    "forage.ds2.jdbc.username=user2",
                    "forage.ds2.jdbc.password=pass2");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.groups())
                    .extracting(QuarkusPropertyTranslator.TranslationGroup::prefix)
                    .containsExactly("ds2");
            assertThat(result.translatedForageKeys()).noneMatch(key -> key.startsWith("forage.ds1."));
            assertThat(flattenGroups(result).keySet()).noneMatch(key -> key.contains("\"ds1\""));
            assertThat(result.warnings())
                    .containsExactly("forage.ds1.jdbc.* kept unchanged: no 1:1 Quarkus property for "
                            + "forage.ds1.jdbc.aggregation.repository.name, forage.ds1.jdbc.idempotent.repository.enabled, "
                            + "forage.ds1.jdbc.transaction.enabled");
        }

        @Test
        void placeholdersKeepTheGroupUnchanged() throws IOException {
            System.setProperty("w106.db.password", "resolved-secret");
            try {
                createPropertiesFile(
                        "application.properties",
                        "forage.jdbc.db.kind=postgresql",
                        "forage.jdbc.url=jdbc:postgresql://localhost:5432/mydb",
                        "forage.jdbc.username={{env:W106_DB_USER:admin}}",
                        "forage.jdbc.password={{sys:w106.db.password}}");

                QuarkusPropertyTranslator.TranslationResult result =
                        QuarkusPropertyTranslator.translate(tempDir.toFile());

                assertThat(result.isEmpty()).isTrue();
                assertThat(result.translatedForageKeys()).isEmpty();
                assertThat(result.warnings())
                        .containsExactly("forage.jdbc.* kept unchanged: Forage resolves the {{...}} placeholders of "
                                + "forage.jdbc.password, forage.jdbc.username at runtime");
            } finally {
                System.clearProperty("w106.db.password");
            }
        }

        @Test
        void quarkusExpressionsPassThrough() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.jdbc.db.kind=postgresql",
                    "forage.jdbc.url=jdbc:postgresql://${DB_HOST:localhost}:5432/mydb",
                    "forage.jdbc.username=admin",
                    "forage.jdbc.password=${DB_PASSWORD}");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            Map<String, String> qProps = flattenGroups(result);
            assertThat(qProps)
                    .containsEntry(
                            "quarkus.datasource.\"dataSource\".jdbc.url",
                            "jdbc:postgresql://${DB_HOST:localhost}:5432/mydb");
            assertThat(qProps).containsEntry("quarkus.datasource.\"dataSource\".password", "${DB_PASSWORD}");
        }

        @Test
        void overrideWhenExportingKeepsTheGroupUnchanged() throws IOException {
            System.setProperty("forage.jdbc.password", "from-the-exporting-shell");
            try {
                createPropertiesFile(
                        "application.properties",
                        "forage.jdbc.db.kind=postgresql",
                        "forage.jdbc.url=jdbc:postgresql://localhost:5432/mydb",
                        "forage.jdbc.username=admin");

                QuarkusPropertyTranslator.TranslationResult result =
                        QuarkusPropertyTranslator.translate(tempDir.toFile());

                assertThat(result.isEmpty()).isTrue();
                assertThat(result.warnings())
                        .containsExactly(
                                "forage.jdbc.* kept unchanged: overridden by forage.jdbc.password when exporting");
            } finally {
                System.clearProperty("forage.jdbc.password");
            }
        }

        @Test
        void defaultNextToNamedConfigurationsKeepsTheModule() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.jdbc.db.kind=postgresql",
                    "forage.jdbc.url=jdbc:postgresql://localhost/legacy",
                    "forage.jdbc.username=admin",
                    "forage.jdbc.password=secret",
                    "forage.ds1.jdbc.db.kind=postgresql",
                    "forage.ds1.jdbc.url=jdbc:postgresql://localhost/db1",
                    "forage.ds1.jdbc.username=user1",
                    "forage.ds1.jdbc.password=pass1");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.warnings())
                    .containsExactly("forage.jdbc.* and the named jdbc configurations kept unchanged: "
                            + "Forage ignores forage.jdbc.* when named configurations exist");
        }

        @Test
        void profileSpecificValuesKeepTheGroup() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.ds1.jdbc.db.kind=postgresql",
                    "forage.ds1.jdbc.url=jdbc:postgresql://prod-db/db1",
                    "forage.ds1.jdbc.username=user1",
                    "forage.ds1.jdbc.password=pass1",
                    "forage.ds2.jdbc.db.kind=mysql",
                    "forage.ds2.jdbc.url=jdbc:mysql://localhost/db2",
                    "forage.ds2.jdbc.username=user2",
                    "forage.ds2.jdbc.password=pass2",
                    "%prod.forage.ds2.jdbc.password=prod-pass2");
            createPropertiesFile("application-dev.properties", "forage.ds1.jdbc.url=jdbc:postgresql://localhost/db1");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.warnings())
                    .containsExactly(
                            "forage.ds1.jdbc.* kept unchanged: profile-specific values for forage.ds1.jdbc.url",
                            "forage.ds2.jdbc.* kept unchanged: profile-specific values for forage.ds2.jdbc.password");
        }

        @Test
        void valuesFromOutsideTheExportKeepTheGroup() throws IOException {
            Path configDir = Files.createDirectories(tempDir.resolve("config"));
            Files.write(
                    configDir.resolve("forage-datasource-factory.properties"), List.of("forage.jdbc.pool.max.size=99"));
            createPropertiesFile(
                    "application.properties",
                    "forage.jdbc.db.kind=postgresql",
                    "forage.jdbc.url=jdbc:postgresql://localhost:5432/mydb",
                    "forage.jdbc.username=admin",
                    "forage.jdbc.password=secret");
            System.setProperty("forage.config.dir", configDir.toString());
            try {
                QuarkusPropertyTranslator.TranslationResult result =
                        QuarkusPropertyTranslator.translate(tempDir.toFile());

                assertThat(result.isEmpty()).isTrue();
                assertThat(result.warnings())
                        .containsExactly("forage.jdbc.* kept unchanged: forage.jdbc.pool.max.size set outside the "
                                + "exported files");
            } finally {
                System.clearProperty("forage.config.dir");
            }
        }

        @Test
        void nonAsciiValuesKeepTheGroup() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.ds1.jdbc.db.kind=postgresql",
                    "forage.ds1.jdbc.url=jdbc:postgresql://localhost/db1",
                    "forage.ds1.jdbc.username=j\u00fcrgen",
                    "forage.ds1.jdbc.password=pass1");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.warnings())
                    .containsExactly(
                            "forage.ds1.jdbc.* kept unchanged: non-ASCII characters in forage.ds1.jdbc.username");
        }

        @Test
        void dottedNamedConfigurationKeepsTheDefault() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.jdbc.db.kind=postgresql",
                    "forage.jdbc.url=jdbc:postgresql://localhost/legacy",
                    "forage.jdbc.username=admin",
                    "forage.jdbc.password=secret",
                    "forage.orders.db.jdbc.db.kind=postgresql",
                    "forage.orders.db.jdbc.url=jdbc:postgresql://localhost/orders");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.warnings())
                    .containsExactly("forage.jdbc.* and the named jdbc configurations kept unchanged: "
                            + "Forage ignores forage.jdbc.* when named configurations exist");
        }

        @Test
        void blankValueFromOutsideTheExportIsIgnored() throws IOException {
            Path configDir = Files.createDirectories(tempDir.resolve("config"));
            Files.write(configDir.resolve("forage-datasource-factory.properties"), List.of("forage.jdbc.password="));
            createPropertiesFile(
                    "application.properties",
                    "forage.jdbc.db.kind=h2",
                    "forage.jdbc.url=jdbc:h2:mem:test",
                    "forage.jdbc.username=sa");
            System.setProperty("forage.config.dir", configDir.toString());
            try {
                QuarkusPropertyTranslator.TranslationResult result =
                        QuarkusPropertyTranslator.translate(tempDir.toFile());

                assertThat(result.warnings()).isEmpty();
                assertThat(flattenGroups(result))
                        .containsEntry("quarkus.datasource.\"dataSource\".db-kind", "h2")
                        .doesNotContainKey("quarkus.datasource.\"dataSource\".password");
            } finally {
                System.clearProperty("forage.config.dir");
            }
        }

        @Test
        void resolverValuesFromOutsideTheExportKeepTheGroup() throws IOException {
            // Driver properties are read through the resolvers, e.g. from another application.properties
            Path configDir = Files.createDirectories(tempDir.resolve("config"));
            Files.write(
                    configDir.resolve("application.properties"), List.of("forage.jdbc.properties.sslpassword=leaked"));
            createPropertiesFile(
                    "forage-datasource-factory.properties",
                    "forage.jdbc.db.kind=postgresql",
                    "forage.jdbc.url=jdbc:postgresql://localhost:5432/mydb",
                    "forage.jdbc.username=admin",
                    "forage.jdbc.password=secret");
            System.setProperty("forage.config.dir", configDir.toString());
            try {
                QuarkusPropertyTranslator.TranslationResult result =
                        QuarkusPropertyTranslator.translate(tempDir.toFile());

                assertThat(result.isEmpty()).isTrue();
                assertThat(result.warnings())
                        .containsExactly("forage.jdbc.* kept unchanged: forage.jdbc.properties.sslpassword set "
                                + "outside the exported files");
            } finally {
                System.clearProperty("forage.config.dir");
            }
        }

        @Test
        void defaultArtemisConnectionFactoryTranslated() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.jms.kind=artemis",
                    "forage.jms.broker.url=tcp://localhost:61616",
                    "forage.jms.username=admin",
                    "forage.jms.password=secret",
                    "forage.jms.pool.max.connections=5");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            Map<String, String> qProps = flattenGroups(result);
            assertThat(qProps).containsEntry("quarkus.artemis.url", "tcp://localhost:61616");
            assertThat(qProps).containsEntry("quarkus.artemis.username", "admin");
            assertThat(qProps).containsEntry("quarkus.artemis.password", "secret");
            assertThat(qProps).containsEntry("quarkus.pooled-jms.max-connections", "5");
            assertThat(result.warnings()).isEmpty();
        }

        @Test
        void namedJmsConnectionFactoryKept() throws IOException {
            // A named connection factory gets its own JMS component at runtime
            createPropertiesFile(
                    "application.properties",
                    "forage.mq1.jms.kind=artemis",
                    "forage.mq1.jms.broker.url=tcp://broker1:61616");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.translatedForageKeys()).isEmpty();
            assertThat(result.warnings())
                    .containsExactly("forage.mq1.jms.* kept unchanged: no 1:1 Quarkus property for "
                            + "forage.mq1.jms.broker.url, forage.mq1.jms.kind");
        }

        @Test
        void ibmMqConnectionFactoryKept() throws IOException {
            // The Forage extension creates the IBM MQ connection factory
            createPropertiesFile(
                    "application.properties", "forage.jms.kind=ibmmq", "forage.jms.broker.url=localhost(1414)");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.warnings())
                    .containsExactly("forage.jms.* kept unchanged: no 1:1 Quarkus property for "
                            + "forage.jms.broker.url, forage.jms.kind");
        }

        @Test
        void transactedJmsConnectionFactoryKept() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.jms.kind=artemis",
                    "forage.jms.broker.url=tcp://localhost:61616",
                    "forage.jms.transaction.enabled=true");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.warnings())
                    .containsExactly("forage.jms.* kept unchanged: no 1:1 Quarkus property for "
                            + "forage.jms.transaction.enabled");
        }

        @Test
        void failingTranslationKeepsTheGroup() throws IOException {
            // forage.jms.kind is required
            createPropertiesFile("application.properties", "forage.jms.broker.url=tcp://localhost:61616");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.warnings())
                    .containsExactly("forage.jms.* kept unchanged: JMS kind is required but not configured");
        }

        @Test
        void modulesWithoutQuarkusMappingAreLeftAlone() throws IOException {
            createPropertiesFile(
                    "application.properties",
                    "forage.agent.model.kind=ollama",
                    "forage.agent.base.url=http://localhost:11434",
                    "forage.agent.model.name=llama3",
                    "forage.cxf.kind=soap",
                    "forage.cxf.address=/hello",
                    "forage.rabbitmq.host=localhost",
                    "forage.influxdb.url=http://localhost:8086",
                    "forage.influxdb2.url=http://localhost:8087",
                    "forage.influxdb2.token=token",
                    "forage.tls.keystore.path=classpath:keystore.p12",
                    "forage.shiro.ini.resource.path=classpath:shiro.ini",
                    "forage.keycloak.server.url=http://localhost:8180",
                    "forage.route.policy.enabled=true",
                    "forage.tavily.api.key=key");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.translatedForageKeys()).isEmpty();
            assertThat(result.warnings()).isEmpty();
        }

        @Test
        void emptyDirectoryProducesEmptyResult() throws IOException {
            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.translatedForageKeys()).isEmpty();
        }

        @Test
        void nonForagePropertiesIgnored() throws IOException {
            createPropertiesFile(
                    "application.properties", "server.port=8080", "quarkus.datasource.url=jdbc:h2:mem:test");

            QuarkusPropertyTranslator.TranslationResult result = QuarkusPropertyTranslator.translate(tempDir.toFile());

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.translatedForageKeys()).isEmpty();
        }
    }

    private static Map<String, String> flattenGroups(QuarkusPropertyTranslator.TranslationResult result) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (QuarkusPropertyTranslator.TranslationGroup group : result.groups()) {
            flat.putAll(group.properties());
        }
        return flat;
    }

    private File createPropertiesFile(String filename, String... lines) throws IOException {
        File file = tempDir.resolve(filename).toFile();
        try (FileWriter writer = new FileWriter(file)) {
            for (String line : lines) {
                writer.write(line + "\n");
            }
        }
        return file;
    }
}
