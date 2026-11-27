package io.kaoto.forage.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import io.kaoto.forage.core.common.RuntimeType;
import io.kaoto.forage.core.util.config.ConfigStore;
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

/** Modules whose bean the export creates with plain Camel {@code camel.beans.*} keys. */
class QuarkusBeanExportTest {

    private static final String INFLUXDB_FILE = "forage-influxdb.properties";
    private static final String REFLECTION = "quarkus.camel.native.reflection.include-patterns";

    @TempDir
    Path project;

    /** The exported resources. */
    @TempDir
    Path resources;

    @BeforeEach
    void setUp() {
        System.setProperty("forage.config.dir", project.toString());
    }

    @AfterEach
    void tearDown() {
        List.of("forage.config.dir", ForagePlugin.FRESH_RUN).forEach(System::clearProperty);
        ConfigStore.getInstance().reload();
    }

    @Test
    void createsANamedInfluxDbClientWithCredentials() throws IOException {
        Files.writeString(
                resources.resolve(INFLUXDB_FILE),
                "forage.metrics.influxdb.url=http://localhost:8086\n"
                        + "forage.metrics.influxdb.username=admin\n"
                        + "forage.metrics.influxdb.password=secret\n");

        QuarkusExport.Result result = QuarkusExport.translate(resources, new Properties());

        assertThat(result.modules()).containsExactly("influxdb");
        assertThat(result.properties())
                .containsOnly(
                        Map.entry(
                                "camel.beans.metrics",
                                "#class:org.influxdb.InfluxDBFactory#connect("
                                        + "'${FORAGE_METRICS_INFLUXDB_URL:${forage.metrics.influxdb.url:http://localhost:8086}}',"
                                        + "'${FORAGE_METRICS_INFLUXDB_USERNAME:${forage.metrics.influxdb.username:admin}}',"
                                        + "'${FORAGE_METRICS_INFLUXDB_PASSWORD:${forage.metrics.influxdb.password:secret}}')"),
                        Map.entry(REFLECTION, "org.influxdb.InfluxDBFactory"),
                        Map.entry("quarkus.index-dependency.influxdb-java.group-id", "org.influxdb"),
                        Map.entry("quarkus.index-dependency.influxdb-java.artifact-id", "influxdb-java"));
        assertThat(result.notes())
                .singleElement()
                .asString()
                .contains("influxdb exported as camel.beans.* configuration", INFLUXDB_FILE + " not read");
    }

    /** The default configuration: the default bean name; the factory method of the keys set. */
    @Test
    void createsTheDefaultInfluxDbClientWithoutCredentials() throws IOException {
        Files.writeString(resources.resolve(INFLUXDB_FILE), "forage.influxdb.url=http://localhost:8086\n");

        QuarkusExport.Result result = QuarkusExport.translate(resources, new Properties());

        assertThat(result.properties())
                .containsEntry(
                        "camel.beans.influxdb",
                        "#class:org.influxdb.InfluxDBFactory#connect("
                                + "'${FORAGE_INFLUXDB_URL:${forage.influxdb.url:http://localhost:8086}}')");
    }

    /** The exported app reads the values Forage reads: FORAGE_*, then -Dforage.*, then the exported value. */
    @Test
    void exportedValueIsOverridable() throws IOException {
        Files.writeString(resources.resolve(INFLUXDB_FILE), "forage.influxdb.url=http://localhost:8086\n");

        QuarkusExport.export(resources);

        assertThat(quarkus(Map.of(), Map.of()).getValue("camel.beans.influxdb", String.class))
                .isEqualTo("#class:org.influxdb.InfluxDBFactory#connect('http://localhost:8086')");
        assertThat(quarkus(Map.of("FORAGE_INFLUXDB_URL", "http://env:8086"), Map.of())
                        .getValue("camel.beans.influxdb", String.class))
                .isEqualTo("#class:org.influxdb.InfluxDBFactory#connect('http://env:8086')");
        assertThat(quarkus(Map.of(), Map.of("forage.influxdb.url", "http://sys:8086"))
                        .getValue("camel.beans.influxdb", String.class))
                .isEqualTo("#class:org.influxdb.InfluxDBFactory#connect('http://sys:8086')");
    }

    /** What the camel.beans.* keys cannot express keeps the module on forage.* (";" = new line). */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                INFLUXDB_FILE + " | forage.influxdb.url=u;forage.influxdb.username=admin"
                        + " | forage.influxdb.username is not used by the bean",
                INFLUXDB_FILE + " | forage.influxdb.username=admin;forage.influxdb.password=p"
                        + " | forage.influxdb.url is not set",
                INFLUXDB_FILE + " | forage.my-db.influxdb.url=u | the bean name my-db cannot be a camel.beans.* name",
                INFLUXDB_FILE
                        + " | forage.influxdb.url=it's | forage.influxdb.url cannot be written as a Camel argument",
            })
    void keepsModuleOnForage(String file, String lines, String reason) throws IOException {
        Files.writeString(resources.resolve(file), lines.replace(";", "\n"));

        QuarkusExport.Result result = QuarkusExport.export(resources);

        assertThat(result.modules()).isEmpty();
        assertThat(result.properties()).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("stays as forage.*", reason);
        assertThat(resources.resolve("application.properties")).doesNotExist();
    }

    /** A user list of reflection classes is kept and extended; another user key wins. */
    @Test
    void writeJoinsTheUserReflectionClasses() throws IOException {
        Files.writeString(
                resources.resolve("application.properties"),
                REFLECTION + "=com.acme.Foo\nquarkus.index-dependency.influxdb-java.group-id=com.acme\n");
        Files.writeString(resources.resolve(INFLUXDB_FILE), "forage.influxdb.url=http://localhost:8086\n");

        QuarkusExport.export(resources);

        SmallRyeConfig quarkus = quarkus(Map.of(), Map.of());
        assertThat(quarkus.getValue(REFLECTION, String.class)).isEqualTo("com.acme.Foo,org.influxdb.InfluxDBFactory");
        assertThat(quarkus.getValue("quarkus.index-dependency.influxdb-java.group-id", String.class))
                .isEqualTo("com.acme");
    }

    /** The exported app keeps the non-Forage dependencies of a translated module, not its Forage artifacts. */
    @Test
    void silentRunLeavesOutTheForageArtifacts() throws IOException {
        Files.writeString(project.resolve(INFLUXDB_FILE), "forage.influxdb.url=http://localhost:8086\n");
        Files.writeString(resources.resolve(INFLUXDB_FILE), "forage.influxdb.url=http://localhost:8086\n");

        ForagePlugin.runStarted();
        Set<String> run = ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);
        Set<String> exported = ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);

        assertThat(System.getProperty(ForagePlugin.FRESH_RUN)).isEqualTo("influxdb");
        assertThat(run).isEqualTo(exported).containsExactly("mvn:org.apache.camel.quarkus:camel-quarkus-influxdb");
    }

    /** application.properties, environment variables and system properties of the exported app. */
    private SmallRyeConfig quarkus(Map<String, String> env, Map<String, String> sys) throws IOException {
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(
                        QuarkusExportTest.read(resources.resolve("application.properties")), "app", 250))
                .withSources(new EnvConfigSource(env, 300))
                .withSources(new PropertiesConfigSource(sys, "sys", 400))
                .build();
    }
}
