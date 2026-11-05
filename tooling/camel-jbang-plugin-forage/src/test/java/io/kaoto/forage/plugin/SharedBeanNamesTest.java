package io.kaoto.forage.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.dsl.jbang.core.common.StringPrinter;
import io.kaoto.forage.catalog.reader.ForageCatalogReader;
import io.kaoto.forage.core.common.RuntimeType;
import io.kaoto.forage.plugin.config.ConfigReadCommand;
import io.kaoto.forage.plugin.config.ConfigWriteCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean names listed by several factories: redis and infinispan (Agent memory and Embedding Store),
 * mariadb (DataSource and Embedding Store). A store project gets the store jars, keys and bean only;
 * Agent and JDBC projects stay as before the Embedding Store factory.
 */
class SharedBeanNamesTest {
    private static final String FORAGE = "mvn:io.kaoto.forage:";

    @TempDir
    File tempDir;

    private final ForageCatalogReader catalog = ForageCatalogReader.getInstance();
    private String originalUserDir;

    @BeforeEach
    void setUp() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void storeRedis() throws Exception {
        write(
                "forage.vectordb.kind=redis",
                "forage.redis.host=localhost",
                "forage.redis.port=6379",
                "forage.redis.index.name=docs",
                "forage.redis.dimension=384");

        assertStoreProject(
                "redis",
                "{\"kind\":\"redis\",\"host\":\"localhost\",\"port\":\"6379\","
                        + "\"dimension\":\"384\",\"index.name\":\"docs\"}");
    }

    @Test
    void storeInfinispan() throws Exception {
        write(
                "forage.vectordb.kind=infinispan",
                "forage.infinispan.host=localhost",
                "forage.infinispan.username=admin",
                "forage.infinispan.cache.name=docs",
                "forage.infinispan.dimension=384");

        assertStoreProject(
                "infinispan",
                "{\"kind\":\"infinispan\",\"host\":\"localhost\",\"dimension\":\"384\","
                        + "\"username\":\"admin\",\"cache.name\":\"docs\"}");
    }

    @Test
    void storeMariadb() throws Exception {
        write(
                "forage.vectordb.kind=mariadb",
                "forage.mariadb.url=jdbc:mariadb://localhost:3306/vectors",
                "forage.mariadb.user=test",
                "forage.mariadb.table=docs",
                "forage.mariadb.dimension=384");

        assertStoreProject(
                "mariadb",
                "{\"kind\":\"mariadb\",\"user\":\"test\","
                        + "\"url\":\"jdbc:mariadb://localhost:3306/vectors\",\"table\":\"docs\",\"dimension\":\"384\"}");
    }

    @Test
    void storeAndMemoryRedis() throws Exception {
        write(
                "forage.agent.model.kind=ollama",
                "forage.agent.features=memory",
                "forage.agent.memory.kind=redis",
                "forage.redis.host=localhost",
                "forage.redis.database=0",
                "forage.docs.vectordb.kind=redis",
                "forage.docs.redis.index.name=docs");

        assertThat(dependencies(RuntimeType.MAIN))
                .contains(
                        FORAGE + "forage-agent",
                        FORAGE + "forage-memory-redis",
                        FORAGE + "forage-embedding-store",
                        FORAGE + "forage-vectordb-redis");
        assertThat(warnings()).isEmpty();
        assertThat(beans())
                .containsExactly(
                        "agent|-|org.apache.camel.component.langchain4j.agent.api.Agent|"
                                + "{\"model.kind\":\"ollama\",\"features\":\"memory\",\"memory.kind\":\"redis\"}",
                        "agent|redis|dev.langchain4j.memory.ChatMemory|{\"host\":\"localhost\",\"database\":\"0\"}",
                        "docs|redis|dev.langchain4j.store.embedding.EmbeddingStore|"
                                + "{\"kind\":\"redis\",\"index.name\":\"docs\"}");
    }

    // The expected values below are the output of upstream main, before the Embedding Store factory

    @Test
    void agentRedisAsOnMain() throws Exception {
        write(
                "forage.agent.model.kind=ollama",
                "forage.agent.features=memory",
                "forage.agent.memory.kind=redis",
                "forage.redis.host=localhost",
                "forage.redis.port=6379",
                "forage.redis.database=0");

        assertThat(dependencies(RuntimeType.MAIN))
                .containsExactly(
                        FORAGE + "forage-agent",
                        FORAGE + "forage-memory-redis",
                        FORAGE + "forage-model-embeddings-ollama",
                        FORAGE + "forage-model-ollama");
        assertThat(dependencies(RuntimeType.QUARKUS))
                .containsExactly(
                        FORAGE + "forage-memory-redis",
                        FORAGE + "forage-model-embeddings-ollama",
                        FORAGE + "forage-model-ollama",
                        FORAGE + "forage-quarkus-agent-deployment");
        assertThat(dependencies(RuntimeType.SPRING_BOOT))
                .containsExactly(
                        FORAGE + "forage-agent-starter",
                        FORAGE + "forage-memory-redis",
                        FORAGE + "forage-model-embeddings-ollama",
                        FORAGE + "forage-model-ollama");
        assertThat(warnings()).isEmpty();
        assertThat(beans())
                .containsExactly(
                        "agent|-|org.apache.camel.component.langchain4j.agent.api.Agent|"
                                + "{\"model.kind\":\"ollama\",\"features\":\"memory\",\"memory.kind\":\"redis\"}",
                        "agent|redis|dev.langchain4j.memory.ChatMemory|"
                                + "{\"host\":\"localhost\",\"database\":\"0\",\"port\":\"6379\"}");
    }

    @Test
    void agentInfinispanAsOnMain() throws Exception {
        write(
                "forage.agent.model.kind=ollama",
                "forage.agent.features=memory",
                "forage.agent.memory.kind=infinispan",
                "forage.infinispan.server-list=localhost:11222",
                "forage.infinispan.username=admin");

        assertThat(dependencies(RuntimeType.MAIN))
                .containsExactly(
                        FORAGE + "forage-agent",
                        FORAGE + "forage-memory-infinispan",
                        FORAGE + "forage-model-embeddings-ollama",
                        FORAGE + "forage-model-ollama");
        assertThat(dependencies(RuntimeType.QUARKUS))
                .containsExactly(
                        FORAGE + "forage-memory-infinispan",
                        FORAGE + "forage-model-embeddings-ollama",
                        FORAGE + "forage-model-ollama",
                        FORAGE + "forage-quarkus-agent-deployment");
        assertThat(dependencies(RuntimeType.SPRING_BOOT))
                .containsExactly(
                        FORAGE + "forage-agent-starter",
                        FORAGE + "forage-memory-infinispan",
                        FORAGE + "forage-model-embeddings-ollama",
                        FORAGE + "forage-model-ollama");
        assertThat(warnings()).isEmpty();
        assertThat(beans())
                .containsExactly(
                        "agent|-|org.apache.camel.component.langchain4j.agent.api.Agent|"
                                + "{\"model.kind\":\"ollama\",\"features\":\"memory\",\"memory.kind\":\"infinispan\"}",
                        "agent|infinispan|dev.langchain4j.memory.ChatMemory|"
                                + "{\"server-list\":\"localhost:11222\",\"username\":\"admin\"}");
    }

    @Test
    void jdbcMariadbAsOnMain() throws Exception {
        write(
                "forage.jdbc.db.kind=mariadb",
                "forage.jdbc.url=jdbc:mariadb://localhost:3306/test",
                "forage.jdbc.username=test");

        assertThat(dependencies(RuntimeType.MAIN))
                .containsExactly(FORAGE + "forage-jdbc", FORAGE + "forage-jdbc-mariadb");
        assertThat(dependencies(RuntimeType.QUARKUS))
                .containsExactly(
                        FORAGE + "forage-jdbc-mariadb",
                        FORAGE + "forage-quarkus-jdbc-deployment",
                        "mvn:io.quarkus:quarkus-jdbc-mariadb",
                        "mvn:org.apache.camel.quarkus:camel-quarkus-jdbc",
                        "mvn:org.apache.camel.quarkus:camel-quarkus-sql");
        assertThat(dependencies(RuntimeType.SPRING_BOOT))
                .containsExactly(FORAGE + "forage-jdbc-mariadb", FORAGE + "forage-jdbc-starter");
        assertThat(warnings()).isEmpty();
        assertThat(beans())
                .containsExactly("dataSource|mariadb|javax.sql.DataSource|{\"username\":\"test\","
                        + "\"db.kind\":\"mariadb\",\"url\":\"jdbc:mariadb://localhost:3306/test\"}");
    }

    // forage.[<agent>.]agent.embeddings.* are keys of older docs: Agent keys that no factory reads
    @Test
    void agentKeysOfOlderDocs() throws Exception {
        write(
                "forage.agent.model.kind=ollama",
                "forage.agent.embeddings.kind=ollama",
                "forage.agent.embeddings.model.name=nomic-embed-text",
                "forage.agent.vectordb.kind=in-memory-store",
                "forage.myAgent.agent.model.kind=ollama",
                "forage.myAgent.agent.embeddings.kind=ollama");

        assertThat(dependencies(RuntimeType.MAIN))
                .containsExactly(
                        FORAGE + "forage-agent",
                        FORAGE + "forage-model-embeddings-ollama",
                        FORAGE + "forage-model-ollama");
        assertThat(dependencies(RuntimeType.QUARKUS))
                .containsExactly(
                        FORAGE + "forage-model-embeddings-ollama",
                        FORAGE + "forage-model-ollama",
                        FORAGE + "forage-quarkus-agent-deployment");
        assertThat(dependencies(RuntimeType.SPRING_BOOT))
                .containsExactly(
                        FORAGE + "forage-agent-starter",
                        FORAGE + "forage-model-embeddings-ollama",
                        FORAGE + "forage-model-ollama");
        assertThat(warnings())
                .containsExactlyInAnyOrder(
                        "Unknown property 'embeddings.kind' for factory 'agent'",
                        "Unknown property 'vectordb.kind' for factory 'agent'");
        assertThat(beans())
                .containsExactly(
                        "agent|-|org.apache.camel.component.langchain4j.agent.api.Agent|{\"embeddings.kind\":\"ollama\","
                                + "\"vectordb.kind\":\"in-memory-store\",\"model.kind\":\"ollama\","
                                + "\"embeddings.model.name\":\"nomic-embed-text\"}",
                        "myAgent|-|org.apache.camel.component.langchain4j.agent.api.Agent|"
                                + "{\"embeddings.kind\":\"ollama\",\"model.kind\":\"ollama\"}");
    }

    // config write picks the factory of a shared kind like config read and export
    @ParameterizedTest
    @CsvSource({"redis,host", "infinispan,username", "mariadb,url"})
    void writeStore(String kind, String key) throws Exception {
        String keyOfKind = "forage." + kind + "." + key;
        JsonNode result = configWrite("{\"forage.vectordb.kind\":\"" + kind + "\",\"" + keyOfKind
                + "\":\"test\",\"kind\":\"" + kind + "\",\"forage.bean.name\":\"docs\"}");

        assertThat(result.get("factories").fieldNames()).toIterable().containsExactly("vectordb", "dependencies");
        assertThat(written())
                .containsExactlyInAnyOrder(
                        "forage.docs.vectordb.kind=" + kind,
                        "forage.docs." + kind + "." + key + "=test",
                        "camel.jbang.dependencies=io.kaoto.forage:forage-vectordb-" + kind,
                        "camel.jbang.dependencies.main=io.kaoto.forage:forage-embedding-store",
                        "camel.jbang.dependencies.quarkus=io.kaoto.forage:forage-quarkus-embedding-store-deployment",
                        "camel.jbang.dependencies.spring-boot=io.kaoto.forage:forage-embedding-store-starter");
    }

    @Test
    void writeStoreWithoutKind() throws Exception {
        assertThat(configWrite("{\"forage.vectordb.kind\":\"redis\",\"forage.redis.host\":\"localhost\"}")
                        .get("factories")
                        .fieldNames())
                .toIterable()
                .containsExactly("vectordb", "dependencies");
    }

    @Test
    void writeMemoryAndJdbcAsBefore() throws Exception {
        assertThat(configWrite("{\"forage.redis.host\":\"localhost\",\"kind\":\"redis\",\"forage.bean.name\":\"mem\"}")
                        .get("factories")
                        .fieldNames())
                .toIterable()
                .containsExactly("agent", "dependencies");
        assertThat(configWrite("{\"forage.jdbc.db.kind\":\"mariadb\",\"kind\":\"mariadb\",\"forage.bean.name\":\"db\"}")
                        .get("factories")
                        .fieldNames())
                .toIterable()
                .containsExactly("jdbc", "dependencies");
        assertThat(written())
                .containsExactlyInAnyOrder(
                        "forage.mem.redis.host=localhost",
                        "forage.db.jdbc.db.kind=mariadb",
                        "camel.jbang.dependencies=io.kaoto.forage:forage-jdbc-mariadb,io.kaoto.forage:forage-memory-redis",
                        "camel.jbang.dependencies.main=io.kaoto.forage:forage-agent,io.kaoto.forage:forage-jdbc",
                        "camel.jbang.dependencies.quarkus=io.kaoto.forage:forage-quarkus-agent-deployment,"
                                + "io.kaoto.forage:forage-quarkus-jdbc-deployment",
                        "camel.jbang.dependencies.spring-boot=io.kaoto.forage:forage-agent-starter,"
                                + "io.kaoto.forage:forage-jdbc-starter");
    }

    private void assertStoreProject(String kind, String configuration) throws Exception {
        assertThat(dependencies(RuntimeType.MAIN))
                .containsExactly(FORAGE + "forage-embedding-store", FORAGE + "forage-vectordb-" + kind);
        assertThat(dependencies(RuntimeType.QUARKUS))
                .containsExactly(
                        FORAGE + "forage-quarkus-embedding-store-deployment", FORAGE + "forage-vectordb-" + kind);
        assertThat(dependencies(RuntimeType.SPRING_BOOT))
                .containsExactly(FORAGE + "forage-embedding-store-starter", FORAGE + "forage-vectordb-" + kind);
        assertThat(warnings()).isEmpty();
        assertThat(beans())
                .containsExactly(
                        "embeddingStore|" + kind + "|dev.langchain4j.store.embedding.EmbeddingStore|" + configuration);
    }

    private void write(String... lines) throws Exception {
        try (FileWriter writer = new FileWriter(new File(tempDir, "application.properties"))) {
            writer.write(String.join("\n", lines) + "\n");
        }
    }

    // Sorted, without versions
    private Set<String> dependencies(RuntimeType runtime) {
        System.setProperty("user.dir", tempDir.getAbsolutePath());
        try {
            return new CatalogDrivenExportCustomizer()
                    .resolveRuntimeDependencies(runtime).stream()
                            .map(dependency -> dependency.replaceAll(":[0-9][^:]*$", ""))
                            .collect(Collectors.toCollection(TreeSet::new));
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    private Set<String> warnings() throws Exception {
        return ForagePropertyValidator.validate(tempDir, catalog).getWarnings().stream()
                .map(ForagePropertyValidator.ValidationWarning::getMessage)
                .collect(Collectors.toSet());
    }

    // name|kind|javaType|configuration of each bean of config read, sorted
    private Set<String> beans() throws Exception {
        StringPrinter printer = new StringPrinter();
        ConfigReadCommand command = new ConfigReadCommand(new CamelJBangMain().withPrinter(printer));
        set(command, "directory", tempDir);
        set(command, "strategy", "application");
        command.doCall();
        Set<String> beans = new TreeSet<>();
        for (JsonNode bean : new ObjectMapper().readTree(printer.getOutput()).get("beans")) {
            beans.add(bean.get("name").asText() + "|"
                    + (bean.has("kind") ? bean.get("kind").asText() : "-") + "|"
                    + bean.get("javaType").asText() + "|" + bean.get("configuration"));
        }
        return beans;
    }

    private JsonNode configWrite(String json) throws Exception {
        StringPrinter printer = new StringPrinter();
        ConfigWriteCommand command = new ConfigWriteCommand(new CamelJBangMain().withPrinter(printer));
        set(command, "input", json);
        set(command, "directory", tempDir);
        set(command, "strategy", "application");
        assertThat(command.doCall()).isZero();
        return new ObjectMapper().readTree(printer.getOutput());
    }

    // key=value of application.properties, dependencies sorted and without versions
    private Set<String> written() throws Exception {
        Properties properties = new Properties();
        try (FileInputStream in = new FileInputStream(new File(tempDir, "application.properties"))) {
            properties.load(in);
        }
        return properties.stringPropertyNames().stream()
                .map(key -> key + "="
                        + Stream.of(properties.getProperty(key).split(","))
                                .map(value -> value.replaceAll(":[0-9][^:]*$", ""))
                                .sorted()
                                .collect(Collectors.joining(",")))
                .collect(Collectors.toSet());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
