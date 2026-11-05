package io.kaoto.forage.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import io.kaoto.forage.catalog.reader.ForageCatalogReader;

/**
 * Scans a directory for forage properties files and extracts property values
 * grouped by factory type key.
 */
public final class ForagePropertyScanner {

    private static final Pattern FORAGE_PROPERTY_PATTERN = Pattern.compile("^forage\\.(.+)$");
    private static final String PROFILE_PROPERTY = "camel.main.profile";
    private static final String PROFILE_ENV_VAR = "CAMEL_MAIN_PROFILE";

    private ForagePropertyScanner() {}

    /**
     * Tracks where a property was defined (for validation purposes).
     */
    public record PropertyOccurrence(File file, String fullPropertyName, String value) {}

    /**
     * Scans the given directory for forage properties and returns them grouped by factory type key.
     * Each factory key maps to a property name to a list of all values found for that property
     * (supporting multiple named instances like ds1.jdbc.db.kind=mysql, ds2.jdbc.db.kind=postgresql).
     *
     * @param directory the directory to scan
     * @param catalog the catalog reader
     * @return map of factoryTypeKey -> (propertyName -> [values])
     */
    public static Map<String, Map<String, List<String>>> scanProperties(File directory, ForageCatalogReader catalog)
            throws IOException {
        // Delegate to scanPropertiesWithFileTracking and convert PropertyOccurrence to String
        Map<String, Map<String, List<PropertyOccurrence>>> withTracking =
                scanPropertiesWithFileTracking(directory, catalog, false);

        Map<String, Map<String, List<String>>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, List<PropertyOccurrence>>> factoryEntry : withTracking.entrySet()) {
            String factoryKey = factoryEntry.getKey();

            Map<String, List<String>> propertyMap = new HashMap<>();
            for (Map.Entry<String, List<PropertyOccurrence>> propEntry :
                    factoryEntry.getValue().entrySet()) {
                List<String> values = propEntry.getValue().stream()
                        .map(PropertyOccurrence::value)
                        .toList();
                propertyMap.put(propEntry.getKey(), values);
            }
            result.put(factoryKey, propertyMap);
        }

        return result;
    }

    /**
     * Scans properties files and tracks where each property is defined.
     * Returns a map of factory type key to properties, plus optionally a special "__unknown__" key for unparseable properties.
     *
     * @param directory the directory to scan
     * @param catalog the catalog reader
     * @param trackUnknown if true, unparseable forage properties are tracked in "__unknown__" bucket
     * @return map of factoryTypeKey -> (propertyName -> [PropertyOccurrence])
     * @throws IOException if properties files cannot be read
     */
    public static Map<String, Map<String, List<PropertyOccurrence>>> scanPropertiesWithFileTracking(
            File directory, ForageCatalogReader catalog, boolean trackUnknown) throws IOException {

        Map<String, Map<String, List<PropertyOccurrence>>> result = new HashMap<>();

        // Load application properties with profile override semantics (like Camel does):
        // scan base first, then profile file — profile occurrences replace base ones per property key
        File applicationPropsFile = loadApplicationProperties(directory);
        Properties baseProps = applicationPropsFile != null ? load(applicationPropsFile) : null;

        String profile = resolveProfile(baseProps);
        File profileFile = profile != null ? new File(directory, "application-" + profile + ".properties") : null;
        Properties profileProps = profileFile != null && profileFile.isFile() ? load(profileFile) : null;

        // Load forage-specific and catalog-named properties files independently
        Map<File, Properties> nonAppProps = new LinkedHashMap<>();
        for (File file : findNonApplicationPropertiesFiles(directory, catalog)) {
            nonAppProps.put(file, load(file));
        }

        // The kinds selected in the project decide which factory owns the keys of a shared bean name
        Map<String, String> allProps = new HashMap<>();
        for (Properties props : Stream.concat(Stream.of(baseProps, profileProps), nonAppProps.values().stream())
                .filter(Objects::nonNull)
                .toList()) {
            props.stringPropertyNames().forEach(key -> allProps.put(key, props.getProperty(key)));
        }
        SharedBeans shared = new SharedBeans(catalog, allProps);

        if (baseProps != null) {
            collectForageProperties(baseProps, applicationPropsFile, catalog, shared, trackUnknown, result);
        }
        if (profileProps != null) {
            // Profile properties override base: replace matching occurrences
            overrideForageProperties(profileProps, profileFile, catalog, shared, trackUnknown, result);
        }
        for (Map.Entry<File, Properties> file : nonAppProps.entrySet()) {
            collectForageProperties(file.getValue(), file.getKey(), catalog, shared, trackUnknown, result);
        }

        return result;
    }

    /**
     * Scans properties files and tracks where each property is defined.
     * Unparseable properties are tracked in "__unknown__" bucket.
     *
     * @param directory the directory to scan
     * @param catalog the catalog reader
     * @return map of factoryTypeKey -> (propertyName -> [PropertyOccurrence])
     * @throws IOException if properties files cannot be read
     */
    public static Map<String, Map<String, List<PropertyOccurrence>>> scanPropertiesWithFileTracking(
            File directory, ForageCatalogReader catalog) throws IOException {
        return scanPropertiesWithFileTracking(directory, catalog, true);
    }

    private static Properties load(File file) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        }
        return props;
    }

    private static void collectForageProperties(
            Properties props,
            File sourceFile,
            ForageCatalogReader catalog,
            SharedBeans shared,
            boolean trackUnknown,
            Map<String, Map<String, List<PropertyOccurrence>>> result) {

        for (String key : props.stringPropertyNames()) {
            Matcher matcher = FORAGE_PROPERTY_PATTERN.matcher(key);
            if (!matcher.matches()) {
                continue;
            }

            String remainder = matcher.group(1);
            ParseResult parsed = parsePropertyKey(remainder, catalog, shared);

            String value = props.getProperty(key);
            PropertyOccurrence occurrence = new PropertyOccurrence(sourceFile, key, value);

            if (parsed == null) {
                if (trackUnknown) {
                    result.computeIfAbsent("__unknown__", k -> new HashMap<>())
                            .computeIfAbsent(remainder, k -> new ArrayList<>())
                            .add(occurrence);
                }
                continue;
            }

            result.computeIfAbsent(parsed.factoryTypeKey, k -> new HashMap<>())
                    .computeIfAbsent(parsed.propertyName, k -> new ArrayList<>())
                    .add(occurrence);
        }
    }

    private static void overrideForageProperties(
            Properties profileProps,
            File profileFile,
            ForageCatalogReader catalog,
            SharedBeans shared,
            boolean trackUnknown,
            Map<String, Map<String, List<PropertyOccurrence>>> result) {

        for (String key : profileProps.stringPropertyNames()) {
            Matcher matcher = FORAGE_PROPERTY_PATTERN.matcher(key);
            if (!matcher.matches()) {
                continue;
            }

            String remainder = matcher.group(1);
            ParseResult parsed = parsePropertyKey(remainder, catalog, shared);

            String value = profileProps.getProperty(key);
            PropertyOccurrence occurrence = new PropertyOccurrence(profileFile, key, value);

            if (parsed == null) {
                if (trackUnknown) {
                    replaceBaseOccurrence("__unknown__", remainder, key, occurrence, result);
                }
                continue;
            }

            replaceBaseOccurrence(parsed.factoryTypeKey, parsed.propertyName, key, occurrence, result);
        }
    }

    // Override is only called after collectForageProperties has processed application.properties,
    // and before non-application files (forage-*.properties) are scanned. This ordering guarantees
    // that removeIf only removes base application.properties occurrences, never forage-*.properties ones.
    private static void replaceBaseOccurrence(
            String groupKey,
            String propertyName,
            String fullPropertyName,
            PropertyOccurrence occurrence,
            Map<String, Map<String, List<PropertyOccurrence>>> result) {

        Map<String, List<PropertyOccurrence>> groupMap = result.computeIfAbsent(groupKey, k -> new HashMap<>());
        List<PropertyOccurrence> occurrences = groupMap.get(propertyName);
        if (occurrences != null) {
            occurrences.removeIf(o -> o.fullPropertyName().equals(fullPropertyName));
        }
        groupMap.computeIfAbsent(propertyName, k -> new ArrayList<>()).add(occurrence);
    }

    private static File loadApplicationProperties(File dir) {
        File file = new File(dir, "application.properties");
        return file.isFile() ? file : null;
    }

    static String resolveProfile(Properties applicationProps) {
        String profile = resolveProfileFromEnvironment();
        if (profile != null) {
            return profile;
        }

        if (applicationProps != null) {
            profile = applicationProps.getProperty(PROFILE_PROPERTY);
        }
        return profile;
    }

    private static String resolveProfileFromEnvironment() {
        String profile = System.getProperty(PROFILE_PROPERTY);
        if (profile != null) {
            return profile;
        }
        return System.getenv(PROFILE_ENV_VAR);
    }

    private static List<File> findNonApplicationPropertiesFiles(File dir, ForageCatalogReader catalog)
            throws IOException {
        Set<String> catalogFileNames = new java.util.HashSet<>();

        for (ForageCatalogReader.FactoryMetadata metadata : catalog.getAllFactories()) {
            String propsFile = metadata.propertiesFileName();
            if (propsFile != null && !propsFile.isEmpty()) {
                catalogFileNames.add(propsFile);
            }
        }

        try (Stream<Path> paths = Files.walk(dir.toPath(), 1)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String fileName = p.getFileName().toString();
                        return catalogFileNames.contains(fileName)
                                || (fileName.startsWith("forage-") && fileName.endsWith(".properties"));
                    })
                    .map(Path::toFile)
                    .toList();
        }
    }

    /**
     * @param shared picks the factory of a bean name listed by several factories; {@code null} takes the first
     */
    static ParseResult parsePropertyKey(String key, ForageCatalogReader catalog, SharedBeans shared) {
        String[] parts = key.split("\\.", 2);
        if (parts.length < 2) {
            return null;
        }

        String firstPart = parts[0];
        String restOfKey = parts[1];

        // Check if the first part is a known factory type key, unless it names an instance of
        // another factory (e.g. forage.embeddings.jdbc.url); a factory with keys besides its kind keeps
        // them (e.g. forage.agent.embeddings.kind)
        Optional<ForageCatalogReader.FactoryMetadata> factory = catalog.getFactoryMetadata(firstPart);
        if (factory.isPresent()) {
            String[] restParts = restOfKey.split("\\.", 2);
            if (restParts.length == 2
                    && factory.get().readsOnlyKind()
                    && catalog.getFactoryMetadata(restParts[0]).isPresent()) {
                return new ParseResult(restParts[0], restParts[1]);
            }
            return new ParseResult(firstPart, restOfKey);
        }

        // Check if the first part is a known bean type
        Optional<String> beanFactory = factoryForBean(firstPart, key, catalog, shared);
        if (beanFactory.isPresent()) {
            return new ParseResult(beanFactory.get(), key);
        }

        // The first part might be an instance name, check second segment
        String[] restParts = restOfKey.split("\\.", 2);
        if (restParts.length >= 1) {
            String secondPart = restParts[0];
            String propertyName = restParts.length > 1 ? restParts[1] : "";

            if (catalog.getFactoryMetadata(secondPart).isPresent()) {
                return new ParseResult(secondPart, propertyName);
            }

            Optional<String> beanFactory2 = factoryForBean(secondPart, restOfKey, catalog, shared);
            if (beanFactory2.isPresent()) {
                return new ParseResult(beanFactory2.get(), restOfKey);
            }
        }

        // Try property prefix mapping
        Optional<String> factoryType = catalog.findFactoryTypeKeyForPropertyPrefix(firstPart);
        return factoryType.map(s -> new ParseResult(s, key)).orElse(null);
    }

    private static Optional<String> factoryForBean(
            String beanName, String beanKey, ForageCatalogReader catalog, SharedBeans shared) {
        return shared != null
                ? shared.factoryForBeanKey(beanName, beanKey)
                : catalog.findFactoryTypeKeyForBeanName(beanName);
    }

    record ParseResult(String factoryTypeKey, String propertyName) {}
}
