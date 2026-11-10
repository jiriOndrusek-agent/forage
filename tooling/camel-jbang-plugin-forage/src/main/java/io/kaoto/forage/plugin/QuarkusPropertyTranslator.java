package io.kaoto.forage.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.kaoto.forage.catalog.reader.ForageCatalogReader;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.util.config.Config;
import io.kaoto.forage.core.util.config.ConfigHelper;
import io.kaoto.forage.core.util.config.ConfigResolver;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.core.util.config.PropertyFileLocator;
import io.kaoto.forage.plugin.ForagePropertyScanner.PropertyOccurrence;

/**
 * Translates forage properties to Quarkus-native properties at export time.
 * Uses the {@link ForageModuleDescriptor#translateProperties} logic of the Forage Quarkus extensions
 * by populating {@link ConfigStore} with scanned property values.
 *
 * <p>Only modules whose Quarkus extension creates the bean from {@code quarkus.*} properties are translated:
 * JDBC (Agroal) and JMS (Artemis), whose descriptors are registered as {@link ServiceLoader} services. Forage
 * creates the beans of the other modules (agent, CXF, Spring RabbitMQ, InfluxDB, TLS, security and route
 * policies, web search) from their {@code forage.*} properties at runtime, so these stay unchanged.
 *
 * <p>A configuration (module and named prefix) is translated only when the translation replaces all its
 * properties (see {@link ForageModuleDescriptor#replacedByTranslation}) and they all come from the exported
 * files, for every profile: no {@code {{...}}} placeholder, no non-ASCII character, no profile-specific value, no
 * environment variable, system property or file outside the export. Otherwise it stays unchanged, with a warning.
 * So does a default configuration next to named ones, which Forage ignores.
 */
public final class QuarkusPropertyTranslator {

    private static final Logger LOG = LoggerFactory.getLogger(QuarkusPropertyTranslator.class);

    private QuarkusPropertyTranslator() {}

    public record TranslationGroup(
            String moduleType, String prefix, Map<String, String> sourceProperties, Map<String, String> properties) {}

    public record TranslationResult(
            List<TranslationGroup> groups, Set<String> translatedForageKeys, List<String> warnings) {

        public boolean isEmpty() {
            return groups.isEmpty();
        }

        public int propertyCount() {
            return groups.stream().mapToInt(g -> g.properties().size()).sum();
        }
    }

    /**
     * Translates forage properties found in the working directory to Quarkus-native properties.
     *
     * @param workingDir the directory containing route and properties files
     * @return translation result with quarkus properties, the forage keys that were translated and the warnings
     *         for the configurations kept unchanged
     */
    @SuppressWarnings("rawtypes")
    public static TranslationResult translate(File workingDir) throws IOException {
        // 1. Scan working directory for forage.* properties across all files
        ForageCatalogReader catalog = ForageCatalogReader.getInstance();
        Map<String, Map<String, List<PropertyOccurrence>>> scanned =
                ForagePropertyScanner.scanPropertiesWithFileTracking(workingDir, catalog, true);
        Set<String> allKeys = new HashSet<>();
        scanned.values().forEach(byName -> byName.values()
                .forEach(occurrences -> occurrences.forEach(occ -> allKeys.add(occ.fullPropertyName()))));

        // 2. Discover the module descriptors of the translated modules (jdbc, jms) via ServiceLoader
        Map<String, ForageModuleDescriptor> descriptors = discoverDescriptors();
        Set<String> profileKeys = profileKeys(workingDir);

        // 3. For each translated module, group its properties by named prefix and translate
        List<TranslationGroup> groups = new ArrayList<>();
        Set<String> translatedForageKeys = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, Map<String, List<PropertyOccurrence>>> factoryEntry :
                new TreeMap<>(scanned).entrySet()) {
            String factoryTypeKey = factoryEntry.getKey();

            ForageModuleDescriptor descriptor = descriptors.get(factoryTypeKey);
            if (descriptor == null) {
                LOG.debug("No Quarkus translation for factory type '{}' — properties kept unchanged", factoryTypeKey);
                continue;
            }

            // Group by named prefix, e.g. "ds1" for forage.ds1.jdbc.*, null for forage.jdbc.*
            Map<String, Map<String, String>> byPrefix =
                    groupByPrefix(factoryEntry.getValue(), descriptor.modulePrefix());

            // Forage ignores the default configuration when named ones exist: translating the named ones only
            // would make it use the default one at runtime. Named ones are found as Forage does, dotted names too.
            String module = descriptor.modulePrefix();
            Pattern named = PropertyFileLocator.pattern(ConfigHelper.getNamedPropertyRegexp(module));
            if (byPrefix.containsKey(null)
                    && allKeys.stream().anyMatch(key -> named.matcher(key).matches())) {
                warnings.add("forage." + module + ".* and the named " + module + " configurations kept unchanged: "
                        + "Forage ignores forage." + module + ".* when named configurations exist");
                continue;
            }

            for (Map.Entry<String, Map<String, String>> prefixEntry : byPrefix.entrySet()) {
                String prefix = prefixEntry.getKey();
                Map<String, String> props = prefixEntry.getValue();

                TranslationGroup group = translatePrefixGroup(descriptor, prefix, props, profileKeys, warnings);
                if (group != null) {
                    groups.add(group);
                    translatedForageKeys.addAll(props.keySet());
                }
            }
        }

        return new TranslationResult(groups, translatedForageKeys, warnings);
    }

    /**
     * Translates a single prefix group's forage properties to Quarkus-native format.
     * Returns {@code null}, and adds a warning, if the group is kept unchanged.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TranslationGroup translatePrefixGroup(
            ForageModuleDescriptor descriptor,
            String prefix,
            Map<String, String> props,
            Set<String> profileKeys,
            List<String> warnings) {
        String keyPrefix = prefix == null
                ? "forage." + descriptor.modulePrefix() + "."
                : "forage." + prefix + "." + descriptor.modulePrefix() + ".";
        String group = keyPrefix + "*";

        // Quarkus cannot resolve {{...}} placeholders, Forage resolves them at runtime: keep them as they are
        List<String> placeholders = props.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().contains("{{"))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!placeholders.isEmpty()) {
            warnings.add(group + " kept unchanged: Forage resolves the {{...}} placeholders of "
                    + String.join(", ", placeholders) + " at runtime");
            return null;
        }

        // The scanner reads the files as ISO-8859-1, Quarkus reads application.properties as UTF-8
        List<String> nonAscii = props.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().chars().anyMatch(c -> c > 0x7e))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!nonAscii.isEmpty()) {
            warnings.add(group + " kept unchanged: non-ASCII characters in " + String.join(", ", nonAscii));
            return null;
        }

        // The base Quarkus properties would replace the values of the other profiles
        List<String> profileSpecific = profileKeys.stream()
                .filter(n -> n.startsWith(keyPrefix))
                .sorted()
                .toList();
        if (!profileSpecific.isEmpty()) {
            warnings.add(group + " kept unchanged: profile-specific values for " + String.join(", ", profileSpecific));
            return null;
        }

        // Environment variables and system properties of the export override the files: keep their values out
        String envPrefix = keyPrefix.replace('.', '_').toUpperCase(Locale.ROOT);
        List<String> overrides = Stream.concat(
                        System.getProperties().stringPropertyNames().stream().filter(n -> n.startsWith(keyPrefix)),
                        System.getenv().keySet().stream().filter(n -> n.startsWith(envPrefix)))
                .sorted()
                .toList();
        if (!overrides.isEmpty()) {
            warnings.add(group + " kept unchanged: overridden by " + String.join(", ", overrides) + " when exporting");
            return null;
        }

        try {
            ConfigStore.getInstance().reload();
            Config config = descriptor.createConfig(prefix);
            props.forEach(config::register);

            // The configuration also reads files outside the export (e.g. ~/.forage): keep their values out
            ConfigStore store = ConfigStore.getInstance();
            Set<String> external = new TreeSet<>();
            store.propertyNames().stream()
                    .filter(n -> n.startsWith(keyPrefix) && !props.containsKey(n))
                    .filter(n -> !store.getByPropertyName(n).orElse("").isBlank())
                    .forEach(external::add);
            // Keys read through a resolver without being stored, e.g. driver properties
            String keyRegexp = Pattern.quote(keyPrefix) + "(.+)";
            for (ConfigResolver resolver : store.getResolvers()) {
                resolver.discoverPrefixes(keyRegexp).stream()
                        .map(suffix -> keyPrefix + suffix)
                        .filter(n -> !props.containsKey(n))
                        .filter(n -> !resolver.resolve(n).orElse("").isBlank())
                        .forEach(external::add);
            }
            if (!external.isEmpty()) {
                warnings.add(
                        group + " kept unchanged: " + String.join(", ", external) + " set outside the exported files");
                return null;
            }

            List<String> notReplaced = props.keySet().stream()
                    .filter(name -> !descriptor.replacedByTranslation(prefix, config, name))
                    .sorted()
                    .toList();
            if (!notReplaced.isEmpty()) {
                warnings.add(group + " kept unchanged: no 1:1 Quarkus property for " + String.join(", ", notReplaced));
                return null;
            }

            Map<String, String> translated = descriptor.translateProperties(prefix, config);
            if (translated.isEmpty()) {
                return null;
            }
            return new TranslationGroup(descriptor.modulePrefix(), prefix, props, translated);
        } catch (Exception e) {
            warnings.add(group + " kept unchanged: " + e.getMessage());
            LOG.debug("Translation error details:", e);
            return null;
        }
    }

    /**
     * Returns the forage properties of the {@code application-<profile>.properties} files, and the
     * {@code %<profile>.forage.*} properties of {@code application.properties}, without the profile.
     */
    private static Set<String> profileKeys(File dir) throws IOException {
        Set<String> keys = new HashSet<>();
        File[] files = dir.listFiles((d, name) -> name.startsWith("application") && name.endsWith(".properties"));
        if (files == null) {
            return keys;
        }
        for (File file : files) {
            Properties props = new Properties();
            try (InputStream in = new FileInputStream(file)) {
                props.load(in);
            }
            boolean profileFile = !file.getName().equals("application.properties");
            for (String name : props.stringPropertyNames()) {
                if (profileFile && name.startsWith("forage.")) {
                    keys.add(name);
                } else if (name.startsWith("%") && name.contains(".forage.")) {
                    keys.add(name.substring(name.indexOf('.') + 1));
                }
            }
        }
        return keys;
    }

    @SuppressWarnings("rawtypes")
    private static Map<String, ForageModuleDescriptor> discoverDescriptors() {
        Map<String, ForageModuleDescriptor> result = new HashMap<>();
        try {
            ServiceLoader<ForageModuleDescriptor> loader =
                    ServiceLoader.load(ForageModuleDescriptor.class, QuarkusPropertyTranslator.class.getClassLoader());
            for (ForageModuleDescriptor descriptor : loader) {
                String prefix = descriptor.modulePrefix();
                result.put(prefix, descriptor);
                LOG.debug(
                        "Discovered ForageModuleDescriptor: {} → {}",
                        prefix,
                        descriptor.getClass().getName());
            }
        } catch (Exception e) {
            LOG.warn("Error discovering ForageModuleDescriptors: {}", e.getMessage());
            LOG.debug("ServiceLoader error details:", e);
        }
        return result;
    }

    /**
     * Groups scanned properties by their named prefix.
     *
     * <p>For {@code "forage.ds1.jdbc.url=..."} with modulePrefix {@code "jdbc"},
     * the prefix is {@code "ds1"} and the full key {@code "forage.ds1.jdbc.url"} is preserved.
     *
     * <p>For {@code "forage.jdbc.url=..."} the prefix is {@code null}.
     *
     * @return map of prefix (nullable) → (fullPropertyName → value)
     */
    private static Map<String, Map<String, String>> groupByPrefix(
            Map<String, List<PropertyOccurrence>> factoryProperties, String modulePrefix) {
        Map<String, Map<String, String>> result = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));

        for (List<PropertyOccurrence> occurrences : factoryProperties.values()) {
            for (PropertyOccurrence occ : occurrences) {
                String prefix = extractNamedPrefix(occ.fullPropertyName(), modulePrefix);
                // The scanner lists application.properties first: its values override the forage-*.properties ones
                result.computeIfAbsent(prefix, k -> new LinkedHashMap<>())
                        .putIfAbsent(occ.fullPropertyName(), occ.value());
            }
        }

        return result;
    }

    /**
     * Extracts the named prefix from a full forage property name.
     *
     * <p>Examples with modulePrefix {@code "jdbc"}:
     * <ul>
     *   <li>{@code "forage.jdbc.url"} → {@code null} (default instance)</li>
     *   <li>{@code "forage.ds1.jdbc.url"} → {@code "ds1"}</li>
     * </ul>
     */
    // Property format: forage.[<namedPrefix>.]<modulePrefix>.<property>
    // e.g., forage.jdbc.url → null (default), forage.ds1.jdbc.url → "ds1"
    static String extractNamedPrefix(String fullPropertyName, String modulePrefix) {
        if (!fullPropertyName.startsWith("forage.")) {
            return null;
        }
        String afterForage = fullPropertyName.substring("forage.".length());

        // Default instance: starts directly with modulePrefix (e.g. "jdbc.url")
        if (afterForage.startsWith(modulePrefix + ".") || afterForage.equals(modulePrefix)) {
            return null;
        }

        // Named instance: first segment is the prefix (e.g. "ds1" in "ds1.jdbc.url")
        int firstDot = afterForage.indexOf('.');
        if (firstDot > 0) {
            String remaining = afterForage.substring(firstDot + 1);
            if (remaining.startsWith(modulePrefix + ".") || remaining.equals(modulePrefix)) {
                return afterForage.substring(0, firstDot);
            }
        }

        return null;
    }
}
