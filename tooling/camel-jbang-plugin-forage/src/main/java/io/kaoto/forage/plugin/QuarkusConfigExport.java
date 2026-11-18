package io.kaoto.forage.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.QuarkusConfigHook;
import io.kaoto.forage.core.util.config.Config;
import io.kaoto.forage.core.util.config.ConfigHelper;
import io.kaoto.forage.core.util.config.ConfigModule;
import io.kaoto.forage.core.util.config.ConfigResolver;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.core.util.config.MissingConfigException;
import io.kaoto.forage.core.util.config.PropertyFileLocator;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Translates, for {@code camel export --runtime=quarkus}, the Forage configurations that Quarkus extensions fully
 * cover into {@code quarkus.*} properties, so the exported application needs no Forage runtime for them.
 *
 * <p>A module is translated all or nothing: if any of its configurations needs Forage at runtime (transactions,
 * auxiliary repositories, {@code {{...}}} placeholders, profile-specific values, ...), the whole module stays
 * {@code forage.*}. Modules opt in with a {@link QuarkusConfigHook}.
 */
final class QuarkusConfigExport {

    private static final String APPLICATION = "application.properties";

    /** The encoding of the {@code application.properties} Camel JBang exports. */
    static final Charset EXPORTED_APPLICATION = StandardCharsets.UTF_8;

    /**
     * @param properties the translated Quarkus properties
     * @param forageKeys the Forage keys they replace
     * @param modules the translated module prefixes (their Forage dependencies are not needed)
     * @param groups the catalog conditional groups the translated configurations enable, also by default
     * @param files the module property files the Forage keys were read from, besides {@code application.properties}
     * @param messages what the export tells the user
     */
    record Result(
            Map<String, String> properties,
            Set<String> forageKeys,
            Set<String> modules,
            Set<String> groups,
            Set<String> files,
            List<String> messages) {}

    /**
     * The exported properties.
     *
     * @param application {@code application.properties}, plus the keys of {@code application-<profile>.properties}
     *     with a {@code %<profile>.} prefix
     * @param files the other {@code *.properties} files by file name (Forage reads a module file by its name)
     */
    record Project(Properties application, Map<String, Properties> files) {}

    /**
     * What the run of the project directory translated, handed to the export call.
     *
     * @param result the translation
     * @param application the {@code forage.*} values of the project {@code application.properties} (also
     *     {@code %<profile>.forage.*}, and those of {@code application-<profile>.properties}), with the
     *     {@code --property} values, as the Forage runtime reads them
     */
    record Run(Result result, Map<String, String> application) {}

    private QuarkusConfigExport() {}

    /**
     * Translates the project directory as the Forage runtime reads it (ISO-8859-1), with the {@code --property} values
     * of the run, which the export writes to {@code application.properties}.
     */
    static Run run(Path dir, Map<String, String> runProperties) throws IOException {
        Project project = load(dir, StandardCharsets.ISO_8859_1);
        runProperties.forEach(project.application()::setProperty);
        // also the application-<profile>.properties values (as %<profile>. keys): Camel JBang does not export them
        return new Run(translate(project), forageValues(project.application()));
    }

    /** The {@code forage.*} and {@code %<profile>.forage.*} entries. */
    private static Map<String, String> forageValues(Properties properties) {
        Map<String, String> values = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            String unprofiled = key.startsWith("%") && key.indexOf('.') > 0 ? key.substring(key.indexOf('.') + 1) : key;
            if (unprofiled.startsWith("forage.")) {
                values.put(key, properties.getProperty(key));
            }
        }
        return values;
    }

    /**
     * Fails if the exported {@code application.properties} does not hold the {@code forage.*} values the run read:
     * Camel JBang writes it as {@code key=value} without escaping, so a backslash, a leading space, a line break or a
     * blank value is not kept. The module files are copied as they are.
     */
    static void checkExported(Run run, Path resources) throws IOException {
        Path file = resources.resolve(APPLICATION);
        Map<String, String> exported =
                forageValues(Files.exists(file) ? read(file, EXPORTED_APPLICATION) : new Properties());
        Set<String> keys = new TreeSet<>(run.application().keySet());
        keys.addAll(exported.keySet());
        for (String key : keys) {
            String expected = run.application().get(key);
            String found = exported.get(key);
            // Camel JBang drops blank values: harmless for a translated key, the export writes it from the run (an
            // empty value is never translated: Quarkus would read it as not set)
            boolean blankTranslated = found == null
                    && expected != null
                    && !expected.isEmpty()
                    && expected.isBlank()
                    && run.result().forageKeys().contains(key);
            if (!blankTranslated && !Objects.equals(expected, found)) {
                throw new IllegalStateException("The exported " + APPLICATION + " changes " + key + ": expected "
                        + quote(expected) + ", found " + quote(found) + ". Camel JBang writes " + APPLICATION
                        + " without escaping, so a backslash, a leading space, a line break or a blank value is not"
                        + " kept. "
                        + (key.startsWith("%")
                                ? "application-<profile>.properties is not exported either. Set " + key + " in "
                                        + APPLICATION + " with a value it keeps (the module file has no profiles)."
                                : "Set " + key + " in the module's forage-*.properties file and pass that file to"
                                        + " camel export (it is copied as it is), instead of in " + APPLICATION
                                        + " or with --property."));
            }
        }
    }

    private static String quote(String value) {
        return value == null ? "no value" : "'" + escape(value, false) + "'";
    }

    /** Encodes a run as text, for a system property. */
    static String encode(Run run) {
        Properties encoded = new Properties();
        Result result = run.result();
        result.properties().forEach((key, value) -> encoded.setProperty("property." + key, value));
        result.forageKeys().forEach(key -> encoded.setProperty("forageKey." + key, ""));
        result.modules().forEach(module -> encoded.setProperty("module." + module, ""));
        result.groups().forEach(group -> encoded.setProperty("group." + group, ""));
        result.files().forEach(file -> encoded.setProperty("file." + file, ""));
        for (int i = 0; i < result.messages().size(); i++) {
            encoded.setProperty(
                    String.format("message.%06d", i), result.messages().get(i));
        }
        run.application().forEach((key, value) -> encoded.setProperty("application." + key, value));
        StringWriter writer = new StringWriter();
        try {
            encoded.store(writer, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return writer.toString();
    }

    static Run decode(String text) {
        Properties encoded = new Properties();
        try {
            encoded.load(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Result result = new Result(
                new TreeMap<>(),
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                new ArrayList<>());
        Map<String, String> application = new TreeMap<>();
        Map<String, String> messages = new TreeMap<>();
        for (String name : encoded.stringPropertyNames()) {
            String kind = name.substring(0, name.indexOf('.'));
            String key = name.substring(kind.length() + 1);
            String value = encoded.getProperty(name);
            switch (kind) {
                case "property" -> result.properties().put(key, value);
                case "forageKey" -> result.forageKeys().add(key);
                case "module" -> result.modules().add(key);
                case "group" -> result.groups().add(key);
                case "file" -> result.files().add(key);
                case "message" -> messages.put(key, value);
                case "application" -> application.put(key, value);
                default -> throw new IllegalStateException("Unknown run data " + name);
            }
        }
        result.messages().addAll(messages.values());
        return new Run(result, application);
    }

    /**
     * Loads the {@code *.properties} files of a directory. The Forage runtime reads the project files as ISO-8859-1
     * ({@link Properties#load(InputStream)}); Camel JBang copies them to the export byte for byte, except
     * {@code application.properties}, which it rewrites as UTF-8 (the Quarkus encoding) with the values it read.
     *
     * @param application the encoding of {@code application.properties}: {@link StandardCharsets#ISO_8859_1} in the
     *     project directory, {@link #EXPORTED_APPLICATION} in the exported resources
     */
    static Project load(Path dir, Charset application) throws IOException {
        Project project = new Project(new Properties(), new TreeMap<>());
        if (!Files.isDirectory(dir)) {
            return project;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".properties"))
                    .sorted()
                    .toList()) {
                String name = file.getFileName().toString();
                Properties props = read(file, name.equals(APPLICATION) ? application : StandardCharsets.ISO_8859_1);
                if (name.equals(APPLICATION)) {
                    props.stringPropertyNames()
                            .forEach(key -> putOnce(project.application(), key, props.getProperty(key), name));
                } else if (name.startsWith("application-")) {
                    String profile =
                            "%" + name.substring("application-".length(), name.length() - ".properties".length()) + ".";
                    props.stringPropertyNames()
                            .forEach(
                                    key -> putOnce(project.application(), profile + key, props.getProperty(key), name));
                } else {
                    project.files().put(name, props);
                }
            }
        }
        return project;
    }

    /** A profile value set twice ({@code %prod.} key and {@code application-prod.properties}) must not hide one. */
    private static void putOnce(Properties properties, String key, String value, String file) {
        String other = properties.getProperty(key);
        if (other != null && !other.equals(value)) {
            throw new IllegalStateException(key + " is set to " + quote(other) + " and to " + quote(value) + " (in "
                    + file + "): set it once, in " + APPLICATION + " or in the profile's file.");
        }
        properties.setProperty(key, value);
    }

    /**
     * Translates the project properties. Only the values of the given properties are used: Forage overrides set in
     * the export environment ({@code FORAGE_*}, {@code -Dforage.*}) are ignored.
     */
    static Result translate(Project project) {
        return translate(
                project,
                ServiceLoader.load(QuarkusConfigHook.class, QuarkusConfigExport.class.getClassLoader()).stream()
                        .<QuarkusConfigHook<?>>map(ServiceLoader.Provider::get)
                        .toList());
    }

    static Result translate(Project project, List<QuarkusConfigHook<?>> hooks) {
        Properties application = new Properties();
        // the keys of every profile, to find the configurations they touch: values of different profiles merge
        Properties profiled = new Properties();
        // the values of each profile ("%dev.", "%prod.", ...) apart, so that every value is checked
        Map<String, Properties> profiles = new TreeMap<>();
        project.application().stringPropertyNames().forEach(key -> {
            String value = project.application().getProperty(key);
            if (key.startsWith("%") && key.indexOf('.') > 0) {
                String profile = key.substring(0, key.indexOf('.') + 1);
                profiled.setProperty(key.substring(profile.length()), value);
                profiles.computeIfAbsent(profile, p -> new Properties())
                        .setProperty(key.substring(profile.length()), value);
            } else {
                application.setProperty(key, value);
            }
        });

        Result result = new Result(
                new LinkedHashMap<>(),
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                new ArrayList<>());
        try {
            for (QuarkusConfigHook<?> hook : hooks) {
                // as at runtime: the module file, overridden by application.properties
                String file = hook.descriptor().createConfig(null).name() + ".properties";
                Properties base = new Properties();
                base.putAll(project.files().getOrDefault(file, new Properties()));
                base.putAll(application);
                translate(hook, project.application(), base, profiled, profiles, result);
                if (result.modules().contains(hook.descriptor().modulePrefix())
                        && project.files().containsKey(file)) {
                    result.files().add(file);
                }
            }
        } finally {
            // drop the export-only values from the shared store
            ConfigStore.getInstance().reload();
        }
        return result;
    }

    private static <C extends Config> void translate(
            QuarkusConfigHook<C> hook,
            Properties application,
            Properties base,
            Properties profiled,
            Map<String, Properties> profiles,
            Result result) {
        ForageModuleDescriptor<C, ?> descriptor = hook.descriptor();
        String module = descriptor.modulePrefix();
        String namedRegexp = ConfigHelper.getNamedPropertyRegexp(module);
        String defaultRegexp = ConfigHelper.getDefaultPropertyRegexp(module);
        Map<String, String> moduleValues = moduleValues(module, namedRegexp, base, profiles);
        moduleValues.forEach((key, value) -> {
            if (value.isEmpty()) {
                throw new IllegalStateException("Quarkus reads the empty value of " + key + " as not set, the Forage"
                        + " runtime uses an empty value: the exported application would not behave the same. Set a"
                        + " value, or remove " + key + " if not setting it is what you mean.");
            }
        });
        Set<String> named = PropertyFileLocator.readPrefixes(base, namedRegexp);
        boolean hasDefault =
                !PropertyFileLocator.readPrefixes(base, defaultRegexp).isEmpty();
        if (named.isEmpty() && !hasDefault) {
            // only profile-specific values, if any: they stay forage.*
            failIfExpanded(module, "profile-specific values", moduleValues);
            return;
        }

        String reason = null;
        if (!PropertyFileLocator.readPrefixes(profiled, namedRegexp).isEmpty()
                || !PropertyFileLocator.readPrefixes(profiled, defaultRegexp).isEmpty()) {
            reason = "profile-specific values";
        } else if (!named.isEmpty() && hasDefault) {
            reason = "default and named configurations together";
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

            // never drop configuration: every key must be one the hook covers
            List<String> uncovered = values.keySet().stream()
                    .filter(key -> hook.covered().stream()
                            .noneMatch(
                                    c -> c.endsWith(".") ? key.startsWith(keyPrefix + c) : key.equals(keyPrefix + c)))
                    .sorted()
                    .toList();
            if (values.values().stream().anyMatch(value -> value.contains("{{"))) {
                reason = "'" + label + "' uses {{...}} placeholders";
            } else if (!uncovered.isEmpty()) {
                reason = "'" + label + "' uses " + String.join(", ", uncovered) + ", not covered by the Quarkus export";
            } else {
                C config = descriptor.createConfig(name);
                useOnly(config, keyPrefix, values);
                String unsupported = hook.unsupported(name, config);
                Map<String, String> translated = new TreeMap<>();
                if (unsupported != null) {
                    reason = "'" + label + "' " + unsupported;
                } else {
                    // some configs (e.g. JDBC driver properties) read system properties and resolvers directly
                    Map<String, String> hidden = new HashMap<>();
                    System.getProperties().stringPropertyNames().stream()
                            .filter(key -> key.startsWith(keyPrefix))
                            .forEach(key -> hidden.put(
                                    key, (String) System.getProperties().remove(key)));
                    ConfigStore store = ConfigStore.getInstance();
                    List<ConfigResolver> resolvers = List.copyOf(store.getResolvers());
                    resolvers.forEach(resolver -> store.unregisterResolver(resolver.getClass()));
                    try {
                        translated.putAll(descriptor.translateProperties(name, config));
                    } catch (MissingConfigException | IllegalArgumentException e) {
                        reason = "'" + label + "': " + e.getMessage();
                    } finally {
                        hidden.forEach(System::setProperty);
                        resolvers.forEach(store::registerResolver);
                    }
                    if (reason == null && translated.isEmpty()) {
                        reason = "'" + label + "' has no Quarkus equivalent";
                    }
                    if (reason == null) {
                        reason = dependencyConflict(hook, label, application, translated);
                    }
                }
                if (reason == null) {
                    groups.addAll(hook.adjust(config, translated));
                    quarkus.putAll(translated);
                    forageKeys.addAll(values.keySet());
                    instanceMessages.add(overrideMessage(module, label, keyPrefix, translated));
                }
            }
        }

        if (reason != null) {
            failIfExpanded(module, reason, moduleValues);
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
     * The values of the module's keys ({@code forage.<module>.*}, {@code forage.<name>.<module>.*}) by key, each
     * profile value under its {@code %<profile>.} key: no value hides another.
     */
    private static Map<String, String> moduleValues(
            String module, String namedRegexp, Properties base, Map<String, Properties> profiles) {
        Map<String, Properties> sources = new LinkedHashMap<>();
        sources.put("", base);
        sources.putAll(profiles);
        Map<String, String> values = new TreeMap<>();
        sources.forEach((profile, source) -> {
            Set<String> prefixes = new LinkedHashSet<>();
            prefixes.add("forage." + module + ".");
            PropertyFileLocator.readPrefixes(source, namedRegexp)
                    .forEach(name -> prefixes.add("forage." + name + "." + module + "."));
            for (String key : source.stringPropertyNames()) {
                if (prefixes.stream().anyMatch(key::startsWith)) {
                    values.put(profile + key, source.getProperty(key));
                }
            }
        });
        return values;
    }

    /**
     * A configuration kept on {@code forage.*} reaches Quarkus through the Forage Quarkus extension, and SmallRye
     * expands {@code ${...}}, {@code $$} and {@code \$} in its values, which the Forage runtime reads as written.
     */
    private static void failIfExpanded(String module, String reason, Map<String, String> moduleValues) {
        moduleValues.forEach((key, value) -> {
            if (expandedByQuarkus(value)) {
                throw new IllegalStateException("Forage " + module + " stays as forage.* configuration (" + reason
                        + "), and Quarkus would read " + key + " differently: it expands ${...}, $$ and \\$ in"
                        + " values, the Forage runtime does not. Change the value, or remove what keeps the"
                        + " configuration on forage.*: the export then writes it as a Quarkus property that keeps"
                        + " the value.");
            }
        });
    }

    /** Whether SmallRye Config reads the value differently (expressions and their escapes). */
    static boolean expandedByQuarkus(String value) {
        return value.contains("${") || value.contains("$$") || value.contains("\\$");
    }

    /** The value escaped for SmallRye Config ({@code \$}), so that Quarkus reads it exactly. */
    static String quarkusValue(String value) {
        return value.replace("$", "\\$");
    }

    /**
     * The dependencies follow the Forage value: a different {@code quarkus.*} value set by the user for one of the
     * hook's {@link QuarkusConfigHook#dependencyKeys()} keeps the configuration on {@code forage.*}.
     */
    private static String dependencyConflict(
            QuarkusConfigHook<?> hook, String label, Properties application, Map<String, String> translated) {
        for (Map.Entry<String, String> entry : translated.entrySet()) {
            String key = entry.getKey();
            if (hook.dependencyKeys().stream().noneMatch(suffix -> key.endsWith("." + suffix))) {
                continue;
            }
            for (String user : application.stringPropertyNames()) {
                // a profile value (%prod.quarkus...) counts too: it applies to that profile
                String unprofiled = user.startsWith("%") ? user.substring(user.indexOf('.') + 1) : user;
                if (sameQuarkusKey(unprofiled, key)
                        && !application.getProperty(user).equals(entry.getValue())) {
                    return "'" + label + "' has " + user + "=" + application.getProperty(user)
                            + " in application.properties, but its dependencies follow " + entry.getValue();
                }
            }
        }
        return null;
    }

    /** {@code "ds1"} and {@code ds1} are the same Quarkus key. */
    private static boolean sameQuarkusKey(String a, String b) {
        return a.replace("\"", "").equals(b.replace("\"", ""));
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
            } else if (entry.getKey() instanceof String key && key.startsWith(keyPrefix)) {
                // e.g. JDBC driver properties, loaded from the home directory
                store.setDirect(key, null);
            }
        }
        values.forEach(config::register);
    }

    private static String overrideMessage(
            String module, String label, String keyPrefix, Map<String, String> translated) {
        String overrides = translated.keySet().stream()
                .sorted()
                .map(QuarkusConfigExport::envName)
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
     * Rewrites the exported resources: adds the Quarkus properties to {@code application.properties}, keeping the keys
     * it already defines, and removes the translated Forage keys from it and from the module files (deleting the module
     * files left empty). Fails if the files do not end up as intended.
     */
    static void write(Result result, Path resourcesDir) throws IOException {
        if (result.properties().isEmpty()) {
            return;
        }
        Path application = resourcesDir.resolve(APPLICATION);
        Properties existing = Files.exists(application) ? read(application, EXPORTED_APPLICATION) : new Properties();
        Map<String, String> written = new TreeMap<>();
        result.properties().forEach((key, value) -> {
            if (existing.stringPropertyNames().stream().noneMatch(user -> sameQuarkusKey(user, key))) {
                written.put(key, value);
            }
        });
        StringBuilder block = new StringBuilder("\n# Translated from the Forage configuration\n");
        written.forEach((key, value) -> block.append(escape(key, true))
                .append('=')
                .append(escape(quarkusValue(value), false))
                .append('\n'));
        // add first: a failure leaves the Forage keys in place; the block is ASCII, any reader sees the same
        Files.writeString(
                application,
                block.toString(),
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);

        List<Path> files = new ArrayList<>();
        files.add(application);
        result.files().forEach(file -> files.add(resourcesDir.resolve(file)));
        for (Path file : files) {
            if (!Files.exists(file)) {
                continue;
            }
            // the lines left keep their bytes; a module file can hold any byte, as ISO-8859-1 maps them all
            Charset charset = charset(file, application);
            List<String> original = Files.readAllLines(file, charset);
            List<String> lines = removeKeys(original, result.forageKeys());
            if (lines.size() == original.size()) {
                continue;
            }
            if (lines.stream().allMatch(String::isBlank) && !file.equals(application)) {
                // only translated keys: the file is not needed
                Files.delete(file);
            } else {
                // one line separator, on every platform
                Files.writeString(file, String.join("\n", lines) + "\n", charset);
            }
        }
        verify(result, written, files, application);
    }

    /** Never drop a setting silently: the written files must hold exactly the translation. */
    private static void verify(Result result, Map<String, String> written, List<Path> files, Path application)
            throws IOException {
        // as Quarkus reads application.properties (SmallRye ConfigSourceUtil.urlToMap: UTF-8), expanding expressions
        SmallRyeConfig quarkus = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(read(application, StandardCharsets.UTF_8), APPLICATION, 250))
                .build();
        written.forEach((key, value) -> {
            if (!value.equals(quarkus.getConfigValue(key).getValue())) {
                throw new IllegalStateException("The exported " + APPLICATION + " does not keep " + key);
            }
        });
        for (Path file : files) {
            if (Files.exists(file)) {
                read(file, charset(file, application)).stringPropertyNames().stream()
                        .filter(result.forageKeys()::contains)
                        .findFirst()
                        .ifPresent(key -> {
                            throw new IllegalStateException("Could not remove " + key + " from the exported " + file);
                        });
            }
        }
    }

    /** The encoding of an exported file. */
    private static Charset charset(Path file, Path application) {
        return file.equals(application) ? EXPORTED_APPLICATION : StandardCharsets.ISO_8859_1;
    }

    private static Properties read(Path file, Charset charset) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), charset)) {
            properties.load(reader);
        }
        return properties;
    }

    /**
     * Escapes a key or value as {@link Properties#store(java.io.OutputStream, String)} does: the result is ASCII, so
     * Quarkus (UTF-8) reads the very string Forage read.
     */
    static String escape(String text, boolean key) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\f' -> escaped.append("\\f");
                case ' ' -> escaped.append(key || i == 0 ? "\\ " : " ");
                case '=', ':', '#', '!' -> escaped.append(key || i == 0 ? "\\" + c : String.valueOf(c));
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
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
                skipping = !comment && keys.contains(keyOf(trimmed));
            }
            if (!skipping) {
                kept.add(line);
            }
            continuation = !comment && endsWithContinuation(line);
        }
        return kept;
    }

    /** The key of a property line, unescaped as {@link Properties#load} reads it. */
    private static String keyOf(String line) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(line));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return properties.stringPropertyNames().stream().findFirst().orElse(null);
    }

    private static boolean endsWithContinuation(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }
}
