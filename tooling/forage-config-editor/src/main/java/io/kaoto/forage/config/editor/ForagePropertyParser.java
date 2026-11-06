package io.kaoto.forage.config.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.kaoto.forage.catalog.model.ConfigEntry;
import io.kaoto.forage.catalog.reader.ForageCatalogReader;

/**
 * Resolves which factory, instance and bean a {@code forage.*} property belongs to.
 *
 * <p>Keys are matched against the catalog's config entries the way the runtime resolves them:
 * {@code forage.<instance>.<config entry>}, where the instance name is optional and may contain dots. This covers
 * default instances ({@code forage.jdbc.url}), named instances ({@code forage.ds1.jdbc.url}), dotted names
 * ({@code forage.my.ds.jdbc.url}) and bean prefixes that differ from the bean name ({@code forage.google.api.key}).
 * Keys that match no config entry (e.g. typos or dynamic route policy keys) fall back to segment heuristics.
 *
 * <p>This class has no CLI dependencies, so it can be reused outside the {@code config} commands.
 */
public final class ForagePropertyParser {

    /**
     * Factory properties whose value names the bean kind of the instance (e.g. {@code db.kind=postgresql},
     * {@code kind=artemis}), used to report the kind of a bean. See {@link #selectedBeans(ParsedProperty, String)}
     * for every property whose value selects a bean.
     */
    public static final Set<String> KIND_PROPERTIES = Set.of("db.kind", "kind");

    private static final String BEAN_NAME_TYPE = "bean-name";
    private static final String KIND_SEGMENT = "kind";

    private static final String FORAGE_PREFIX = "forage.";
    private static final Pattern FORAGE_PROPERTY_PATTERN = Pattern.compile("^forage\\.(.+)$");

    private final ForageCatalogReader catalog;
    // bean name (lower case) -> factories whose bean-name entry selects it in the project
    private final Map<String, Set<String>> selections;

    public ForagePropertyParser(ForageCatalogReader catalog) {
        this(catalog, Map.of());
    }

    private ForagePropertyParser(ForageCatalogReader catalog, Map<String, Set<String>> selections) {
        this.catalog = catalog;
        this.selections = selections;
    }

    /**
     * Returns a parser that gives a key of a bean listed by several factories (e.g. {@code forage.redis.host}: Agent
     * memory and Embedding Store) to the factories whose bean declares the key, then to the ones selecting the bean in
     * the given properties (e.g. {@code forage.vectordb.kind=redis}), then to the first one.
     *
     * @param properties all the {@code forage.*} properties of the project, full key to value
     * @return the parser
     */
    public ForagePropertyParser withSelections(Map<String, String> properties) {
        Map<String, Set<String>> beanSelections = new HashMap<>();
        properties.forEach((key, value) -> parse(key).ifPresent(parsed -> findBeanNameEntry(parsed)
                .ifPresent(entry -> beanNames(entry, value).forEach(bean -> beanSelections
                        .computeIfAbsent(bean.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                        .add(parsed.factoryType())))));
        return new ForagePropertyParser(catalog, beanSelections);
    }

    /**
     * The factory of a bean name listed by several factories: the ones selecting it (see
     * {@link #withSelections(Map)}), then the first one.
     *
     * @param beanName the bean name (e.g. {@code redis})
     * @return the factory type key, or empty if no factory lists the bean
     */
    public Optional<String> factoryOfBean(String beanName) {
        return choose(catalog.findFactoryTypeKeysForBeanName(beanName), beanName, null);
    }

    /**
     * A parsed {@code forage.*} property.
     *
     * @param factoryType the factory type key (e.g. {@code jdbc}, {@code agent})
     * @param instanceName the instance name, or {@code null} for the default instance
     * @param beanType the bean the property configures (e.g. {@code ollama}), or {@code null}
     * @param propertyName the property name relative to the factory or bean (e.g. {@code url}, {@code model.name})
     * @param entryKey the key without {@code forage.} and the instance name (e.g. {@code ollama.model.name})
     */
    public record ParsedProperty(
            String factoryType, String instanceName, String beanType, String propertyName, String entryKey) {

        /**
         * @return the same property, for another instance
         */
        public ParsedProperty withInstanceName(String name) {
            return new ParsedProperty(factoryType, name, beanType, propertyName, entryKey);
        }
    }

    /**
     * Parses a full property key.
     *
     * @param key the property key (e.g. {@code forage.ds1.jdbc.url})
     * @return the parsed property, or empty if the key is not a recognised Forage property
     */
    public Optional<ParsedProperty> parse(String key) {
        List<ParsedProperty> candidates = candidates(key);
        return candidates.isEmpty() ? parseHeuristically(key) : Optional.of(candidates.get(0));
    }

    /**
     * Returns every way the key reads as {@code forage.<instance>.<config entry>} with a catalog config entry, the
     * default instance first, then by increasing instance name length. A key with more than one candidate is
     * ambiguous: for example {@code forage.azure.openai.api.key} is both the default Azure OpenAI entry and the
     * {@code openai.api.key} entry of an instance named {@code azure}.
     *
     * @param key the full property key
     * @return the candidates, empty if no suffix of the key is a catalog config entry
     */
    public List<ParsedProperty> candidates(String key) {
        Matcher matcher = FORAGE_PROPERTY_PATTERN.matcher(key);
        if (!matcher.matches()) {
            return List.of();
        }
        List<ParsedProperty> candidates = new ArrayList<>();
        String instanceName = null;
        String entryKey = matcher.group(1);
        while (true) {
            Optional<ForageCatalogReader.ConfigEntryOwner> owner =
                    catalog.findConfigEntryOwner(FORAGE_PREFIX + entryKey);
            if (owner.isPresent() && !keptByFactory(lastSegment(instanceName), entryKey)) {
                candidates.add(toParsedProperty(instanceName, entryKey, owner.get()));
            }
            int dot = entryKey.indexOf('.');
            if (dot < 0) {
                return candidates;
            }
            String segment = entryKey.substring(0, dot);
            instanceName = instanceName == null ? segment : instanceName + "." + segment;
            entryKey = entryKey.substring(dot + 1);
        }
    }

    /**
     * Parses a key that no catalog config entry matches (e.g. a typo) with segment heuristics: a factory key or bean
     * name in the first or second segment.
     *
     * @param key the full property key
     * @return the parsed property, or empty if the heuristics recognise nothing
     */
    public Optional<ParsedProperty> parseHeuristically(String key) {
        Matcher matcher = FORAGE_PROPERTY_PATTERN.matcher(key);
        return matcher.matches() ? Optional.ofNullable(parseUsingSegments(matcher.group(1))) : Optional.empty();
    }

    /**
     * Whether the key is a config entry declared in the catalog, exactly as written (e.g. {@code forage.jdbc.url},
     * {@code forage.openai.api.key}).
     *
     * @param key the full property key
     * @return {@code true} if a factory or bean declares the entry
     */
    public boolean isConfigEntry(String key) {
        return catalog.findConfigEntryOwner(key).isPresent();
    }

    /**
     * Returns the beans a property's value selects, by name: the value of a catalog {@code bean-name} entry
     * (e.g. {@code db.kind}, {@code agent.model.kind}, {@code agent.memory.kind}), split on commas for a
     * multi-value entry (e.g. {@code route.policy.<routeId>.name=schedule,flip}), and the value of any other
     * {@code *.kind} property (e.g. {@code cxf.kind=soap}).
     *
     * @param parsed the parsed property
     * @param value the property value
     * @return the selected bean names, empty if the property does not select a bean
     */
    public List<String> selectedBeans(ParsedProperty parsed, String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Optional<ConfigEntry> beanNameEntry = findBeanNameEntry(parsed);
        if (beanNameEntry.isPresent()) {
            return beanNames(beanNameEntry.get(), value);
        }
        String propertyName = parsed.propertyName();
        if (propertyName.equals(KIND_SEGMENT) || propertyName.endsWith("." + KIND_SEGMENT)) {
            return List.of(value.trim());
        }
        return List.of();
    }

    private static List<String> beanNames(ConfigEntry beanNameEntry, String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return beanNameEntry.isMultiValue() ? PropertyKeys.splitValues(value) : List.of(value.trim());
    }

    private static String lastSegment(String instanceName) {
        return instanceName == null ? null : instanceName.substring(instanceName.lastIndexOf('.') + 1);
    }

    /**
     * Whether a key after a factory type key stays with that factory: a factory with keys besides its kind keeps the
     * keys of a factory that only reads its kind, like an embedding model or store (e.g.
     * {@code forage.agent.embeddings.kind} is an Agent key, not the embedding model kind of an instance named
     * {@code agent}). Other keys are those of an instance named like the factory (e.g.
     * {@code forage.embeddings.jdbc.url}, {@code forage.keycloak.jdbc.url}).
     */
    private boolean keptByFactory(String factoryTypeKey, String key) {
        if (factoryTypeKey == null) {
            return false;
        }
        String[] parts = key.split("\\.", 2);
        return parts.length == 2
                && catalog.getFactoryMetadata(factoryTypeKey)
                        .filter(factory -> !factory.readsOnlyKind())
                        .isPresent()
                && catalog.getFactoryMetadata(parts[0])
                        .filter(ForageCatalogReader.FactoryMetadata::readsOnlyKind)
                        .isPresent();
    }

    /**
     * Narrows the factories listing a bean (or declaring a key) to those whose bean declares the key, then to those
     * selecting the bean, then takes the first.
     */
    private Optional<String> choose(List<String> factories, String beanName, String configEntryName) {
        List<String> candidates = factories;
        if (candidates.size() > 1 && configEntryName != null) {
            List<String> declaring = catalog.findFactoryTypeKeysForConfigEntry(configEntryName);
            candidates = narrow(candidates, declaring::contains);
        }
        if (candidates.size() > 1 && beanName != null) {
            Set<String> selecting = selections.getOrDefault(beanName.toLowerCase(Locale.ROOT), Set.of());
            candidates = narrow(candidates, selecting::contains);
        }
        return candidates.stream().findFirst();
    }

    private static List<String> narrow(List<String> candidates, Predicate<String> filter) {
        List<String> narrowed = candidates.stream().filter(filter).toList();
        return narrowed.isEmpty() ? candidates : narrowed;
    }

    private Optional<String> factoryOfBeanKey(String beanName, String beanKey) {
        return choose(catalog.findFactoryTypeKeysForBeanName(beanName), beanName, FORAGE_PREFIX + beanKey);
    }

    private Optional<ConfigEntry> findBeanNameEntry(ParsedProperty parsed) {
        return catalog.getFactoryMetadata(parsed.factoryType())
                .map(ForageCatalogReader.FactoryMetadata::configEntries)
                .flatMap(entries -> entries.stream()
                        .filter(entry -> BEAN_NAME_TYPE.equals(entry.getType()))
                        .filter(entry ->
                                entry.getName() != null && entry.getName().startsWith(FORAGE_PREFIX))
                        .filter(entry -> PropertyKeys.matchesExactly(
                                parsed.entryKey(), entry.getName().substring(FORAGE_PREFIX.length())))
                        .findFirst());
    }

    private ParsedProperty toParsedProperty(
            String instanceName, String entryKey, ForageCatalogReader.ConfigEntryOwner owner) {
        // A key of a bean listed by several factories goes to the factory selecting the bean
        String factoryTypeKey = choose(
                        catalog.findFactoryTypeKeysForConfigEntry(FORAGE_PREFIX + entryKey),
                        owner.beanName(),
                        FORAGE_PREFIX + entryKey)
                .orElse(owner.factoryTypeKey());
        String factoryPrefix = factoryTypeKey + ".";
        // Factory-level property (e.g., "jdbc.url", "jms.artemis.reconnect.attempts"), unless the
        // factory key is really a bean prefix (e.g., the security policy factory keyed as "keycloak")
        if (entryKey.startsWith(factoryPrefix)
                && catalog.findBeanNameForPropertyPrefix(factoryTypeKey).isEmpty()) {
            return new ParsedProperty(
                    factoryTypeKey, instanceName, null, entryKey.substring(factoryPrefix.length()), entryKey);
        }
        // Bean property (e.g., "ollama.model.name", "azure.openai.api.key")
        if (owner.beanName() != null) {
            Optional<String> beanPrefix = catalog.findBeanPropertyPrefix(owner.beanName(), entryKey);
            if (beanPrefix.isPresent()) {
                return new ParsedProperty(
                        factoryTypeKey,
                        instanceName,
                        owner.beanName(),
                        entryKey.substring(beanPrefix.get().length() + 1),
                        entryKey);
            }
        }
        // Factory property outside the factory namespace (e.g., "multi.agent.names", "guardrail.*")
        return new ParsedProperty(factoryTypeKey, instanceName, null, entryKey, entryKey);
    }

    /**
     * Segment heuristics for keys that match no catalog config entry.
     */
    private ParsedProperty parseUsingSegments(String key) {
        String[] parts = key.split("\\.", 2);
        if (parts.length < 2) {
            return null;
        }
        String firstPart = parts[0];
        String restOfKey = parts[1];

        // Default instance of a factory (e.g., "jdbc.url"), unless the key names an instance of another factory
        // (e.g., "embeddings.jdbc.urll"); see keptByFactory
        String[] restParts = restOfKey.split("\\.", 2);
        if (catalog.getFactoryMetadata(firstPart).isPresent()) {
            if (restParts.length == 2
                    && catalog.getFactoryMetadata(restParts[0]).isPresent()
                    && !keptByFactory(firstPart, restOfKey)) {
                return new ParsedProperty(restParts[0], firstPart, null, restParts[1], restOfKey);
            }
            return new ParsedProperty(firstPart, null, null, restOfKey, key);
        }
        // Default instance of a bean (e.g., "ollama.model.name")
        Optional<String> beanFactory = factoryOfBeanKey(firstPart, key);
        if (beanFactory.isPresent()) {
            return new ParsedProperty(beanFactory.get(), null, firstPart, restOfKey, key);
        }

        // Named instance: check the second segment for a factory or bean
        String secondPart = restParts[0];
        String propertyName = restParts.length > 1 ? restParts[1] : "";
        if (catalog.getFactoryMetadata(secondPart).isPresent()) {
            return new ParsedProperty(secondPart, firstPart, null, propertyName, restOfKey);
        }
        Optional<String> beanFactory2 = factoryOfBeanKey(secondPart, restOfKey);
        if (beanFactory2.isPresent()) {
            return new ParsedProperty(beanFactory2.get(), firstPart, secondPart, propertyName, restOfKey);
        }

        // Bean-specific prefix mapped to a factory (e.g., "google.api.key")
        return catalog.findBeanNameForPropertyPrefix(firstPart)
                .flatMap(beanName -> factoryOfBeanKey(beanName, key))
                .or(() -> catalog.findFactoryTypeKeyForPropertyPrefix(firstPart))
                .map(factory -> new ParsedProperty(factory, null, null, key, key))
                .orElse(null);
    }
}
