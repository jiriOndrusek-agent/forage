package io.kaoto.forage.plugin;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.util.config.Config;
import io.kaoto.forage.core.util.config.ConfigHelper;
import io.kaoto.forage.core.util.config.ConfigModule;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.core.util.config.MissingConfigException;
import io.kaoto.forage.core.util.config.PropertyFileLocator;
import io.kaoto.forage.jdbc.common.JdbcModuleDescriptor;
import io.kaoto.forage.jms.common.ConnectionFactoryConfig;
import io.kaoto.forage.jms.common.JmsModuleDescriptor;

/**
 * Translates, for {@code camel export --runtime=quarkus}, the Forage configurations that Quarkus extensions fully
 * cover into native {@code quarkus.*} properties, so the exported application needs no Forage runtime for them.
 *
 * <p>A module is translated all or nothing: if any of its configurations needs Forage at runtime (transactions,
 * auxiliary repositories, {@code {{...}}} placeholders, profile-specific values, ...), the whole module stays
 * {@code forage.*}.
 */
final class QuarkusNativeExport {

    /**
     * The modules Quarkus covers natively. Named JMS brokers stay Forage: their per-broker components come from the
     * Forage extension.
     */
    private static final List<NativeModule> MODULES = List.of(
            new NativeModule(new JdbcModuleDescriptor(), true), new NativeModule(new JmsModuleDescriptor(), false));

    private record NativeModule(ForageModuleDescriptor<?, ?> descriptor, boolean namedSupported) {}

    /** The catalog group whose dependency (quarkus-pooled-jms) a pooled JMS connection factory needs. */
    static final String JMS_POOL_GROUP = "jms-connection-pool";

    /**
     * @param properties the translated Quarkus properties
     * @param forageKeys the Forage keys they replace
     * @param modules the translated module prefixes (their Forage dependencies are not needed)
     * @param groups the catalog conditional groups the translated configurations enable, also by default
     * @param messages what the export tells the user
     */
    record Result(
            Map<String, String> properties,
            Set<String> forageKeys,
            Set<String> modules,
            Set<String> groups,
            List<String> messages) {}

    private QuarkusNativeExport() {}

    /**
     * Loads the project properties: {@code application.properties} and the other {@code *.properties} files. Keys of
     * {@code application-<profile>.properties} files are returned with a {@code %<profile>.} prefix.
     */
    static Properties load(Path dir) throws IOException {
        Properties all = new Properties();
        if (!Files.isDirectory(dir)) {
            return all;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".properties"))
                    .sorted()
                    .toList()) {
                Properties props = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
                String name = file.getFileName().toString();
                String profile = name.startsWith("application-")
                        ? "%" + name.substring("application-".length(), name.length() - ".properties".length()) + "."
                        : "";
                props.stringPropertyNames().forEach(key -> all.setProperty(profile + key, props.getProperty(key)));
            }
        }
        return all;
    }

    /**
     * Translates the project properties. Only the values of the given properties are used: Forage overrides set in
     * the export environment ({@code FORAGE_*}, {@code -Dforage.*}) are ignored.
     */
    static Result translate(Properties project) {
        Properties base = new Properties();
        Properties profiled = new Properties();
        project.stringPropertyNames().forEach(key -> {
            if (key.startsWith("%")) {
                profiled.setProperty(key.substring(key.indexOf('.') + 1), project.getProperty(key));
            } else {
                base.setProperty(key, project.getProperty(key));
            }
        });

        Result result = new Result(
                new LinkedHashMap<>(),
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                new ArrayList<>());
        try {
            for (NativeModule module : MODULES) {
                translate(module.descriptor(), module.namedSupported(), base, profiled, result);
            }
        } finally {
            // drop the export-only values from the shared store
            ConfigStore.getInstance().reload();
        }
        return result;
    }

    private static <C extends Config> void translate(
            ForageModuleDescriptor<C, ?> descriptor,
            boolean namedSupported,
            Properties base,
            Properties profiled,
            Result result) {
        String module = descriptor.modulePrefix();
        String namedRegexp = ConfigHelper.getNamedPropertyRegexp(module);
        String defaultRegexp = ConfigHelper.getDefaultPropertyRegexp(module);
        Set<String> named = PropertyFileLocator.readPrefixes(base, namedRegexp);
        boolean hasDefault =
                !PropertyFileLocator.readPrefixes(base, defaultRegexp).isEmpty();
        if (named.isEmpty() && !hasDefault) {
            return;
        }

        String reason = null;
        if (!PropertyFileLocator.readPrefixes(profiled, namedRegexp).isEmpty()
                || !PropertyFileLocator.readPrefixes(profiled, defaultRegexp).isEmpty()) {
            reason = "profile-specific values";
        } else if (!named.isEmpty() && hasDefault) {
            reason = "default and named configurations together";
        } else if (!named.isEmpty() && !namedSupported) {
            reason = "named configurations need the Forage extension";
        }

        Map<String, String> quarkus = new TreeMap<>();
        Set<String> forageKeys = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        List<String> instanceMessages = new ArrayList<>();
        for (String name : named.isEmpty() ? Collections.<String>singletonList(null) : named) {
            if (reason != null) {
                break;
            }
            String label = name == null ? module : name;
            String keyPrefix = name == null ? "forage." + module + "." : "forage." + name + "." + module + ".";
            Map<String, String> values = base.stringPropertyNames().stream()
                    .filter(key -> key.startsWith(keyPrefix))
                    .collect(Collectors.toMap(key -> key, base::getProperty));

            if (values.values().stream().anyMatch(value -> value.contains("{{"))) {
                reason = "'" + label + "' uses {{...}} placeholders";
            } else {
                C config = descriptor.createConfig(name);
                useOnly(config, keyPrefix, values);
                boolean repositories =
                        !descriptor.auxiliaryBeans(name, beanName -> null).isEmpty();
                // auxiliaryBeans creates its own configuration, which reloads the export environment
                useOnly(config, keyPrefix, values);
                Map<String, String> translated = new TreeMap<>();
                if (repositories) {
                    reason = "'" + label + "' uses Forage repositories";
                } else if (descriptor.transactionEnabled(config)) {
                    reason = "'" + label + "' uses transactions";
                } else {
                    try {
                        translated.putAll(descriptor.translateProperties(name, config));
                    } catch (MissingConfigException | IllegalArgumentException e) {
                        reason = "'" + label + "': " + e.getMessage();
                    }
                    if (reason == null && translated.isEmpty()) {
                        reason = "'" + label + "' has no Quarkus equivalent";
                    }
                }
                if (reason == null && config instanceof ConnectionFactoryConfig jms) {
                    // pooling is on by default: quarkus-pooled-jms must be added, or its keys dropped
                    if (jms.poolEnabled()) {
                        groups.add(JMS_POOL_GROUP);
                    } else {
                        translated.keySet().removeIf(key -> key.startsWith("quarkus.pooled-jms."));
                    }
                }
                if (reason == null) {
                    quarkus.putAll(translated);
                    forageKeys.addAll(values.keySet());
                    instanceMessages.add(overrideMessage(module, label, keyPrefix, translated));
                }
            }
        }

        if (reason != null) {
            result.messages().add("Forage " + module + " stays as forage.* configuration: " + reason + ".");
            return;
        }
        result.properties().putAll(quarkus);
        result.forageKeys().addAll(forageKeys);
        result.modules().add(module);
        result.groups().addAll(groups);
        result.messages().addAll(instanceMessages);
    }

    /**
     * Makes the configuration use only the given values, dropping what the store loaded from the environment, system
     * properties or other locations.
     */
    private static void useOnly(Config config, String keyPrefix, Map<String, String> values) {
        ConfigStore store = ConfigStore.getInstance();
        for (Map.Entry<Object, Object> entry : store.entries()) {
            if (entry.getKey() instanceof ConfigModule configModule
                    && configModule.propertyName().startsWith(keyPrefix)) {
                store.set(configModule, null);
            }
        }
        values.forEach(config::register);
    }

    private static String overrideMessage(
            String module, String label, String keyPrefix, Map<String, String> translated) {
        String overrides = translated.keySet().stream()
                .sorted()
                .map(QuarkusNativeExport::envName)
                .collect(Collectors.joining(", "));
        String message = "Forage " + module + " '" + label + "' exported as Quarkus configuration: override it with "
                + overrides + " (" + envName(keyPrefix) + "* is not read).";
        boolean exportTimeOverrides = System.getenv().keySet().stream().anyMatch(n -> n.startsWith(envName(keyPrefix)))
                || System.getProperties().stringPropertyNames().stream().anyMatch(n -> n.startsWith(keyPrefix));
        return exportTimeOverrides
                ? message + " Ignored the " + envName(keyPrefix) + "* / " + keyPrefix
                        + "* values set in the export environment."
                : message;
    }

    /** The environment variable name of a property, as Quarkus and Forage map it. */
    static String envName(String property) {
        return property.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT);
    }

    /**
     * Rewrites the exported resources: removes the translated Forage keys from the {@code *.properties} files and adds
     * the Quarkus properties to {@code application.properties}, keeping the keys it already defines.
     */
    static void write(Result result, Path resourcesDir) throws IOException {
        if (result.properties().isEmpty()) {
            return;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(resourcesDir)) {
            files = list.filter(f -> f.toString().endsWith(".properties")).toList();
        }
        for (Path file : files) {
            List<String> lines = removeKeys(Files.readAllLines(file, StandardCharsets.UTF_8), result.forageKeys());
            Files.write(file, lines, StandardCharsets.UTF_8);
        }

        Path application = resourcesDir.resolve("application.properties");
        Properties existing = new Properties();
        if (Files.exists(application)) {
            try (Reader reader = Files.newBufferedReader(application, StandardCharsets.UTF_8)) {
                existing.load(reader);
            }
        }
        StringBuilder block = new StringBuilder("\n# Translated from the Forage configuration\n");
        result.properties().forEach((key, value) -> {
            if (!existing.containsKey(key)) {
                block.append(key)
                        .append('=')
                        .append(value.replace("\\", "\\\\"))
                        .append('\n');
            }
        });
        Files.writeString(
                application,
                block.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** Removes the lines of the given keys, including their continuation lines. */
    static List<String> removeKeys(List<String> lines, Set<String> keys) {
        List<String> kept = new ArrayList<>();
        boolean skipping = false;
        boolean continuation = false;
        for (String line : lines) {
            String trimmed = line.stripLeading();
            boolean comment = !continuation && (trimmed.startsWith("#") || trimmed.startsWith("!"));
            if (!continuation) {
                skipping = !comment && keys.contains(trimmed.split("[=:\\s]", 2)[0]);
            }
            if (!skipping) {
                kept.add(line);
            }
            continuation = !comment && endsWithContinuation(line);
        }
        return kept;
    }

    private static boolean endsWithContinuation(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }
}
