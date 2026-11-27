package io.kaoto.forage.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import io.kaoto.forage.catalog.model.ConfigEntry;
import io.kaoto.forage.catalog.model.ForageBean;
import io.kaoto.forage.catalog.model.QuarkusBean;
import io.kaoto.forage.catalog.model.QuarkusSelector;
import io.kaoto.forage.catalog.reader.ForageCatalogReader;
import io.kaoto.forage.core.util.config.ConfigHelper;
import io.kaoto.forage.core.util.config.PropertyFileLocator;
import io.kaoto.forage.core.util.config.PropertyFileSource;

/**
 * Adds, on {@code camel export --runtime=quarkus}, native {@code quarkus.*} keys for the Forage modules the catalog maps
 * (today the JDBC datasources and the default Artemis JMS connection factory) to the exported
 * {@code application.properties}, so the exported app needs no Forage runtime for them. The {@code forage.*} keys
 * stay; each Quarkus key reads its Forage key as {@code ${FORAGE_<KEY>:${forage.<key>:<value>}}}, so {@code FORAGE_*}
 * and then {@code -Dforage.*} still win, as in Forage. The module files are not read at runtime. A module with anything
 * the catalog does not map stays {@code forage.*} as before; a configured module the catalog lists in
 * {@code quarkusUnsupported} gets a note with the reason. A module whose bean no Quarkus extension creates may instead
 * declare in the catalog ({@code quarkusBeans}) how plain Camel {@code camel.beans.*} keys create it; the export then
 * writes those keys and the native build keys for their classes.
 */
final class QuarkusExport {

    private static final String APPLICATION = "application.properties";

    /**
     * A Quarkus key ({@code {name}}: the quoted configuration name; ending with {@code .}: a map), and a unit; or none,
     * {@code ignored}: the Forage key has no effect on Quarkus; {@code selector}: the key selects the bean.
     */
    private record To(String key, String unit, boolean ignored, QuarkusSelector selector) {}

    private static final To IGNORED = new To(null, "", true, null);
    private static final To HANDLED = new To(null, "", false, null);

    /** Comma-separated lists: the values of all modules and of the exported application.properties are joined. */
    private static final String REFLECTION = "quarkus.camel.native.reflection.include-patterns";

    private static final String RESOURCES = "quarkus.native.resources.includes";

    private static final String BUILD_ARGS = "quarkus.native.additional-build-args";

    private static final Set<String> LISTS = Set.of(REFLECTION, RESOURCES, BUILD_ARGS);

    /** A name {@code camel.beans.<name>} binds as it is. */
    private static final Pattern BEAN_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private static final Pattern CLASS = Pattern.compile("#class:([A-Za-z0-9_$.]+)");

    /**
     * Per module: the Forage keys (after {@code forage.[<name>.]<module>.}; ending with {@code .}: a map) with a
     * Quarkus equivalent, from the catalog ({@code quarkusProperty} / {@code quarkusUnit}, {@code quarkusIgnored},
     * {@code quarkusHandled}, {@code quarkusSelector}, written from the module descriptor).
     */
    static Map<String, Map<String, To>> table(ForageCatalogReader catalog) {
        Map<String, Map<String, To>> table = new TreeMap<>();
        for (var factory : catalog.getAllFactories()) {
            String module = factory.factoryTypeKey();
            Map<String, To> keys = new TreeMap<>();
            QuarkusBean bean = catalog.getQuarkusBeans().get(module);
            if (bean != null) {
                // set by the camel.beans.* keys of the bean
                bean.getProperties().keySet().forEach(key -> keys.put(key, HANDLED));
                bean.getCreate().forEach(create -> QuarkusBean.keys(create).forEach(key -> keys.put(key, HANDLED)));
            }
            for (ConfigEntry entry : factory.configEntries()) {
                String to = entry.getQuarkusProperty();
                String key = entry.getName().substring(("forage." + module + ".").length());
                QuarkusSelector selector = entry.getQuarkusSelector();
                if (to != null) {
                    keys.put(
                            to.endsWith(".") ? key + "." : key,
                            new To(to, entry.getQuarkusUnit() == null ? "" : entry.getQuarkusUnit(), false, selector));
                } else if (entry.isQuarkusIgnored()) {
                    keys.put(key, IGNORED);
                } else if (selector != null || entry.isQuarkusHandled()) {
                    keys.put(key, new To(null, "", false, selector));
                }
            }
            if (bean != null || keys.values().stream().anyMatch(to -> to.key() != null)) {
                table.put(module, keys);
            }
        }
        return table;
    }

    /** The added Quarkus properties, and the translated modules (their Forage artifacts are not needed). */
    record Result(Map<String, String> properties, Set<String> modules, List<String> notes) {}

    private QuarkusExport() {}

    /** Translates the exported resources and appends the Quarkus properties to their application.properties. */
    static Result export(Path resources) throws IOException {
        return export(resources, null);
    }

    /** As {@link #export(Path)}, translating only the {@code allowed} modules ({@code null}: all). */
    static Result export(Path resources, Set<String> allowed) throws IOException {
        Path application = resources.resolve("application.properties");
        Properties existing = read(application, StandardCharsets.UTF_8);
        Result result = translate(resources, existing, allowed);
        Properties added = new Properties();
        result.properties().forEach((key, value) -> {
            if (LISTS.contains(key) && existing.getProperty(key) != null) {
                // the later line wins: keep the user's entries
                added.setProperty(key, join(existing.getProperty(key), value));
            } else if (existing.stringPropertyNames().stream()
                    .noneMatch(k -> unquoted(k).equals(unquoted(key)))) {
                added.setProperty(key, value); // a user quarkus.* key wins, as before
            }
        });
        if (!added.isEmpty()) {
            // escaped as ASCII; drop the date comment
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            added.store(out, null);
            String lines = out.toString(StandardCharsets.ISO_8859_1);
            Files.writeString(
                    application,
                    "\n# Forage configuration as Quarkus configuration\n" + lines.substring(lines.indexOf('\n') + 1),
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
        return result;
    }

    static Result translate(Path resources, Properties application) throws IOException {
        return translate(resources, application, null);
    }

    /** Reads {@code application.properties} of {@code dir} and returns the modules it translates. */
    static Set<String> modules(Path dir) throws IOException {
        return translate(dir, read(dir.resolve("application.properties"), StandardCharsets.ISO_8859_1))
                .modules();
    }

    private static Result translate(Path resources, Properties application, Set<String> allowed) throws IOException {
        ForageCatalogReader catalog = ForageCatalogReader.getInstance();
        Result result = new Result(new TreeMap<>(), new TreeSet<>(), new ArrayList<>());
        Map<String, Map<String, To>> table = table(catalog);
        for (String module : table.keySet()) {
            // the factory file and the files of its beans (e.g. forage-jms-artemis.properties)
            List<String> files = new ArrayList<>(
                    List.of(catalog.getPropertiesFileName(module).orElseThrow()));
            catalog.getAllBeansForFactory(module).stream()
                    .map(ForageBean::getPropertiesFile)
                    .filter(file -> file != null && !file.isEmpty() && !files.contains(file))
                    .forEach(files::add);
            // per configuration name ("" for the default one): the Forage keys, and the file of each key; a key set
            // differently in two files keeps the module forage.*
            Map<String, Map<String, String>> configs = new TreeMap<>();
            Map<String, String> origins = new TreeMap<>();
            String reason = null;
            for (String file : files) {
                if (reason == null) {
                    reason = collect(
                            module, file, read(resources.resolve(file), StandardCharsets.ISO_8859_1), configs, origins);
                }
            }
            if (reason == null) {
                reason = collect(module, APPLICATION, application, configs, origins);
            }
            if (configs.isEmpty()) {
                continue;
            }
            if (reason == null && configs.containsKey("") && configs.size() > 1) {
                reason = "default and named configurations together";
            }
            Map<String, String> translated = new TreeMap<>();
            List<String> ignored = new ArrayList<>();
            for (var config : configs.entrySet()) {
                if (reason == null) {
                    reason = translate(
                            catalog,
                            module,
                            table.get(module),
                            config.getKey(),
                            config.getValue(),
                            origins,
                            translated,
                            ignored);
                }
            }
            if (reason == null) {
                reason = otherFiles(resources, module, files);
            }
            if (reason == null && allowed != null && !allowed.contains(module)) {
                // the run data of the export holds its Forage artifacts
                reason = "the run of the export kept it";
            }
            if (reason != null) {
                result.notes().add("Forage " + module + " stays as forage.* configuration: " + reason + ".");
                continue;
            }
            translated.forEach((key, value) ->
                    result.properties().merge(key, value, (a, b) -> LISTS.contains(key) ? join(a, b) : b));
            result.modules().add(module);
            result.notes()
                    .add("Forage " + module + " exported as "
                            + (catalog.getQuarkusBeans().containsKey(module) ? "camel.beans.*" : "quarkus.*")
                            + " configuration: " + String.join(", ", files)
                            + " not read at runtime, a copy mounted later is ignored; override with FORAGE_*,"
                            + " -Dforage.* or forage.* keys in config/application.properties, or keep forage.* with"
                            + " FORAGE_QUARKUS_TRANSLATION_SKIP=true.");
            if (!ignored.isEmpty()) {
                result.notes()
                        .add("Forage " + module + ": " + String.join(", ", ignored)
                                + " not translated, no effect on Quarkus.");
            }
        }
        for (var unsupported : catalog.getQuarkusUnsupported().entrySet()) {
            if (configured(resources, application, unsupported.getKey())) {
                result.notes()
                        .add("Forage " + unsupported.getKey() + " stays as forage.* configuration: "
                                + unsupported.getValue() + ".");
            }
        }
        return result;
    }

    /**
     * Adds the module's keys of {@code file} to {@code configs} and their file to {@code origins}, or returns why the
     * module stays forage.*.
     */
    private static String collect(
            String module,
            String file,
            Properties source,
            Map<String, Map<String, String>> configs,
            Map<String, String> origins) {
        Pattern named = Pattern.compile(ConfigHelper.getNamedPropertyRegexp(module));
        Pattern unnamed = Pattern.compile(ConfigHelper.getDefaultPropertyRegexp(module));
        for (String key : source.stringPropertyNames()) {
            String plain = key.startsWith("%") ? key.substring(key.indexOf('.') + 1) : key;
            Matcher m = named.matcher(plain);
            String name = m.matches() ? m.group(1) : unnamed.matcher(plain).matches() ? "" : null;
            if (name == null) {
                continue;
            }
            if (!plain.equals(key)) {
                return "profile-specific values";
            }
            String earlier = configs.computeIfAbsent(name, n -> new TreeMap<>()).put(key, source.getProperty(key));
            if (earlier != null && !earlier.equals(source.getProperty(key))) {
                return key + " differs between " + origins.get(key) + " and " + file;
            }
            origins.put(key, file);
        }
        return null;
    }

    /** Whether application.properties or another properties file of the resources sets a key of the module. */
    private static boolean configured(Path resources, Properties application, String module) throws IOException {
        List<Properties> sources = new ArrayList<>(List.of(application));
        if (Files.isDirectory(resources)) {
            try (Stream<Path> files = Files.list(resources)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".properties"))
                        .toList()) {
                    sources.add(read(file, StandardCharsets.ISO_8859_1));
                }
            }
        }
        Pattern named = Pattern.compile(ConfigHelper.getNamedPropertyRegexp(module));
        Pattern unnamed = Pattern.compile(ConfigHelper.getDefaultPropertyRegexp(module));
        return sources.stream()
                .flatMap(source -> source.stringPropertyNames().stream())
                .map(key -> key.startsWith("%") ? key.substring(key.indexOf('.') + 1) : key)
                .anyMatch(key ->
                        named.matcher(key).matches() || unnamed.matcher(key).matches());
    }

    /**
     * Adds the Quarkus properties of one configuration and its keys without effect on Quarkus to {@code ignored}, or
     * returns why the module stays forage.*.
     */
    private static String translate(
            ForageCatalogReader catalog,
            String module,
            Map<String, To> table,
            String name,
            Map<String, String> values,
            Map<String, String> origins,
            Map<String, String> translated,
            List<String> ignored) {
        String prefix = "forage." + (name.isEmpty() ? "" : name + ".") + module + ".";
        for (var entry : values.entrySet()) {
            String suffix = entry.getKey().substring(prefix.length());
            if (!table.containsKey(suffix) && !(table.containsKey("properties.") && suffix.startsWith("properties."))) {
                return entry.getKey() + " has no Quarkus equivalent";
            }
            if (table.containsKey(suffix) && table.get(suffix).ignored()) {
                ignored.add(entry.getKey());
            }
            // Quarkus cannot escape { or } inside ${...:<value>}, reads ${...::<value>} as a secret handler; Forage
            // expands ${...} and {{...}} itself
            if ((entry.getKey() + entry.getValue()).matches("(?s).*[{}].*")
                    || entry.getKey().contains(":")
                    || entry.getValue().startsWith(":")) {
                return entry.getKey() + " cannot be written as a Quarkus expression";
            }
        }
        var metadata = catalog.getFactoryMetadata(module).orElseThrow();
        Map<String, String> defaults = new TreeMap<>();
        for (ConfigEntry entry : metadata.configEntries()) {
            String key = prefix + entry.getName().substring(("forage." + module + ".").length());
            if (entry.getDefaultValue() != null) {
                defaults.put(key, entry.getDefaultValue());
            } else if (entry.isRequired() && !values.containsKey(key)) {
                return key + " is not set";
            }
        }
        for (var entry : table.entrySet()) {
            QuarkusSelector selector = entry.getValue().selector();
            if (selector == null) {
                continue;
            }
            if (selector.isDefaultOnly() && !name.isEmpty()) {
                return "named configurations have no Quarkus equivalent";
            }
            String kind = values.get(prefix + entry.getKey());
            if (catalog.getBeanRuntimeDependencies(kind, "quarkus").isEmpty()
                    || (selector.getKinds() != null && !selector.getKinds().containsKey(kind))) {
                return "the kind of " + prefix + "* has no Quarkus equivalent in the Forage catalog";
            }
            if (selector.getKinds() != null) {
                translated.putAll(selector.getKinds().get(kind));
            }
        }
        QuarkusBean bean = catalog.getQuarkusBeans().get(module);
        if (bean != null) {
            String reason = bean(
                    bean,
                    name.isEmpty() ? metadata.defaultBeanName() : name,
                    prefix,
                    values,
                    defaults,
                    origins,
                    translated);
            if (reason != null) {
                return reason;
            }
        }
        String quarkusName = "\"" + (name.isEmpty() ? metadata.defaultBeanName() : name) + "\"";
        for (var entry : table.entrySet()) {
            To to = entry.getValue();
            if (to.key() == null) {
                continue;
            }
            String key = to.key().replace("{name}", quarkusName);
            String forageKey = prefix + entry.getKey();
            if (key.endsWith(".")) {
                values.forEach((k, v) -> {
                    if (k.startsWith(forageKey)) {
                        translated.put(
                                key + "\"" + k.substring(forageKey.length()) + "\"",
                                expression(k, v, !APPLICATION.equals(origins.get(k))));
                    }
                });
            } else {
                String value = values.getOrDefault(forageKey, defaults.getOrDefault(forageKey, ""));
                translated.put(
                        key, expression(forageKey, value, !APPLICATION.equals(origins.get(forageKey))) + to.unit());
            }
        }
        return null;
    }

    /**
     * Adds the {@code camel.beans.<bean>} keys of one configuration and the keys the native build needs for them, or
     * returns why the module stays forage.*. A key not set and without a default writes nothing; a nested bean is
     * created only when a key below it is set.
     */
    private static String bean(
            QuarkusBean bean,
            String beanName,
            String prefix,
            Map<String, String> values,
            Map<String, String> defaults,
            Map<String, String> origins,
            Map<String, String> translated) {
        if (!BEAN_NAME.matcher(beanName).matches()) {
            return "the bean name " + beanName + " cannot be a camel.beans.* name";
        }
        String create = bean.getCreate().stream()
                .filter(alternative -> QuarkusBean.keys(alternative).stream()
                        .allMatch(key -> values.containsKey(prefix + key) || defaults.containsKey(prefix + key)))
                .findFirst()
                .orElse(null);
        if (create == null) {
            return "the keys set do not create the bean";
        }
        Set<String> used = QuarkusBean.keys(create);
        // a key set but left out by the alternative (e.g. a username without a password) would be dropped
        for (String key : allCreateKeys(bean)) {
            if (!used.contains(key) && !bean.getProperties().containsKey(key) && values.containsKey(prefix + key)) {
                return prefix + key + " is not used by the bean";
            }
        }
        Map<String, String> lines = new TreeMap<>();
        Set<String> set = new TreeSet<>(); // bean properties of keys set, not defaulted
        Matcher m = QuarkusBean.KEY.matcher(create);
        StringBuilder value = new StringBuilder();
        while (m.find()) {
            String key = prefix + m.group(1);
            String v = values.getOrDefault(key, defaults.get(key));
            if (v.contains("'")) {
                return key + " cannot be written as a Camel argument";
            }
            m.appendReplacement(
                    value, Matcher.quoteReplacement(expression(key, v, !APPLICATION.equals(origins.get(key)))));
        }
        m.appendTail(value);
        for (var property : bean.getProperties().entrySet()) {
            String key = prefix + property.getKey();
            String v = values.getOrDefault(key, defaults.get(key));
            if (v == null) {
                continue;
            }
            if (v.startsWith("#")) {
                // Camel would read it as a bean reference
                return key + " cannot be written as a Camel bean property";
            }
            for (String path : property.getValue()) {
                lines.put(path, expression(key, v, !APPLICATION.equals(origins.get(key))));
                if (values.containsKey(key)) {
                    set.add(path);
                }
            }
        }
        Set<String> nested = new TreeSet<>();
        bean.getNested().keySet().stream()
                .filter(path -> set.stream().anyMatch(p -> p.startsWith(path + ".")))
                .forEach(nested::add);
        lines.keySet().removeIf(path -> bean.getNested().keySet().stream()
                .anyMatch(n -> path.startsWith(n + ".") && !nested.contains(n)));
        String base = "camel.beans." + beanName;
        translated.put(base, value.toString());
        nested.forEach(
                path -> translated.put(base + "." + path, bean.getNested().get(path)));
        lines.forEach((path, v) -> translated.put(base + "." + path, v));
        // the native build reflects on the #class: types; they must be in the Jandex index
        Set<String> classes = new TreeSet<>(bean.getReflection());
        Stream.concat(Stream.of(create), nested.stream().map(bean.getNested()::get))
                .forEach(v -> {
                    Matcher c = CLASS.matcher(v);
                    while (c.find()) {
                        classes.add(c.group(1));
                    }
                });
        if (!classes.isEmpty()) {
            translated.merge(REFLECTION, String.join(",", classes), QuarkusExport::join);
        }
        // a classpath resource (a path without a scheme other than classpath:) goes into the native image
        for (String key : bean.getResources()) {
            String v = values.get(prefix + key);
            if (v != null && !v.isBlank()) {
                String path = v.startsWith("classpath:") ? v.substring("classpath:".length()) : v;
                if (!path.contains(":") && !path.startsWith("/")) {
                    translated.merge(RESOURCES, path, QuarkusExport::join);
                }
            }
        }
        if (!bean.getNativeBuildArgs().isEmpty()) {
            translated.merge(BUILD_ARGS, String.join(",", bean.getNativeBuildArgs()), QuarkusExport::join);
        }
        for (String dependency : bean.getIndexDependencies()) {
            String[] ga = dependency.split(":");
            translated.put("quarkus.index-dependency." + ga[1] + ".group-id", ga[0]);
            translated.put("quarkus.index-dependency." + ga[1] + ".artifact-id", ga[1]);
        }
        return null;
    }

    private static Set<String> allCreateKeys(QuarkusBean bean) {
        Set<String> keys = new TreeSet<>();
        bean.getCreate().forEach(alternative -> keys.addAll(QuarkusBean.keys(alternative)));
        return keys;
    }

    /** Joins two comma-separated lists without duplicates. */
    private static String join(String a, String b) {
        Set<String> items = new LinkedHashSet<>();
        for (String item : (a + "," + b).split(",")) {
            if (!item.isBlank()) {
                items.add(item.trim());
            }
        }
        return String.join(",", items);
    }

    /** Why other files keep the module forage.*, or {@code null}. */
    private static String otherFiles(Path resources, String module, List<String> moduleFiles) throws IOException {
        try (Stream<Path> files = Files.list(resources)) {
            for (Path profileFile : files.filter(f -> f.getFileName().toString().startsWith("application-"))
                    .toList()) {
                Map<String, Map<String, String>> configs = new TreeMap<>();
                collect(module, "", read(profileFile, StandardCharsets.ISO_8859_1), configs, new TreeMap<>());
                if (!configs.isEmpty()) {
                    return "profile-specific values in " + profileFile.getFileName();
                }
            }
        }
        // read by Forage at runtime from outside the project
        for (String file : moduleFiles) {
            String configDir = System.getenv("FORAGE_CONFIG_DIR");
            if (configDir != null && Files.isRegularFile(Path.of(configDir, file))) {
                return file + " is read from FORAGE_CONFIG_DIR";
            }
            for (PropertyFileSource source : PropertyFileLocator.getBuiltInSources()) {
                if (source.name().equals("HomeDirectoryPropertyFileSource")) {
                    try (InputStream in = source.locate(file)) {
                        if (in != null) {
                            return file + " is read from the Forage home directory";
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * {@code FORAGE_<KEY>}, then {@code forage.<key>}, then the exported value: the order Forage reads them. A
     * {@code literal} value (module file or catalog default, which Forage reads as it is) gets each {@code $} escaped
     * as {@code \$}; a value of application.properties is read by Quarkus as in its Forage key ({@code $$} and
     * {@code \$} are a {@code $}).
     */
    private static String expression(String forageKey, String value, boolean literal) {
        return "${" + forageKey.replace('.', '_').toUpperCase(Locale.ROOT) + ":${" + forageKey + ":"
                + (literal ? value.replace("$", "\\$") : value) + "}}";
    }

    private static String unquoted(String key) {
        return key.replace("\"", "");
    }

    private static Properties read(Path file, Charset charset) throws IOException {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, charset)) {
                properties.load(reader);
            }
        }
        return properties;
    }
}
