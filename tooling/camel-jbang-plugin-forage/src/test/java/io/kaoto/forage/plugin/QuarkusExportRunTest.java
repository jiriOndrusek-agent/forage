package io.kaoto.forage.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import io.kaoto.forage.core.common.RuntimeType;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code camel export --runtime=quarkus}: the silent run translates the project directory, Camel JBang then writes the
 * exported {@code application.properties} as {@code key=value} without escaping, and the export call writes the run's
 * translation. Every value is either kept exactly or the export fails.
 */
class QuarkusExportRunTest {

    private static final String MODULE = "forage-datasource-factory.properties";
    private static final String PASSWORD = "forage.ds1.jdbc.password";
    private static final String QUARKUS_PASSWORD = "quarkus.datasource.\"ds1\".password";
    private static final String DS1 =
            """
            forage.ds1.jdbc.db.kind=postgresql
            forage.ds1.jdbc.url=jdbc:postgresql://localhost:5432/db
            forage.ds1.jdbc.username=test
            """;
    /** Keeps the datasource on forage.* */
    private static final String TRANSACTIONS = "forage.ds1.jdbc.transaction.enabled=true\n";

    @TempDir
    Path project;

    @TempDir
    Path resources;

    @BeforeEach
    void setUp() {
        System.setProperty("forage.config.dir", project.toString());
        clearRunData();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("forage.config.dir");
        clearRunData();
        ConfigStore.getInstance().reload();
    }

    private static final List<String> MODES =
            List.of("app", "app-forage", "prop", "prop-forage", "file", "file-forage");
    private static final Set<String> APP_PROP = Set.of("app", "app-forage", "prop", "prop-forage");
    private static final Set<String> FORAGE = Set.of("app-forage", "prop-forage", "file-forage");
    private static final String ESCAPING = "without escaping";
    private static final String EMPTY = "reads the empty value of " + PASSWORD + " as not set";
    private static final String EXPANDS = "Quarkus would read " + PASSWORD + " differently";

    /**
     * The value as written in a properties file, and the modes where the export must fail, with the failure: the
     * value in application.properties, --property or the module file, translated or kept on forage.*. Everywhere else
     * the exported app reads the very value.
     */
    static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of("backslash", "a\\\\b", failing(APP_PROP, ESCAPING)),
                Arguments.of("leadingSpace", "\\ x", failing(APP_PROP, ESCAPING)),
                Arguments.of("trailingSpace", "x ", Map.of()),
                Arguments.of("newline", "a\\nb", failing(APP_PROP, ESCAPING)),
                Arguments.of("equalsColon", "a=b:c", Map.of()),
                Arguments.of("hash", "#x", Map.of()),
                Arguments.of("bang", "!x", Map.of()),
                // Quarkus reads an empty value as not set
                Arguments.of("empty", "", failing(Set.copyOf(MODES), EMPTY)),
                Arguments.of("latin1", "päss", Map.of()),
                // SmallRye expands these: the Quarkus block escapes them, forage.* fails (the extension expands)
                Arguments.of("expression", "x${user.home}y", failing(FORAGE, EXPANDS)),
                Arguments.of("escapedExpression", "x$${user.home}y", failing(FORAGE, EXPANDS)),
                Arguments.of("nestedExpression", "x${a${user.home}}", failing(FORAGE, EXPANDS)),
                Arguments.of("unterminatedExpression", "x${user.home", failing(FORAGE, EXPANDS)),
                Arguments.of("doubleDollar", "a$$b", failing(FORAGE, EXPANDS)),
                Arguments.of(
                        "backslashDollar",
                        "a\\\\$b",
                        Map.of(
                                "app", ESCAPING,
                                "prop", ESCAPING,
                                "app-forage", EXPANDS,
                                "prop-forage", EXPANDS,
                                "file-forage", EXPANDS)),
                Arguments.of("dollar", "a$b$}c$", Map.of()));
    }

    private static Map<String, String> failing(Set<String> modes, String message) {
        Map<String, String> failing = new LinkedHashMap<>();
        modes.forEach(mode -> failing.put(mode, message));
        return failing;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    void keepsTheValueOrFails(String name, String text, Map<String, String> failing) throws IOException {
        String value = properties(PASSWORD + "=" + text).getProperty(PASSWORD);
        for (String mode : MODES) {
            boolean forage = FORAGE.contains(mode);
            clean();
            String module =
                    DS1 + (forage ? TRANSACTIONS : "") + (mode.startsWith("file") ? PASSWORD + "=" + text + "\n" : "");
            Files.writeString(project.resolve(MODULE), module, StandardCharsets.ISO_8859_1);
            Map<String, String> runProperties = new LinkedHashMap<>();
            String application = "camel.main.name=demo\n";
            if (mode.startsWith("app")) {
                application += PASSWORD + "=" + text + "\n";
            } else if (mode.startsWith("prop")) {
                runProperties.put(PASSWORD, value);
            }
            Files.writeString(project.resolve("application.properties"), application, StandardCharsets.ISO_8859_1);

            if (failing.containsKey(mode)) {
                assertThatThrownBy(() -> runAndExport(runProperties))
                        .as(mode)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(failing.get(mode));
            } else {
                runAndExport(runProperties);
                if (forage) {
                    assertThat(forageReads().getProperty(PASSWORD)).as(mode).isEqualTo(value);
                } else {
                    assertThat(quarkusReads(QUARKUS_PASSWORD)).as(mode).isEqualTo(value);
                    assertThat(forageReads()).as(mode).doesNotContainKey(PASSWORD);
                }
            }
            assertRunDataCleared();
        }
    }

    /**
     * The same key in two or three profiles, one of them with a value the export cannot keep, in each position, in
     * application.properties or with --property: no profile value may hide another. The module file holds the base
     * value.
     */
    static Stream<Arguments> profileValues() {
        Map<String, String> bad = Map.of(
                "expression", "a${user.home}",
                "doubleDollar", "a$$b",
                "empty", "",
                "backslash", "a\\\\b",
                "leadingSpace", "\\ x");
        Map<String, String> messages = Map.of(
                "expression", "Quarkus would read %s differently",
                "doubleDollar", "Quarkus would read %s differently",
                "empty", "reads the empty value of %s as not set",
                "backslash", "changes %s: expected",
                "leadingSpace", "changes %s: expected");
        Stream.Builder<Arguments> cases = Stream.builder();
        for (List<String> profiles : List.of(List.of("dev", "prod"), List.of("test", "prod", "dev"))) {
            for (int position = 0; position < profiles.size(); position++) {
                for (String kind : List.of("expression", "doubleDollar", "empty", "backslash", "leadingSpace")) {
                    for (String where : List.of("app", "prop")) {
                        String key = "%" + profiles.get(position) + "." + PASSWORD;
                        cases.add(Arguments.of(
                                profiles,
                                position,
                                where,
                                bad.get(kind),
                                messages.get(kind).formatted(key)));
                    }
                }
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} [{1}] {2}: {3}")
    @MethodSource("profileValues")
    void failsOnAnyProfileValueItCannotKeep(
            List<String> profiles, int position, String where, String text, String message) throws IOException {
        Files.writeString(project.resolve(MODULE), DS1 + PASSWORD + "=base\n", StandardCharsets.ISO_8859_1);
        StringBuilder application = new StringBuilder("camel.main.name=demo\n");
        Map<String, String> runProperties = new LinkedHashMap<>();
        for (int i = 0; i < profiles.size(); i++) {
            String key = "%" + profiles.get(i) + "." + PASSWORD;
            if (i != position) {
                application.append(key).append("=ok").append(i).append('\n');
            } else if (where.equals("app")) {
                application.append(key).append('=').append(text).append('\n');
            } else {
                runProperties.put(key, properties(key + "=" + text).getProperty(key));
            }
        }
        Files.writeString(
                project.resolve("application.properties"), application.toString(), StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> runAndExport(runProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
        assertRunDataCleared();
    }

    /** Camel JBang does not export application-<profile>.properties: its values would be lost. */
    @Test
    void failsOnAProfileFileValue() throws IOException {
        Files.writeString(project.resolve(MODULE), DS1 + PASSWORD + "=base\n");
        Files.writeString(project.resolve("application.properties"), "camel.main.name=demo\n");
        Files.writeString(project.resolve("application-prod.properties"), PASSWORD + "=q\n");

        assertThatThrownBy(() -> runAndExport(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changes %prod." + PASSWORD + ": expected 'q', found no value")
                .hasMessageContaining("Set %prod." + PASSWORD + " in application.properties");
    }

    /** Values every profile keeps: each profile of the exported app reads what the Forage runtime reads. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("keptProfileValues")
    void keepsEveryProfileValue(String name, String module, String application, Map<String, String> runProperties)
            throws IOException {
        Files.writeString(project.resolve(MODULE), DS1 + module, StandardCharsets.ISO_8859_1);
        Files.writeString(project.resolve("application.properties"), application, StandardCharsets.ISO_8859_1);
        // the Forage runtime: the module file, then application.properties; profile keys only from the latter
        Properties profiled = properties(application);
        profiled.putAll(runProperties);
        Properties original = properties(DS1 + module);
        original.putAll(profiled);

        runAndExport(runProperties);

        for (String profile : List.of("dev", "test", "prod", "custom")) {
            String expected = profiled.getProperty("%" + profile + "." + PASSWORD, original.getProperty(PASSWORD));
            assertThat(exportedReads(profile)).as(profile).isEqualTo(expected);
        }
    }

    static Stream<Arguments> keptProfileValues() {
        String base = PASSWORD + "=base\n";
        return Stream.of(
                Arguments.of(
                        "twoProfiles", base, "%dev." + PASSWORD + "=a$b$}c\n%prod." + PASSWORD + "=x \n", Map.of()),
                Arguments.of(
                        "threeProfilesAndProperty",
                        base,
                        "%test." + PASSWORD + "=t\n%dev." + PASSWORD + "=d\n",
                        Map.of("%prod." + PASSWORD, "p")),
                // the module file is not a Quarkus source: the Forage runtime and Quarkus ignore its profile keys
                Arguments.of(
                        "moduleFileProfileKeys",
                        base + "%prod." + PASSWORD + "=a${user.home}\n%dev." + PASSWORD + "=\n",
                        "",
                        Map.of()));
    }

    /**
     * A value of the exported app as Quarkus reads it with a profile: SmallRye expands the exported
     * application.properties; the Forage Quarkus extension reads the module file as written.
     */
    private String exportedReads(String profile) throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withProfile(profile)
                .withSources(new PropertiesConfigSource(quarkus(), "application.properties", 250))
                .build();
        String value = config.getConfigValue(QUARKUS_PASSWORD).getValue();
        if (value == null) {
            value = config.getConfigValue(PASSWORD).getValue();
        }
        if (value == null && Files.exists(resources.resolve(MODULE))) {
            Properties module = new Properties();
            try (InputStream in = Files.newInputStream(resources.resolve(MODULE))) {
                module.load(in);
            }
            value = module.getProperty(PASSWORD);
        }
        return value;
    }

    /** The silent run, Camel JBang writing the exported resources, then the export call. */
    private void runAndExport(Map<String, String> runProperties) throws IOException {
        ForagePlugin.runStarted(runProperties);
        try {
            ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);
        } catch (IllegalStateException e) {
            // Camel JBang stops when the run fails: the export call never comes
            clearRunData();
            throw e;
        }
        camelExport(runProperties);
        ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);
    }

    @Test
    void failsOnAForageKeyTheRunDidNotRead() throws IOException {
        QuarkusConfigExport.Run run = QuarkusConfigExport.run(project, Map.of());
        Files.writeString(resources.resolve("application.properties"), "forage.ds1.jdbc.url=x\n");

        assertThatThrownBy(() -> QuarkusConfigExport.checkExported(run, resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changes forage.ds1.jdbc.url: expected no value, found 'x'");
    }

    /** Camel JBang drops blank values: only a blank, non-empty translated value is written from the run. */
    @Test
    void failsOnADroppedEmptyTranslatedValue() throws IOException {
        QuarkusConfigExport.Result result = new QuarkusConfigExport.Result(
                Map.of(QUARKUS_PASSWORD, " "), Set.of(PASSWORD), Set.of("jdbc"), Set.of(), Set.of(), List.of());
        Files.writeString(resources.resolve("application.properties"), "camel.main.name=demo\n");

        QuarkusConfigExport.checkExported(new QuarkusConfigExport.Run(result, Map.of(PASSWORD, " ")), resources);
        assertThatThrownBy(() -> QuarkusConfigExport.checkExported(
                        new QuarkusConfigExport.Run(result, Map.of(PASSWORD, "")), resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changes " + PASSWORD + ": expected '', found no value");
    }

    @Test
    void comparesProfileSpecificKeys() throws IOException {
        Files.writeString(project.resolve("application.properties"), "%dev.forage.ds1.jdbc.url=a\\\\b\n");
        QuarkusConfigExport.Run run = QuarkusConfigExport.run(project, Map.of());
        Files.writeString(resources.resolve("application.properties"), "%dev.forage.ds1.jdbc.url=a\\b\n");

        assertThatThrownBy(() -> QuarkusConfigExport.checkExported(run, resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changes %dev.forage.ds1.jdbc.url: expected 'a\\\\b', found 'ab'");
    }

    @Test
    void writesTheRunTranslation() throws IOException {
        Files.writeString(project.resolve(MODULE), DS1 + PASSWORD + "=secret\n");
        Files.writeString(project.resolve("application.properties"), "");
        Map<String, String> runProperties = Map.of("forage.ds1.jdbc.url", "jdbc:postgresql://cli/db");

        ForagePlugin.runStarted(runProperties);
        ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);
        camelExport(runProperties);
        // the export translates what the run read, not the exported files
        Files.writeString(resources.resolve(MODULE), DS1.replace("test", "changed"));
        Set<String> export = ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);

        assertThat(export).noneMatch(d -> d.contains("io.kaoto.forage"));
        assertThat(quarkus())
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.url", "jdbc:postgresql://cli/db")
                .containsEntry("quarkus.datasource.\"ds1\".username", "test")
                .doesNotContainKey("forage.ds1.jdbc.url");
        assertThat(ForagePlugin.exportQuarkusProperties(resources.resolve("missing"), null))
                .isNull();
    }

    @Test
    void failsWhenTheExportReusesEarlierRunDataWithForageKeys() throws IOException {
        // stays forage.*: nothing translated, but the exported value cannot be checked
        Files.writeString(resources.resolve("application.properties"), DS1 + TRANSACTIONS);

        assertThatThrownBy(() -> ForagePlugin.dependencies(RuntimeType.QUARKUS, resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--fresh");
    }

    @Test
    void exportWithoutForageKeysNeedsNoRunData() throws IOException {
        Files.writeString(resources.resolve("application.properties"), "camel.main.name=demo\n");

        assertThat(ForagePlugin.dependencies(RuntimeType.QUARKUS, resources)).isEmpty();
    }

    @Test
    void encodesTheRun() {
        QuarkusConfigExport.Result result = new QuarkusConfigExport.Result(
                Map.of("quarkus.datasource.\"a b\".password", " \\x\n=:#!\u00e4\ud83d\ude00 "),
                Set.of("forage.a b.jdbc.password", "forage.x.jdbc.url"),
                Set.of("jdbc", "jms"),
                Set.of("pooled"),
                Set.of(MODULE),
                List.of("second\nline", "first", "third"));
        QuarkusConfigExport.Run run =
                new QuarkusConfigExport.Run(result, Map.of("forage.a b.jdbc.password", "", "%p.forage.x", "=\\"));

        QuarkusConfigExport.Run decoded = QuarkusConfigExport.decode(QuarkusConfigExport.encode(run));

        assertThat(decoded.application()).isEqualTo(run.application());
        assertThat(decoded.result().properties()).isEqualTo(result.properties());
        assertThat(decoded.result().forageKeys()).isEqualTo(result.forageKeys());
        assertThat(decoded.result().modules()).isEqualTo(result.modules());
        assertThat(decoded.result().groups()).isEqualTo(result.groups());
        assertThat(decoded.result().files()).isEqualTo(result.files());
        assertThat(decoded.result().messages()).isEqualTo(result.messages());
    }

    /**
     * What Camel JBang 4.22 exports ({@code ExportBaseCommand.copySettingsAndProfile}): the project
     * application.properties as read (ISO-8859-1) without blank values, then the --property values it does not hold,
     * each as {@code key=value}, written as UTF-8. The module file is copied as it is.
     */
    private void camelExport(Map<String, String> runProperties) throws IOException {
        Properties profile = new Properties();
        try (InputStream in = Files.newInputStream(project.resolve("application.properties"))) {
            profile.load(in);
        }
        StringBuilder content = new StringBuilder();
        profile.stringPropertyNames().stream()
                .filter(key -> !profile.getProperty(key).isBlank())
                .forEach(key -> content.append(key)
                        .append('=')
                        .append(profile.getProperty(key))
                        .append('\n'));
        runProperties.forEach((key, value) -> {
            if (profile.get(key) == null) {
                content.append(key).append('=').append(value).append('\n');
            }
        });
        Files.writeString(resources.resolve("application.properties"), content.toString(), StandardCharsets.UTF_8);
        Files.copy(project.resolve(MODULE), resources.resolve(MODULE));
    }

    /** The exported application.properties, as Quarkus reads it (UTF-8). */
    private Properties quarkus() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(resources.resolve("application.properties"))) {
            properties.load(reader);
        }
        return properties;
    }

    /** A value of the exported application.properties as Quarkus reads it: SmallRye expands expressions. */
    private String quarkusReads(String key) throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .addSystemSources()
                .withSources(new PropertiesConfigSource(quarkus(), "application.properties", 250))
                .build();
        return config.getConfigValue(key).getValue();
    }

    /** The exported forage.* values as the Forage runtime reads them: the module file, then application.properties. */
    private Properties forageReads() throws IOException {
        Properties properties = new Properties();
        Path module = resources.resolve(MODULE);
        if (Files.exists(module)) {
            try (InputStream in = Files.newInputStream(module)) {
                properties.load(in);
            }
        }
        properties.putAll(quarkus());
        return properties;
    }

    private void clean() throws IOException {
        for (Path dir : List.of(project, resources)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        ConfigStore.getInstance().reload();
    }

    private static void assertRunDataCleared() {
        assertThat(System.getProperty(ForagePlugin.RUN_TRANSLATION)).isNull();
        assertThat(System.getProperty(ForagePlugin.RUN_DEPENDENCIES)).isNull();
    }

    private static void clearRunData() {
        System.clearProperty(ForagePlugin.RUN_TRANSLATION);
        System.clearProperty(ForagePlugin.RUN_DEPENDENCIES);
    }

    private static Properties properties(String content) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return properties;
    }
}
