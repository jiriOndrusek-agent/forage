package io.kaoto.forage.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
