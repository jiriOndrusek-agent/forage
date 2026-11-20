package io.kaoto.forage.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.RuntimeType;
import io.kaoto.forage.core.util.config.Config;
import io.kaoto.forage.core.util.config.ConfigHelper;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.core.util.config.PropertyFileLocator;
import io.kaoto.forage.jdbc.common.JdbcModuleDescriptor;
import io.kaoto.forage.jms.common.JmsModuleDescriptor;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code camel export --runtime=quarkus}: the silent run translates the project directory with the {@code --property}
 * values, Camel JBang writes the exported {@code application.properties} without escaping, then the export call writes
 * the translation. The exported app reads every value as an export from {@code main} (without translation), or the
 * export fails.
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
    private static final String SKIP = "forage.quarkus.translation.skip";
    /** Keeps the datasource on forage.* (an XA datasource) */
    private static final String TRANSACTIONS = "forage.ds1.jdbc.transaction.enabled=true\n";

    @TempDir
    Path project;

    @TempDir
    Path resources;

    /** The exported resources before the export call: what an export from {@code main} ships. */
    @TempDir
    Path main;

    @BeforeEach
    void setUp() {
        System.setProperty("forage.config.dir", project.toString());
        System.clearProperty(ForagePlugin.RUN_PROPERTIES_KEY);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("forage.config.dir");
        System.clearProperty(SKIP);
        System.clearProperty(ForagePlugin.RUN_PROPERTIES_KEY);
        ConfigStore.getInstance().reload();
    }

    /**
     * The value (as written in a properties file) in application.properties, with --property or in the module file
     * ({@code app}, {@code prop}, {@code file}), translated or kept on forage.* ({@code -forage}): the exported app
     * reads the value an export from {@code main} reads; in the listed modes the module stays forage.* as in
     * {@code main} (Camel JBang changes the value, or it cannot be written inside an expression).
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "a\\\\b | app,prop",
                // Camel JBang drops blank values: the exported app does not read them
                "'\\ ' | ",
                "'' | ",
                "x${user.home}y | app,prop,file",
                "x${x:dflt}y | app,prop,file",
                "a$$b | ",
                "päss | ",
                "'a=b:c #x! $y ' | "
            })
    void keepsTheValue(String text, String forageModes) throws IOException {
        String value = properties(PASSWORD + "=" + text).getProperty(PASSWORD);
        for (String mode : List.of("app", "prop", "file", "app-forage", "prop-forage", "file-forage")) {
            boolean forage = mode.endsWith("-forage")
                    || (forageModes != null && Set.of(forageModes.split(",")).contains(mode));
            clean();
            String line = PASSWORD + "=" + text + "\n";
            Files.writeString(
                    project.resolve(MODULE),
                    DS1 + (mode.endsWith("-forage") ? TRANSACTIONS : "") + (mode.startsWith("file") ? line : ""),
                    StandardCharsets.ISO_8859_1);
            Files.writeString(
                    project.resolve("application.properties"),
                    "camel.main.name=demo\n" + (mode.startsWith("app") ? line : ""),
                    StandardCharsets.ISO_8859_1);
            Properties runProperties = new Properties();
            if (mode.startsWith("prop")) {
                runProperties.setProperty(PASSWORD, value);
            }

            runAndExport(runProperties);
            assertThat(reads(resources, null)).as(mode).isEqualTo(reads(main, null));
            if (!forage) {
                assertThat(QuarkusConfigExportTest.read(resources.resolve("application.properties")))
                        .as(mode)
                        .doesNotContainKey(PASSWORD);
            } else {
                assertSameAsMain();
            }
            assertThat(System.getProperty(ForagePlugin.RUN_PROPERTIES_KEY)).isNull();
        }
    }

    /** A profile value keeps the module on forage.*: the export is the one of {@code main}, as Camel JBang wrote it. */
    @Test
    void exportsAProfileValueAsMain() throws IOException {
        Files.writeString(project.resolve(MODULE), DS1 + PASSWORD + "=base\n");
        Files.writeString(project.resolve("application.properties"), "%dev." + PASSWORD + "=ok\n");

        runAndExport(properties("%prod." + PASSWORD + "=a\\\\b"));

        assertSameAsMain();
    }

    /** Profile values keep the datasource on forage.*: every profile of the exported app reads the Forage value. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "%test.forage.ds1.jdbc.password=t;%dev.forage.ds1.jdbc.password=a$b$}c  | %prod.forage.ds1.jdbc.password=p | ",
                // the module file is no Quarkus source: its profile keys are ignored
                "| | %prod.forage.ds1.jdbc.password=a${x};%dev.forage.ds1.jdbc.password="
            })
    void keepsEveryProfileValue(String application, String property, String module) throws IOException {
        Files.writeString(project.resolve(MODULE), DS1 + PASSWORD + "=base\n" + lines(module));
        Files.writeString(project.resolve("application.properties"), lines(application));
        Properties runProperties = properties(lines(property));
        Properties expected = properties(lines(application));
        expected.putAll(runProperties);

        runAndExport(runProperties);

        for (String profile : List.of("dev", "test", "prod", "custom")) {
            assertThat(reads(resources, profile))
                    .as(profile)
                    .isEqualTo(expected.getProperty("%" + profile + "." + PASSWORD, "base"));
        }
    }

    /**
     * The export writes what the project and --property forage.jms.pool.enabled=false give, not the exported files:
     * neither the run nor the export ships quarkus-pooled-jms or a Forage artifact.
     */
    @Test
    void writesTheRunTranslation() throws IOException {
        String jms = "forage.jms.kind=artemis\nforage.jms.broker.url=tcp://localhost:61616\n";
        Files.writeString(project.resolve("forage-connectionfactory.properties"), jms);
        Properties runProperties = properties("forage.jms.pool.enabled=false");

        ForagePlugin.runStarted(runProperties);
        Set<String> run = ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);
        Files.writeString(resources.resolve("forage-connectionfactory.properties"), jms.replace("localhost", "other"));
        Files.writeString(resources.resolve("application.properties"), "forage.jms.pool.enabled=false\n");
        Set<String> export = ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);

        assertThat(run).noneMatch(d -> d.contains("quarkus-pooled-jms"));
        assertThat(export).noneMatch(d -> d.contains("quarkus-pooled-jms") || d.contains("io.kaoto.forage"));
        assertThat(QuarkusConfigExportTest.resolved(
                        QuarkusConfigExportTest.read(resources.resolve("application.properties"))))
                .containsEntry("quarkus.pooled-jms.pooling.enabled", "false")
                .containsEntry("quarkus.artemis.url", "tcp://localhost:61616")
                .doesNotContainKey("forage.jms.pool.enabled");
    }

    /** The empty H2 password of the JDBC test plan: translated, Quarkus reads it as not set. */
    @Test
    void translatesAnEmptyPassword() throws IOException {
        Files.writeString(
                project.resolve(MODULE),
                "forage.ds1.jdbc.db.kind=h2\nforage.ds1.jdbc.url=jdbc:h2:mem:db\nforage.ds1.jdbc.username=sa\n"
                        + PASSWORD + "=\n");

        runAndExport(new Properties());

        // the JDBC translation leaves an empty password out: not set, as Quarkus reads the empty value
        assertThat(QuarkusConfigExportTest.resolved(
                        QuarkusConfigExportTest.read(resources.resolve("application.properties"))))
                .doesNotContainKey(QUARKUS_PASSWORD)
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.url", "jdbc:h2:mem:db");
        assertThat(resources.resolve(MODULE)).doesNotExist();
        assertThat(reads(resources, null)).isEqualTo(reads(main, null)).isNull();
    }

    /**
     * A forage.* key in a shipped application-<profile>.properties keeps the module on forage.*: the export is the one
     * of {@code main}. A profile file the export does not ship is ignored.
     */
    @ParameterizedTest
    @CsvSource({"true, ''", "false, quarkus.datasource.\"ds1\".jdbc.url"})
    void keepsModuleOfAProfileFileOnForage(boolean shipped, String translatedKey) throws IOException {
        Files.writeString(project.resolve(MODULE), DS1 + PASSWORD + "=secret\n");
        Files.writeString(project.resolve("application-dev.properties"), "forage.ds1.jdbc.url=jdbc:h2:mem:dev\n");
        if (shipped) {
            Files.copy(project.resolve("application-dev.properties"), resources.resolve("application-dev.properties"));
        }

        runAndExport(new Properties());

        if (shipped) {
            assertSameAsMain();
        } else {
            assertThat(QuarkusConfigExportTest.read(resources.resolve("application.properties")))
                    .containsKey(translatedKey);
        }
    }

    /** A module file in the project directory that the export does not ship is not translated. */
    @Test
    void ignoresAModuleFileTheExportDoesNotShip() throws IOException {
        Files.writeString(project.resolve(MODULE), DS1 + PASSWORD + "=secret\n");
        Files.writeString(project.resolve("application.properties"), "camel.main.name=demo\n");

        ForagePlugin.runStarted(new Properties());
        ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);
        Files.writeString(resources.resolve("application.properties"), "camel.main.name=demo\n");
        ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);

        assertThat(Files.readString(resources.resolve("application.properties")))
                .isEqualTo("camel.main.name=demo\n");
    }

    @Test
    void runIgnoresALeftOverExportWorkDirectory() throws IOException {
        Files.writeString(resources.resolve("application.properties"), DS1);

        ForagePlugin.runStarted(new Properties());
        Set<String> run = ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);

        assertThat(run).noneMatch(d -> d.contains("postgresql"));
        assertThat(Files.readString(resources.resolve("application.properties")))
                .isEqualTo(DS1);
    }

    /** Nothing to check the exported values against: everything stays forage.*, as without translation. */
    @Test
    void keepsForageWhenTheExportReusesEarlierRunData() throws IOException {
        Files.writeString(resources.resolve("application.properties"), "camel.main.name=demo\n");
        assertThat(ForagePlugin.dependencies(RuntimeType.QUARKUS, resources)).isEmpty();

        Files.writeString(project.resolve(MODULE), DS1 + PASSWORD + "=secret\n");
        Files.writeString(resources.resolve("application.properties"), DS1 + PASSWORD + "=secret\n");
        Set<String> dependencies = ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);

        assertThat(dependencies).anyMatch(d -> d.contains("io.kaoto.forage"));
        assertThat(Files.readString(resources.resolve("application.properties")))
                .isEqualTo(DS1 + PASSWORD + "=secret\n");
    }

    /**
     * Parity with the export without translation (the off switch): never fails where it works, loses no non-Forage
     * dependency and adds none, every quarkus.* key reads what the Forage runtime translation gives, and a FORAGE_* /
     * -Dforage.* override still wins. Columns: module files and application.properties (";" = new line), translated,
     * the override ({@code env:} or {@code sys:}), the Quarkus key and the value it then reads.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "default h2 | jdbc.db.kind=h2;jdbc.url=jdbc:h2:mem:db;jdbc.username=sa;jdbc.password=p | | true"
                        + " | env:FORAGE_JDBC_URL=jdbc:h2:mem:prod | quarkus.datasource.\"dataSource\".jdbc.url | jdbc:h2:mem:prod",
                "named postgresql | ds1.jdbc.db.kind=postgresql;ds1.jdbc.username=u;ds1.jdbc.url=jdbc:postgresql://h/db;ds1.jdbc.password=p"
                        + " | | true | sys:forage.ds1.jdbc.pool.acquisition.timeout.seconds=30"
                        + " | quarkus.datasource.\"ds1\".jdbc.acquisition-timeout | 30S",
                "map key override | ds1.jdbc.db.kind=mysql;ds1.jdbc.username=u;ds1.jdbc.password=p | ds1.jdbc.url=jdbc:mysql://h/db;ds1.jdbc.properties.ssl=true | true"
                        + " | env:FORAGE_DS1_JDBC_PROPERTIES_SSL=false"
                        + " | quarkus.datasource.\"ds1\".jdbc.additional-jdbc-properties.\"ssl\" | false",
                "empty password | ds1.jdbc.username=u;ds1.jdbc.password= | ds1.jdbc.db.kind=mariadb;ds1.jdbc.url=jdbc:mariadb://h/db | true"
                        + " | env:FORAGE_DS1_JDBC_PASSWORD=pw | quarkus.datasource.\"ds1\".password | pw",
                "oracle | ds1.jdbc.db.kind=oracle;ds1.jdbc.username=u;ds1.jdbc.password=p;ds1.jdbc.url=jdbc:oracle:thin:@h:1521/db | | false | | |",
                "db2 | ds1.jdbc.db.kind=db2;ds1.jdbc.username=u;ds1.jdbc.password=p;ds1.jdbc.url=jdbc:db2://h/db | | false | | |",
                "mssql | ds1.jdbc.db.kind=mssql;ds1.jdbc.username=u;ds1.jdbc.password=p;ds1.jdbc.url=jdbc:sqlserver://h | | false | | |",
                "hsqldb | ds1.jdbc.db.kind=hsqldb;ds1.jdbc.username=u;ds1.jdbc.password=p;ds1.jdbc.url=jdbc:hsqldb:mem:db | | false | | |",
                "transaction | ds1.jdbc.db.kind=h2;ds1.jdbc.username=u;ds1.jdbc.password=p;ds1.jdbc.url=jdbc:h2:mem:db;ds1.jdbc.transaction.enabled=true | | false | | |",
                "value with } | ds1.jdbc.db.kind=h2;ds1.jdbc.username=u;ds1.jdbc.url=jdbc:h2:mem:db;ds1.jdbc.password=a}b | | false | | |",
                "backslash in application.properties | ds1.jdbc.db.kind=h2;ds1.jdbc.username=u;ds1.jdbc.url=jdbc:h2:mem:db"
                        + " | ds1.jdbc.password=a\\\\b | false | | |",
                "artemis | @jms.kind=artemis;jms.broker.url=tcp://h:61616 | | true"
                        + " | env:FORAGE_JMS_USERNAME=u | quarkus.artemis.username | u",
                "artemis pooled | @jms.kind=artemis;jms.broker.url=tcp://h:61616;jms.pool.enabled=true | | true"
                        + " | sys:forage.jms.broker.url=tcp://prod:61616 | quarkus.artemis.url | tcp://prod:61616",
                "artemis ssl | @jms.kind=artemis;jms.broker.url=tcp://h:61616;jms.ssl.enabled=true | | false | | |",
                "named artemis | @mq1.jms.kind=artemis;mq1.jms.broker.url=tcp://h:61616 | | false | | |",
                "ibmmq | @jms.kind=ibmmq;jms.broker.url=localhost(1414) | | false | | |"
            })
    void exportsAsWithoutTranslation(
            String name,
            String moduleFile,
            String application,
            boolean translated,
            String override,
            String quarkusKey,
            String overridden)
            throws IOException {
        // "@": the JMS module file
        String file = moduleFile != null && moduleFile.startsWith("@") ? "forage-connectionfactory.properties" : MODULE;
        Files.writeString(project.resolve(file), forage(moduleFile == null ? "" : moduleFile.replaceFirst("^@", "")));
        Files.writeString(project.resolve("application.properties"), forage(application));

        System.setProperty(SKIP, "true");
        Set<String> before = runAndExport(new Properties());
        assertSameAsMain();
        System.clearProperty(SKIP);
        cleanExport();
        Set<String> after = runAndExport(new Properties());

        Set<String> nonForage = new TreeSet<>(before);
        nonForage.removeIf(d -> d.startsWith("mvn:io.kaoto.forage:"));
        assertThat(after).containsAll(nonForage).isSubsetOf(before);
        if (!translated) {
            assertSameAsMain();
            assertThat(after).isEqualTo(before);
            return;
        }
        assertThat(after).noneMatch(d -> d.startsWith("mvn:io.kaoto.forage:"));
        Properties exported = QuarkusConfigExportTest.read(resources.resolve("application.properties"));
        assertThat(exported.stringPropertyNames()).noneMatch(key -> key.startsWith("forage."));
        Map<String, String> forage = forageTranslation(main);
        SmallRyeConfig quarkus = quarkus(exported, Map.of(), Map.of());
        Set<String> keys = new TreeSet<>(forage.keySet());
        keys.addAll(exported.stringPropertyNames());
        keys.remove(QuarkusConfigExport.LOCATIONS);
        for (String key : keys) {
            // as Quarkus reads it: empty is not set
            assertThat(quarkus.getOptionalValue(key, String.class).orElse(null))
                    .as(key)
                    .isEqualTo(forage.get(key) == null || forage.get(key).isEmpty() ? null : forage.get(key));
        }

        String[] setting = override.substring(4).split("=", 2);
        Map<String, String> value = Map.of(setting[0], setting[1]);
        boolean env = override.startsWith("env:");
        assertThat(quarkus(exported, env ? value : Map.of(), env ? Map.of() : value)
                        .getConfigValue(quarkusKey)
                        .getValue())
                .isEqualTo(overridden);
    }

    /** The Quarkus configuration of the exported app, with environment variables and system properties. */
    private static SmallRyeConfig quarkus(Properties application, Map<String, String> env, Map<String, String> sys) {
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(application, "app", 250))
                .withSources(new EnvConfigSource(env, 300))
                .withSources(new PropertiesConfigSource(sys, "sys", 400))
                .build();
    }

    /** What the Forage runtime translates from the exported files: the module files, then application.properties. */
    private static Map<String, String> forageTranslation(Path exported) throws IOException {
        Properties values = new Properties();
        try (Stream<Path> files = Files.list(exported)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("forage-"))
                    .toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    values.load(in);
                }
            }
        }
        values.putAll(QuarkusConfigExportTest.read(exported.resolve("application.properties")));
        Map<String, String> translated = new TreeMap<>();
        for (ForageModuleDescriptor<?, ?> descriptor : List.of(new JdbcModuleDescriptor(), new JmsModuleDescriptor())) {
            String module = descriptor.modulePrefix();
            Set<String> names = PropertyFileLocator.readPrefixes(values, ConfigHelper.getNamedPropertyRegexp(module));
            if (!PropertyFileLocator.readPrefixes(values, ConfigHelper.getDefaultPropertyRegexp(module))
                    .isEmpty()) {
                translated.putAll(translate(descriptor, null, values));
            }
            for (String name : names) {
                translated.putAll(translate(descriptor, name, values));
            }
        }
        ConfigStore.getInstance().reload();
        return translated;
    }

    private static <C extends Config> Map<String, String> translate(
            ForageModuleDescriptor<C, ?> descriptor, String name, Properties values) {
        C config = descriptor.createConfig(name);
        values.stringPropertyNames().forEach(key -> config.register(key, values.getProperty(key)));
        return descriptor.translateProperties(name, config);
    }

    private static String forage(String csv) {
        return csv == null ? "" : ("forage." + csv.replace(";", "\nforage.") + "\n");
    }

    /** The silent run, Camel JBang writing the exported resources, then the export call. */
    private Set<String> runAndExport(Properties runProperties) throws IOException {
        ForagePlugin.runStarted(runProperties);
        try {
            ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);
        } catch (IllegalStateException e) {
            // Camel JBang stops when the run fails: the export call never comes
            System.clearProperty(ForagePlugin.RUN_PROPERTIES_KEY);
            throw e;
        }
        camelExport(runProperties);
        try (Stream<Path> files = Files.list(resources)) {
            for (Path file : files.toList()) {
                Files.copy(file, main.resolve(file.getFileName()));
            }
        }
        return ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);
    }

    /** The export call changed nothing: the exported resources are the ones of an export from {@code main}. */
    private void assertSameAsMain() throws IOException {
        try (Stream<Path> files = Files.list(main)) {
            for (Path file : files.toList()) {
                assertThat(resources.resolve(file.getFileName()))
                        .as(file.getFileName().toString())
                        .hasSameBinaryContentAs(file);
            }
        }
        try (Stream<Path> files = Files.list(resources)) {
            assertThat(files.count()).isEqualTo(Files.list(main).count());
        }
    }

    /**
     * What Camel JBang 4.22 exports: the project application.properties as read (ISO-8859-1) without blank values,
     * then the --property values, each as {@code key=value}, written as UTF-8. The module file is copied as it is.
     */
    private void camelExport(Properties runProperties) throws IOException {
        Properties exported = new Properties();
        if (Files.exists(project.resolve("application.properties"))) {
            try (InputStream in = Files.newInputStream(project.resolve("application.properties"))) {
                exported.load(in);
            }
        }
        exported.putAll(runProperties);
        StringBuilder content = new StringBuilder();
        exported.stringPropertyNames().stream()
                .filter(key -> !exported.getProperty(key).isBlank())
                .forEach(key -> content.append(key)
                        .append('=')
                        .append(exported.getProperty(key))
                        .append('\n'));
        Files.writeString(resources.resolve("application.properties"), content.toString(), StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.list(project)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("forage-"))
                    .toList()) {
                Files.copy(file, resources.resolve(file.getFileName()));
            }
        }
    }

    /**
     * The password of the exported app as Quarkus reads it with a profile, from the translated key or the Forage key:
     * SmallRye expands the exported application.properties, then the module file without its %profile keys (Forage's
     * config source hands it to Quarkus). An empty value is not set.
     */
    private static String reads(Path exported, String profile) throws IOException {
        Properties module = new Properties();
        if (Files.exists(exported.resolve(MODULE))) {
            try (InputStream in = Files.newInputStream(exported.resolve(MODULE))) {
                module.load(in);
            }
            module.keySet().removeIf(key -> key.toString().startsWith("%"));
        }
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(
                        QuarkusConfigExportTest.read(exported.resolve("application.properties")), "app", 250))
                .withSources(new PropertiesConfigSource(module, "module", 200));
        SmallRyeConfig config = (profile == null ? builder : builder.withProfile(profile)).build();
        String value = config.getConfigValue(QUARKUS_PASSWORD).getValue();
        if (value == null || value.isEmpty()) {
            value = config.getConfigValue(PASSWORD).getValue();
        }
        return value == null || value.isEmpty() ? null : value;
    }

    private void clean() throws IOException {
        cleanExport();
        try (Stream<Path> files = Files.list(project)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    private void cleanExport() throws IOException {
        for (Path dir : List.of(resources, main)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        ConfigStore.getInstance().reload();
    }

    private static String lines(String csv) {
        return csv == null ? "" : csv.replace(";", "\n") + "\n";
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
