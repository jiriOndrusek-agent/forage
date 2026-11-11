package io.kaoto.forage.plugin;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import io.kaoto.forage.core.util.config.ConfigStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class ForagePluginRewriteTest {

    @TempDir
    Path tempDir;

    @Test
    void forageKeysReplacedWithQuarkusProperties() throws IOException {
        Path appProps = writeProps(
                "# App config",
                "server.port=8080",
                "forage.jdbc.db.kind=postgresql",
                "forage.jdbc.url=jdbc:postgresql://localhost:5432/mydb",
                "forage.jdbc.username=admin",
                "forage.jdbc.password=secret",
                "camel.rest.port=9090");

        ForagePlugin.rewriteApplicationProperties(
                appProps,
                result(
                        group(
                                "jdbc",
                                null,
                                Map.of(
                                        "forage.jdbc.db.kind",
                                        "postgresql",
                                        "forage.jdbc.url",
                                        "jdbc:postgresql://localhost:5432/mydb",
                                        "forage.jdbc.username",
                                        "admin",
                                        "forage.jdbc.password",
                                        "secret"),
                                Map.of(
                                        "quarkus.datasource.\"dataSource\".db-kind",
                                        "postgresql",
                                        "quarkus.datasource.\"dataSource\".jdbc.url",
                                        "jdbc:postgresql://localhost:5432/mydb",
                                        "quarkus.datasource.\"dataSource\".username",
                                        "admin",
                                        "quarkus.datasource.\"dataSource\".password",
                                        "secret")),
                        Set.of(
                                "forage.jdbc.db.kind",
                                "forage.jdbc.url",
                                "forage.jdbc.username",
                                "forage.jdbc.password")));

        List<String> lines = Files.readAllLines(appProps);

        assertThat(lines).contains("# App config", "server.port=8080", "camel.rest.port=9090");
        assertThat(lines).noneMatch(l -> l.startsWith("forage."));
        assertThat(lines).noneMatch(l -> l.startsWith("quarkus."));
        assertThat(mpConfigLines()).contains("quarkus.datasource.\"dataSource\".db-kind=postgresql");
        assertThat(mpConfigLines()).contains("quarkus.datasource.\"dataSource\".username=admin");
    }

    @Test
    void namedPrefixesProduceGroupComments() throws IOException {
        Path appProps = writeProps("forage.ds1.jdbc.db.kind=postgresql", "forage.ds2.jdbc.db.kind=mysql");

        ForagePlugin.rewriteApplicationProperties(
                appProps,
                result(
                        List.of(
                                group(
                                        "jdbc",
                                        "ds1",
                                        Map.of("forage.ds1.jdbc.db.kind", "postgresql"),
                                        Map.of("quarkus.datasource.\"ds1\".db-kind", "postgresql")),
                                group(
                                        "jdbc",
                                        "ds2",
                                        Map.of("forage.ds2.jdbc.db.kind", "mysql"),
                                        Map.of("quarkus.datasource.\"ds2\".db-kind", "mysql"))),
                        Set.of("forage.ds1.jdbc.db.kind", "forage.ds2.jdbc.db.kind")));

        assertThat(mpConfigLines()).contains("# Properties for jdbc with prefix 'ds1'");
        assertThat(mpConfigLines()).contains("# Properties for jdbc with prefix 'ds2'");
        assertThat(Files.readAllLines(appProps)).noneMatch(l -> l.startsWith("forage."));
    }

    @Test
    void defaultPrefixCommentOmitsPrefixLabel() throws IOException {
        Path appProps = writeProps("forage.jdbc.db.kind=postgresql");

        ForagePlugin.rewriteApplicationProperties(
                appProps,
                result(
                        group(
                                "jdbc",
                                null,
                                Map.of("forage.jdbc.db.kind", "postgresql"),
                                Map.of("quarkus.datasource.\"dataSource\".db-kind", "postgresql")),
                        Set.of("forage.jdbc.db.kind")));

        List<String> lines = mpConfigLines();

        assertThat(lines).contains("# Properties for jdbc");
        assertThat(lines).noneMatch(l -> l.contains("with prefix"));
    }

    @Test
    void sourcePropertiesShownAsComments() throws IOException {
        Path appProps = writeProps("forage.jdbc.db.kind=postgresql", "forage.jdbc.username=admin");

        ForagePlugin.rewriteApplicationProperties(
                appProps,
                result(
                        group(
                                "jdbc",
                                null,
                                Map.of("forage.jdbc.db.kind", "postgresql", "forage.jdbc.username", "admin"),
                                Map.of(
                                        "quarkus.datasource.\"dataSource\".db-kind",
                                        "postgresql",
                                        "quarkus.datasource.\"dataSource\".username",
                                        "admin")),
                        Set.of("forage.jdbc.db.kind", "forage.jdbc.username")));

        List<String> lines = mpConfigLines();

        assertThat(lines).contains("# Translated from:");
        assertThat(lines).anyMatch(l -> l.equals("#   forage.jdbc.db.kind=postgresql"));
        assertThat(lines).anyMatch(l -> l.equals("#   forage.jdbc.username=admin"));
    }

    @Test
    void commentsAndBlankLinesPreserved() throws IOException {
        Path appProps = writeProps(
                "# My application", "", "! legacy comment", "forage.jdbc.db.kind=postgresql", "", "other.prop=value");

        ForagePlugin.rewriteApplicationProperties(
                appProps,
                result(
                        group(
                                "jdbc",
                                null,
                                Map.of("forage.jdbc.db.kind", "postgresql"),
                                Map.of("quarkus.datasource.\"dataSource\".db-kind", "postgresql")),
                        Set.of("forage.jdbc.db.kind")));

        List<String> lines = Files.readAllLines(appProps);

        assertThat(lines).contains("# My application", "", "! legacy comment", "other.prop=value");
        assertThat(lines).noneMatch(l -> l.equals("forage.jdbc.db.kind=postgresql"));
    }

    @Test
    void nonTranslatedForageKeysKept() throws IOException {
        Path appProps = writeProps("forage.jdbc.db.kind=postgresql", "forage.cxf.kind=soap");

        ForagePlugin.rewriteApplicationProperties(
                appProps,
                result(
                        group(
                                "jdbc",
                                null,
                                Map.of("forage.jdbc.db.kind", "postgresql"),
                                Map.of("quarkus.datasource.\"dataSource\".db-kind", "postgresql")),
                        Set.of("forage.jdbc.db.kind")));

        List<String> lines = Files.readAllLines(appProps);

        assertThat(lines).anyMatch(l -> l.equals("forage.cxf.kind=soap"));
        assertThat(lines).noneMatch(l -> l.equals("forage.jdbc.db.kind=postgresql"));
    }

    @Test
    void keptGroupStaysUnchanged() throws IOException {
        List<String> original = List.of(
                "forage.ds1.jdbc.db.kind=postgresql",
                "forage.ds1.jdbc.url=jdbc:postgresql://localhost:5432/mydb",
                "forage.ds1.jdbc.transaction.enabled=true",
                "forage.ds2.jdbc.db.kind=mysql",
                "forage.ds2.jdbc.url=jdbc:mysql://localhost/db2",
                "forage.ds2.jdbc.username=user2",
                "forage.ds2.jdbc.password=pass2");
        Path appProps = writeProps(original.toArray(String[]::new));

        QuarkusPropertyTranslator.TranslationResult result;
        try {
            result = QuarkusPropertyTranslator.translate(tempDir.toFile());
        } finally {
            ConfigStore.getInstance().reload();
        }
        ForagePlugin.rewriteApplicationProperties(appProps, result);

        List<String> lines = Files.readAllLines(appProps);
        assertThat(lines).containsSubsequence(original.subList(0, 3));
        assertThat(lines).noneMatch(l -> l.startsWith("forage.ds2."));
        assertThat(mpConfigLines()).contains("quarkus.datasource.\"ds2\".db-kind=mysql");
        assertThat(mpConfigLines()).noneMatch(l -> l.startsWith("quarkus.datasource.\"ds1\"."));
    }

    @Test
    void placeholdersComeThroughUnchanged() throws IOException {
        List<String> original = List.of(
                "forage.jdbc.db.kind=postgresql",
                "forage.jdbc.url=jdbc:postgresql://localhost:5432/mydb",
                "forage.jdbc.username={{env:W106_DB_USER:admin}}",
                "forage.jdbc.password={{sys:w106.db.password}}");
        Path appProps = writeProps(original.toArray(String[]::new));

        System.setProperty("w106.db.password", "resolved-secret");
        QuarkusPropertyTranslator.TranslationResult result;
        try {
            result = QuarkusPropertyTranslator.translate(tempDir.toFile());
        } finally {
            System.clearProperty("w106.db.password");
            ConfigStore.getInstance().reload();
        }
        ForagePlugin.rewriteApplicationProperties(appProps, result);

        List<String> lines = Files.readAllLines(appProps);
        assertThat(lines).isEqualTo(original);
        assertThat(lines).noneMatch(l -> l.contains("resolved-secret") || l.endsWith("=admin"));
    }

    @Test
    void translatedKeysRemovedFromOtherPropertiesFiles() throws IOException {
        Path appProps = writeProps("camel.main.name=demo");
        Path forageProps = tempDir.resolve("forage-datasource-factory.properties");
        Files.write(
                forageProps,
                List.of(
                        "# datasources",
                        "forage.ds1.jdbc.db.kind=postgresql",
                        "forage.ds1.jdbc.url=jdbc:postgresql://localhost/db1",
                        "forage.ds1.jdbc.username=user1",
                        "forage.ds1.jdbc.password=pass1",
                        "forage.ds2.jdbc.db.kind=mysql",
                        "forage.ds2.jdbc.url=jdbc:mysql://localhost/db2",
                        "forage.ds2.jdbc.username=user2",
                        "forage.ds2.jdbc.password=pass2",
                        "forage.ds2.jdbc.transaction.enabled=true"));
        Path otherProps = tempDir.resolve("messages.properties");
        Files.write(otherProps, List.of("greeting=Hello"));

        QuarkusPropertyTranslator.TranslationResult result;
        try {
            result = QuarkusPropertyTranslator.translate(tempDir.toFile());
        } finally {
            ConfigStore.getInstance().reload();
        }
        ForagePlugin.rewriteApplicationProperties(appProps, result);

        assertThat(Files.readAllLines(forageProps))
                .containsExactly(
                        "# datasources",
                        "forage.ds2.jdbc.db.kind=mysql",
                        "forage.ds2.jdbc.url=jdbc:mysql://localhost/db2",
                        "forage.ds2.jdbc.username=user2",
                        "forage.ds2.jdbc.password=pass2",
                        "forage.ds2.jdbc.transaction.enabled=true");
        assertThat(Files.readAllLines(appProps)).containsExactly("camel.main.name=demo");
        assertThat(mpConfigLines())
                .contains("quarkus.datasource.\"ds1\".db-kind=postgresql")
                .noneMatch(l -> l.contains("\"ds2\""));
        assertThat(Files.readAllLines(otherProps)).containsExactly("greeting=Hello");
    }

    @Test
    void exportedPropertiesAreTranslated() throws IOException {
        // camel export writes --property values into the exported application.properties only
        Path resources = Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path appProps = resources.resolve("application.properties");
        Files.write(
                appProps,
                List.of(
                        "forage.jdbc.db.kind=postgresql",
                        "forage.jdbc.url=jdbc:postgresql://localhost:5432/mydb",
                        "forage.jdbc.username=admin",
                        "forage.jdbc.password=secret",
                        "forage.jdbc.pool.max.size=50"));

        ForagePlugin.translateForageProperties(tempDir);

        List<String> lines = Files.readAllLines(appProps);
        assertThat(lines).noneMatch(l -> l.startsWith("forage."));
        assertThat(Files.readAllLines(resources.resolve(ForagePlugin.MP_CONFIG_PROPERTIES)))
                .contains("quarkus.datasource.\"dataSource\".jdbc.max-size=50");
    }

    @Test
    void applicationPropertiesOverrideForageFiles() throws IOException {
        Path appProps = writeProps("forage.ds1.jdbc.url=jdbc:postgresql://from-application/db");
        Files.write(
                tempDir.resolve("forage-datasource-factory.properties"),
                List.of(
                        "forage.ds1.jdbc.db.kind=postgresql",
                        "forage.ds1.jdbc.url=jdbc:postgresql://from-forage-file/db",
                        "forage.ds1.jdbc.username=user1",
                        "forage.ds1.jdbc.password=pass1"));

        QuarkusPropertyTranslator.TranslationResult result;
        try {
            result = QuarkusPropertyTranslator.translate(tempDir.toFile());
        } finally {
            ConfigStore.getInstance().reload();
        }
        ForagePlugin.rewriteApplicationProperties(appProps, result);

        assertThat(mpConfigLines())
                .contains("quarkus.datasource.\"ds1\".jdbc.url=jdbc:postgresql://from-application/db");
    }

    @Test
    void propertiesWithOtherSeparatorsAndContinuationLinesRemoved() throws IOException {
        Path appProps = writeProps("camel.main.name=demo");
        Path forageProps = tempDir.resolve("forage-datasource-factory.properties");
        Files.write(
                forageProps,
                List.of(
                        "forage.ds1.jdbc.db.kind: postgresql",
                        "  forage.ds1.jdbc.url = jdbc:postgresql://localhost/\\",
                        "      db1",
                        "forage.ds1.jdbc.username user1",
                        "forage.ds1.jdbc.password=pass1",
                        "# forage.ds1.jdbc.url=commented out",
                        "other.key=value"));

        QuarkusPropertyTranslator.TranslationResult result;
        try {
            result = QuarkusPropertyTranslator.translate(tempDir.toFile());
        } finally {
            ConfigStore.getInstance().reload();
        }
        ForagePlugin.rewriteApplicationProperties(appProps, result);

        assertThat(Files.readAllLines(forageProps))
                .containsExactly("# forage.ds1.jdbc.url=commented out", "other.key=value");
        assertThat(mpConfigLines()).contains("quarkus.datasource.\"ds1\".jdbc.url=jdbc:postgresql://localhost/db1");
    }

    @Test
    void translatedValuesAreEscaped() throws IOException {
        Path appProps = writeProps(
                "forage.ds1.jdbc.db.kind=postgresql",
                "forage.ds1.jdbc.url=jdbc:postgresql://localhost/db1",
                "forage.ds1.jdbc.username=\\ user1",
                "forage.ds1.jdbc.password=pa\\\\ss");

        QuarkusPropertyTranslator.TranslationResult result;
        try {
            result = QuarkusPropertyTranslator.translate(tempDir.toFile());
        } finally {
            ConfigStore.getInstance().reload();
        }
        ForagePlugin.rewriteApplicationProperties(appProps, result);

        Properties exported = new Properties();
        try (Reader reader = Files.newBufferedReader(tempDir.resolve(ForagePlugin.MP_CONFIG_PROPERTIES))) {
            exported.load(reader);
        }
        assertThat(exported.getProperty("quarkus.datasource.\"ds1\".password")).isEqualTo("pa\\ss");
        assertThat(exported.getProperty("quarkus.datasource.\"ds1\".username")).isEqualTo(" user1");
    }

    @Test
    void translationNeverFailsTheExport() throws IOException {
        Path resources = Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path appProps = resources.resolve("application.properties");
        // java.util.Properties cannot read "\\u" followed by a non-hexadecimal character
        List<String> original = List.of(
                "my.backup.dir=C:\\users\\me",
                "forage.jdbc.db.kind=postgresql",
                "forage.jdbc.url=jdbc:postgresql://localhost:5432/mydb",
                "forage.jdbc.username=admin",
                "forage.jdbc.password=secret");
        Files.write(appProps, original);

        ForagePlugin.translateForageProperties(tempDir);

        assertThat(Files.readAllLines(appProps)).isEqualTo(original);
        assertThat(resources.resolve(ForagePlugin.MP_CONFIG_PROPERTIES)).doesNotExist();
    }

    @Test
    void rewriteKeepsTheBytesOfTheOtherLines() throws IOException {
        Path appProps = writeProps("camel.main.name=demo");
        Path forageProps = tempDir.resolve("forage-datasource-factory.properties");
        Files.write(
                forageProps,
                List.of(
                        "# caf\u00e9 datasource",
                        "forage.ds1.jdbc.db.kind=postgresql",
                        "forage.ds1.jdbc.url=jdbc:postgresql://localhost/db1",
                        "forage.ds1.jdbc.username=user1",
                        "forage.ds1.jdbc.password=pass1"),
                StandardCharsets.ISO_8859_1);

        QuarkusPropertyTranslator.TranslationResult result;
        try {
            result = QuarkusPropertyTranslator.translate(tempDir.toFile());
        } finally {
            ConfigStore.getInstance().reload();
        }
        ForagePlugin.rewriteApplicationProperties(appProps, result);

        assertThat(Files.readAllBytes(forageProps))
                .isEqualTo(("# caf\u00e9 datasource" + System.lineSeparator()).getBytes(StandardCharsets.ISO_8859_1));
        assertThat(mpConfigLines()).contains("quarkus.datasource.\"ds1\".db-kind=postgresql");
    }

    @Test
    void keyOutsideLatin1IsWrittenEscaped() throws IOException {
        Path appProps = writeProps(
                "forage.ds1.jdbc.db.kind=postgresql",
                "forage.ds1.jdbc.url=jdbc:postgresql://localhost/db1",
                "forage.ds1.jdbc.username=user1",
                "forage.ds1.jdbc.password=pass1",
                "forage.ds1.jdbc.properties.price\\u20AC=1");

        QuarkusPropertyTranslator.TranslationResult result;
        try {
            result = QuarkusPropertyTranslator.translate(tempDir.toFile());
        } finally {
            ConfigStore.getInstance().reload();
        }
        ForagePlugin.rewriteApplicationProperties(appProps, result);

        Properties exported = new Properties();
        try (Reader reader = Files.newBufferedReader(tempDir.resolve(ForagePlugin.MP_CONFIG_PROPERTIES))) {
            exported.load(reader);
        }
        assertThat(exported.getProperty("quarkus.datasource.\"ds1\".jdbc.additional-jdbc-properties.\"price\u20AC\""))
                .isEqualTo("1");
    }

    @Test
    void userQuarkusPropertiesAreKept() throws IOException {
        // application.properties (ordinal 250) overrides microprofile-config.properties (ordinal 100)
        Path appProps = writeProps("quarkus.datasource.\"ds1\".jdbc.max-size=50");
        Files.write(
                tempDir.resolve("forage-datasource-factory.properties"),
                List.of(
                        "forage.ds1.jdbc.db.kind=postgresql",
                        "forage.ds1.jdbc.url=jdbc:postgresql://localhost/db1",
                        "forage.ds1.jdbc.username=user1",
                        "forage.ds1.jdbc.password=pass1"));

        QuarkusPropertyTranslator.TranslationResult result;
        try {
            result = QuarkusPropertyTranslator.translate(tempDir.toFile());
        } finally {
            ConfigStore.getInstance().reload();
        }
        ForagePlugin.rewriteApplicationProperties(appProps, result);

        assertThat(Files.readAllLines(appProps)).containsExactly("quarkus.datasource.\"ds1\".jdbc.max-size=50");
        assertThat(mpConfigLines()).contains("quarkus.datasource.\"ds1\".jdbc.max-size=20");
    }

    @Test
    void existingMicroprofileConfigIsKept() throws IOException {
        Path appProps = writeProps("forage.jdbc.db.kind=postgresql");
        Path mpConfig = Files.createDirectories(tempDir.resolve("META-INF")).resolve("microprofile-config.properties");
        Files.write(mpConfig, List.of("my.key=value"));

        ForagePlugin.rewriteApplicationProperties(
                appProps,
                result(
                        group(
                                "jdbc",
                                null,
                                Map.of("forage.jdbc.db.kind", "postgresql"),
                                Map.of("quarkus.datasource.\"dataSource\".db-kind", "postgresql")),
                        Set.of("forage.jdbc.db.kind")));

        assertThat(mpConfigLines())
                .startsWith("my.key=value")
                .contains("quarkus.datasource.\"dataSource\".db-kind=postgresql");
    }

    private List<String> mpConfigLines() throws IOException {
        return Files.readAllLines(tempDir.resolve(ForagePlugin.MP_CONFIG_PROPERTIES));
    }

    private Path writeProps(String... lines) throws IOException {
        Path path = tempDir.resolve("application.properties");
        Files.write(path, List.of(lines));
        return path;
    }

    private static QuarkusPropertyTranslator.TranslationGroup group(
            String moduleType, String prefix, Map<String, String> source, Map<String, String> translated) {
        return new QuarkusPropertyTranslator.TranslationGroup(
                moduleType, prefix, new LinkedHashMap<>(source), new LinkedHashMap<>(translated));
    }

    private static QuarkusPropertyTranslator.TranslationResult result(
            QuarkusPropertyTranslator.TranslationGroup group, Set<String> translatedKeys) {
        return result(List.of(group), translatedKeys);
    }

    private static QuarkusPropertyTranslator.TranslationResult result(
            List<QuarkusPropertyTranslator.TranslationGroup> groups, Set<String> translatedKeys) {
        return new QuarkusPropertyTranslator.TranslationResult(groups, new LinkedHashSet<>(translatedKeys), List.of());
    }
}
