package io.kaoto.forage.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import io.kaoto.forage.core.common.RuntimeType;
import io.kaoto.forage.core.util.config.ConfigStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Camel JBang resolves the dependencies twice for {@code camel export --runtime=quarkus}: in a silent run of the project
 * directory (with the {@code --property} values) and for the exported resources. It ships both. The export call
 * writes what the run translated.
 */
class ForagePluginDependenciesTest {

    private static final String JMS = "forage.jms.kind=artemis\nforage.jms.broker.url=tcp://localhost:61616\n";
    private static final String POOLED_JMS = "quarkus-pooled-jms";

    @TempDir
    Path project;

    @TempDir
    Path resources;

    @BeforeEach
    void setUp() throws IOException {
        System.setProperty("forage.config.dir", project.toString());
        System.clearProperty(ForagePlugin.RUN_DEPENDENCIES);
        System.clearProperty(ForagePlugin.RUN_TRANSLATION);
        Files.writeString(project.resolve("forage-connectionfactory.properties"), JMS);
        // what Camel JBang exports for --property forage.jms.pool.enabled=false
        Files.writeString(resources.resolve("forage-connectionfactory.properties"), JMS);
        Files.writeString(resources.resolve("application.properties"), "forage.jms.pool.enabled=false\n");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("forage.config.dir");
        System.clearProperty(ForagePlugin.RUN_DEPENDENCIES);
        System.clearProperty(ForagePlugin.RUN_TRANSLATION);
        ConfigStore.getInstance().reload();
    }

    @Test
    void propertyDisablingPoolingShipsNoPool() throws IOException {
        ForagePlugin.runStarted(Map.of("forage.jms.pool.enabled", "false"));
        Set<String> run = ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);

        Set<String> export = ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);

        assertThat(run).noneMatch(d -> d.contains(POOLED_JMS));
        assertThat(System.getProperty(ForagePlugin.RUN_DEPENDENCIES)).isNull();
        assertThat(export).noneMatch(d -> d.contains(POOLED_JMS)).noneMatch(d -> d.contains("io.kaoto.forage"));
        assertThat(Files.readString(resources.resolve("application.properties")))
                .contains("quarkus.pooled-jms.pooling.enabled=false")
                .doesNotContain("forage.jms.");
    }

    @Test
    void failsWhenTheRunAddsADependencyTheExportDoesNot() throws IOException {
        Files.writeString(resources.resolve("application.properties"), "");
        ForagePlugin.runStarted(Map.of());
        assertThat(ForagePlugin.dependencies(RuntimeType.QUARKUS, resources)).anyMatch(d -> d.contains(POOLED_JMS));
        // e.g. a customizer that reads other files in the export call
        System.setProperty(
                ForagePlugin.RUN_DEPENDENCIES,
                System.getProperty(ForagePlugin.RUN_DEPENDENCIES) + ",mvn:org.acme:extra:1.0");

        assertThatThrownBy(() -> ForagePlugin.dependencies(RuntimeType.QUARKUS, resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mvn:org.acme:extra:1.0");
        assertThat(System.getProperty(ForagePlugin.RUN_DEPENDENCIES)).isNull();
    }

    @Test
    void failsWhenTheExportedValueDiffersFromTheRun() {
        // the run sees pooling on (default), the exported resources switch it off
        ForagePlugin.runStarted(Map.of());
        ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);

        assertThatThrownBy(() -> ForagePlugin.dependencies(RuntimeType.QUARKUS, resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changes forage.jms.pool.enabled: expected no value, found 'false'");
        assertThat(System.getProperty(ForagePlugin.RUN_DEPENDENCIES)).isNull();
        assertThat(System.getProperty(ForagePlugin.RUN_TRANSLATION)).isNull();
    }

    @Test
    void failsWhenTheExportReusesEarlierRunData() {
        assertThatThrownBy(() -> ForagePlugin.dependencies(RuntimeType.QUARKUS, resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--fresh");
    }

    @Test
    void runIgnoresALeftOverExportWorkDirectory() throws IOException {
        Files.writeString(
                resources.resolve("application.properties"),
                "forage.jdbc.db.kind=postgresql\nforage.jdbc.url=jdbc:postgresql://localhost/db\n");
        String before = Files.readString(resources.resolve("application.properties"));

        ForagePlugin.runStarted(Map.of());
        Set<String> run = ForagePlugin.dependencies(RuntimeType.QUARKUS, resources);

        assertThat(run).anyMatch(d -> d.contains(POOLED_JMS)).noneMatch(d -> d.contains("postgresql"));
        assertThat(Files.readString(resources.resolve("application.properties")))
                .isEqualTo(before);
    }

    @Test
    void otherRuntimesAreNotChecked() {
        ForagePlugin.runStarted(Map.of());
        Set<String> run = ForagePlugin.dependencies(RuntimeType.MAIN, resources);

        assertThat(ForagePlugin.dependencies(RuntimeType.MAIN, resources)).isEqualTo(run);
    }
}
