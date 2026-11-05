package io.kaoto.forage.plugin;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import io.kaoto.forage.catalog.model.ConfigEntry;
import io.kaoto.forage.catalog.reader.ForageCatalogReader;

/**
 * Picks the factory of a key whose bean is listed by several factories, e.g. {@code forage.redis.host}
 * (Agent memory and Embedding Store): the factories whose bean declares the key, then the ones that
 * select the bean in the project (e.g. {@code forage.vectordb.kind=redis}), then the first one.
 */
public final class SharedBeans {
    private static final String FORAGE_PREFIX = "forage.";

    private final ForageCatalogReader catalog;
    // bean name -> factories selecting it with a bean-name property
    private final Map<String, Set<String>> selections = new HashMap<>();

    /**
     * @param properties all Forage properties of the project, full key to value
     */
    public SharedBeans(ForageCatalogReader catalog, Map<String, String> properties) {
        this.catalog = catalog;
        properties.forEach((key, value) -> {
            if (value != null && key.startsWith(FORAGE_PREFIX)) {
                ForagePropertyScanner.ParseResult parsed =
                        ForagePropertyScanner.parsePropertyKey(key.substring(FORAGE_PREFIX.length()), catalog, null);
                if (parsed != null && isBeanNameProperty(parsed.factoryTypeKey(), parsed.propertyName())) {
                    selections
                            .computeIfAbsent(value.trim().toLowerCase(), k -> new LinkedHashSet<>())
                            .add(parsed.factoryTypeKey());
                }
            }
        });
    }

    /**
     * The factory of a key that starts with a bean prefix.
     *
     * @param beanKey the key without {@code forage.} and the instance name (e.g. {@code redis.host})
     */
    public Optional<String> factoryForBeanKey(String beanName, String beanKey) {
        return choose(catalog.findFactoryTypeKeysForBeanName(beanName), beanName, FORAGE_PREFIX + beanKey);
    }

    /**
     * The factory of a bean name: the ones selecting it (e.g. {@code forage.vectordb.kind=redis}), then the first one.
     */
    public Optional<String> factoryForBean(String beanName) {
        return choose(catalog.findFactoryTypeKeysForBeanName(beanName), beanName, null);
    }

    /**
     * The factory of a config entry of a bean (e.g. {@code forage.redis.host} of {@code redis}).
     */
    public Optional<String> factoryForConfigEntry(String configEntryName, String beanName) {
        return choose(catalog.findFactoryTypeKeysForConfigEntry(configEntryName), beanName, configEntryName);
    }

    private Optional<String> choose(List<String> factories, String beanName, String configEntryName) {
        List<String> candidates = factories;
        if (candidates.size() > 1 && configEntryName != null) {
            List<String> declaring = catalog.findFactoryTypeKeysForConfigEntry(configEntryName);
            List<String> narrowed =
                    candidates.stream().filter(declaring::contains).toList();
            candidates = narrowed.isEmpty() ? candidates : narrowed;
        }
        if (candidates.size() > 1 && beanName != null) {
            Set<String> selecting = selections.getOrDefault(beanName.toLowerCase(), Set.of());
            List<String> narrowed =
                    candidates.stream().filter(selecting::contains).toList();
            candidates = narrowed.isEmpty() ? candidates : narrowed;
        }
        return candidates.stream().findFirst();
    }

    private boolean isBeanNameProperty(String factoryTypeKey, String propertyName) {
        List<ConfigEntry> entries = catalog.getFactoryMetadata(factoryTypeKey)
                .map(ForageCatalogReader.FactoryMetadata::configEntries)
                .orElse(null);
        return entries != null
                && entries.stream()
                        .filter(entry -> "bean-name".equals(entry.getType()) && entry.getName() != null)
                        .anyMatch(entry -> entry.getName().equals(FORAGE_PREFIX + factoryTypeKey + "." + propertyName));
    }
}
