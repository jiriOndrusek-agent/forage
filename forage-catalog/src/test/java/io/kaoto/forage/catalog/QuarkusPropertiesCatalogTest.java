package io.kaoto.forage.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import io.kaoto.forage.agent.AgentModuleDescriptor;
import io.kaoto.forage.jdbc.common.JdbcModuleDescriptor;
import io.kaoto.forage.jms.common.JmsModuleDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog's {@code quarkusProperty} / {@code quarkusUnit} are exactly the module descriptors'
 * {@code quarkusProperties()} lists, in both catalogs, and nothing else.
 */
class QuarkusPropertiesCatalogTest {

    private static final Path DIR = Path.of("target", "generated-catalog");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void catalogHasTheDescriptorLists() throws Exception {
        Map<String, String> expected = new TreeMap<>();
        new JdbcModuleDescriptor()
                .quarkusProperties()
                .forEach(p -> expected.put(
                        "forage.jdbc." + p.forageKey().replaceAll("\\.$", ""), p.quarkusKey() + "|" + p.unit()));
        new JmsModuleDescriptor()
                .quarkusProperties()
                .forEach(p -> expected.put("forage.jms." + p.forageKey(), p.quarkusKey() + "|" + p.unit()));

        assertThat(mapped("forage-catalog.json")).isEqualTo(expected);
        assertThat(mapped("forage-configuration-catalog.json")).isEqualTo(expected);
    }

    /** {@code quarkusIgnored} is exactly the descriptors' {@code quarkusIgnoredProperties()}, without a Quarkus key. */
    @Test
    void catalogHasTheIgnoredLists() throws Exception {
        Set<String> expected = new TreeSet<>();
        new JmsModuleDescriptor().quarkusIgnoredProperties().forEach(key -> expected.add("forage.jms." + key));
        assertThat(expected).contains("forage.jms.pool.connection.timeout.millis");

        assertThat(ignored("forage-catalog.json")).isEqualTo(expected);
        assertThat(ignored("forage-configuration-catalog.json")).isEqualTo(expected);
    }

    /** {@code quarkusHandled} is exactly the descriptors' {@code quarkusHandledProperties()}. */
    @Test
    void catalogHasTheHandledLists() throws Exception {
        Set<String> expected = new TreeSet<>();
        new JmsModuleDescriptor().quarkusHandledProperties().forEach(key -> expected.add("forage.jms." + key));
        assertThat(expected).contains("forage.jms.pool.enabled");

        for (String file : List.of("forage-catalog.json", "forage-configuration-catalog.json")) {
            Set<String> handled = new TreeSet<>();
            read(file)
                    .findParents("quarkusHandled")
                    .forEach(node -> handled.add(node.get("name").asText()));
            assertThat(handled).isEqualTo(expected);
        }
    }

    /** {@code quarkusSelector} is exactly the descriptors' {@code quarkusSelector()}. */
    @Test
    void catalogHasTheSelectors() throws Exception {
        var jdbc = new JdbcModuleDescriptor().quarkusSelector();
        var jms = new JmsModuleDescriptor().quarkusSelector();
        var agent = new AgentModuleDescriptor().quarkusSelector();
        Map<String, Object> expected = new TreeMap<>();
        expected.put("forage.jdbc." + jdbc.forageKey(), selector(jdbc.kinds(), jdbc.defaultOnly()));
        expected.put("forage.jms." + jms.forageKey(), selector(jms.kinds(), jms.defaultOnly()));
        Map<String, Object> agentSelector = selector(agent.kinds(), agent.defaultOnly());
        agentSelector.put("properties", agent.properties());
        agentSelector.put("dependencies", agent.dependencies());
        expected.put("forage.agent." + agent.forageKey(), agentSelector);
        assertThat(expected).containsKeys("forage.jdbc.db.kind", "forage.jms.kind", "forage.agent.model.kind");

        for (String file : List.of("forage-catalog.json", "forage-configuration-catalog.json")) {
            Map<String, Object> selectors = new TreeMap<>();
            read(file).findParents("quarkusSelector").forEach(node -> {
                Map<String, Object> selector = MAPPER.convertValue(node.get("quarkusSelector"), Map.class);
                if (selector.get("dependencies") instanceof Map<?, ?> dependencies) {
                    // the catalog build adds the versions
                    Map<Object, Object> versionless = new TreeMap<>();
                    dependencies.forEach((kind, gavs) -> versionless.put(
                            kind,
                            ((List<?>) gavs)
                                    .stream()
                                            .map(gav -> (String) gav)
                                            .peek(gav -> assertThat(gav).matches("mvn:[^:]+:[^:]+:[^:]+"))
                                            .map(gav -> gav.substring(0, gav.lastIndexOf(':')))
                                            .toList()));
                    selector.put("dependencies", versionless);
                }
                Object previous = selectors.put(node.get("name").asText(), selector);
                assertThat(previous).isIn(null, selectors.get(node.get("name").asText()));
            });
            assertThat(selectors).isEqualTo(expected);
        }
    }

    private static Map<String, Object> selector(Map<String, Map<String, String>> kinds, boolean defaultOnly) {
        Map<String, Object> selector = new TreeMap<>();
        if (kinds != null) {
            selector.put("kinds", kinds);
        }
        if (defaultOnly) {
            selector.put("defaultOnly", true);
        }
        return selector;
    }

    private static JsonNode read(String file) throws Exception {
        return MAPPER.readTree(Files.readString(DIR.resolve(file)));
    }

    /** Every config entry with quarkusIgnored=true (and no quarkusProperty). */
    private static Set<String> ignored(String file) throws Exception {
        Set<String> ignored = new TreeSet<>();
        new ObjectMapper()
                .readTree(Files.readString(DIR.resolve(file)))
                .findParents("quarkusIgnored")
                .forEach(node -> {
                    assertThat(node.get("quarkusIgnored").asBoolean()).isTrue();
                    assertThat(node.has("quarkusProperty")).isFalse();
                    ignored.add(node.get("name").asText());
                });
        return ignored;
    }

    /** Every config entry with a quarkusProperty: name -> quarkusProperty|quarkusUnit. */
    private static Map<String, String> mapped(String file) throws Exception {
        Map<String, String> mapped = new TreeMap<>();
        collect(new ObjectMapper().readTree(Files.readString(DIR.resolve(file))), mapped);
        return mapped;
    }

    private static void collect(JsonNode node, Map<String, String> mapped) {
        if (node.has("quarkusProperty")) {
            String value = node.get("quarkusProperty").asText() + "|"
                    + (node.has("quarkusUnit") ? node.get("quarkusUnit").asText() : "");
            String previous = mapped.put(node.get("name").asText(), value);
            assertThat(previous).isIn(null, value);
        }
        node.forEach(child -> collect(child, mapped));
    }
}
