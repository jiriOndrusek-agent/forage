package io.kaoto.forage.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.dsl.jbang.core.common.CamelJBangPlugin;
import org.apache.camel.dsl.jbang.core.common.CommandLineHelper;
import org.apache.camel.dsl.jbang.core.common.Plugin;
import org.apache.camel.dsl.jbang.core.common.PluginExporter;
import org.apache.camel.dsl.jbang.core.common.PluginRunCustomizer;
import org.apache.camel.dsl.jbang.core.common.Printer;
import org.apache.camel.main.KameletMain;
import org.apache.camel.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.kaoto.forage.core.common.ExportCustomizer;
import io.kaoto.forage.core.common.RuntimeType;
import io.kaoto.forage.plugin.config.ConfigCommand;
import io.kaoto.forage.plugin.config.ConfigReadCommand;
import io.kaoto.forage.plugin.config.ConfigWriteCommand;
import picocli.CommandLine;

@CamelJBangPlugin(name = "camel-jbang-plugin-forage", firstVersion = "4.16.0")
public class ForagePlugin implements Plugin {

    private static final Logger LOG = LoggerFactory.getLogger(ForagePlugin.class);

    /** The {@code --property} values of the run in progress, taken by its dependency resolution. */
    private static final AtomicReference<Map<String, String>> RUN_PROPERTIES = new AtomicReference<>();

    /**
     * The system properties with the Quarkus dependencies and the translation of the last run. Camel JBang loads the
     * plugin in a new class loader for the export call, so static state does not reach it.
     */
    static final String RUN_DEPENDENCIES = "io.kaoto.forage.plugin.run.dependencies";

    static final String RUN_TRANSLATION = "io.kaoto.forage.plugin.run.translation";

    @Override
    public void customize(CommandLine commandLine, CamelJBangMain main) {
        commandLine.addSubcommand(
                "forage",
                new CommandLine(new ForageCommand(main))
                        .addSubcommand(
                                "config",
                                new CommandLine(new ConfigCommand(main))
                                        .addSubcommand("read", new CommandLine(new ConfigReadCommand(main)))
                                        .addSubcommand("write", new CommandLine(new ConfigWriteCommand(main))))
                        .addSubcommand("run", new CommandLine(new ForageRun(main)))
                        .addSubcommand("export", new ForageExport(main)));
    }

    @Override
    public Optional<PluginRunCustomizer> getRunCustomizer() {
        return Optional.of(this::beforeRun);
    }

    /**
     * Exporter is used to add runtime dependencies for both `camel run` and `camel export`
     */
    @Override
    public Optional<PluginExporter> getExporter() {
        return Optional.of(new PluginExporter() {
            @Override
            public Set<String> getDependencies(org.apache.camel.dsl.jbang.core.common.RuntimeType runtimeType) {
                return dependencies(
                        RuntimeType.fromValue(runtimeType.name()),
                        CommandLineHelper.getWorkDir().resolve("work/src/main/resources"));
            }

            @Override
            public boolean contributeRuntimeDependencies() {
                return true;
            }

            @Override
            public void addSourceFiles(Path buildDir, String packageName, Printer printer) {
                // This exporter contributes only runtime dependencies, not source files.
            }
        });
    }

    /**
     * The dependencies of a Camel JBang run (also the silent run an export starts before it writes the resources):
     * Quarkus configurations are translated from the project directory and the run's {@code --property} values, never
     * from the export work directory (it may be left over). The export call writes that translation: Camel JBang
     * rewrites the exported {@code application.properties} without escaping, so its values are only compared with what
     * the run read. It fails if the silent run contributed a dependency it does not: Camel JBang adds those to the
     * export.
     *
     * @param exportedResources the exported resources, written when no run is in progress
     */
    static Set<String> dependencies(RuntimeType runtime, Path exportedResources) {
        Map<String, String> runProperties = RUN_PROPERTIES.getAndSet(null);
        boolean run = runProperties != null;
        // used once, also on failure: a later export in this JVM must not see them
        String runDependencies = run ? null : System.clearProperty(RUN_DEPENDENCIES);
        String runTranslation = run ? null : System.clearProperty(RUN_TRANSLATION);
        QuarkusConfigExport.Run translation = null;
        QuarkusConfigExport.Result quarkusExport = null;
        if (runtime == RuntimeType.QUARKUS) {
            if (run) {
                translation = translateProject(runProperties);
                quarkusExport = translation.result();
            } else {
                quarkusExport = exportQuarkusProperties(
                        exportedResources, runTranslation != null ? QuarkusConfigExport.decode(runTranslation) : null);
            }
        }
        // gather dependencies across all (enabled) export customizers for the specific runtime
        Set<String> dependencies = ExportHelper.getAllCustomizers(quarkusExport)
                .filter(ExportCustomizer::isEnabled)
                .map(exportCustomizer -> exportCustomizer.resolveRuntimeDependencies(runtime))
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(TreeSet::new));
        if (runtime == RuntimeType.QUARKUS) {
            if (run) {
                System.setProperty(RUN_TRANSLATION, QuarkusConfigExport.encode(translation));
                System.setProperty(RUN_DEPENDENCIES, String.join(",", dependencies));
            } else if (runDependencies != null) {
                checkRunDependencies(runDependencies, dependencies);
            }
        }
        return dependencies;
    }

    /** Called when a Camel JBang run starts, with its {@code --property} values. */
    static void runStarted(Map<String, String> properties) {
        RUN_PROPERTIES.set(properties);
    }

    private static QuarkusConfigExport.Run translateProject(Map<String, String> runProperties) {
        try {
            return QuarkusConfigExport.run(
                    CatalogDrivenExportCustomizer.workingDir().toPath(), runProperties);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the Forage properties", e);
        }
    }

    private static void checkRunDependencies(String run, Set<String> dependencies) {
        Set<String> extra = new TreeSet<>(List.of(run.split(",")));
        extra.remove("");
        extra.removeAll(dependencies);
        if (!extra.isEmpty()) {
            throw new IllegalStateException("The exported project would also get " + extra
                    + " from the run of the project directory, which does not match the exported Forage "
                    + "configuration. Set the Forage values in the project files instead of with --property.");
        }
    }

    /**
     * Writes what the run translated as {@code quarkus.*} properties into the project being exported. Camel JBang calls
     * {@code getDependencies} after it has written the exported resources (including the {@code --property} values),
     * and it has no hook for the exported properties.
     *
     * @param run what the run of the project directory translated, or {@code null} when the export reuses earlier run
     *     data
     * @return what was translated, or {@code null} when there are no exported resources
     */
    static QuarkusConfigExport.Result exportQuarkusProperties(Path resources, QuarkusConfigExport.Run run) {
        if (!Files.isDirectory(resources)) {
            return run != null ? run.result() : null;
        }
        try {
            if (run == null) {
                // nothing to compare the exported values with
                QuarkusConfigExport.Run exported = QuarkusConfigExport.run(resources, Map.of());
                if (!exported.result().modules().isEmpty()
                        || !exported.application().isEmpty()) {
                    throw new IllegalStateException("The export reuses the data of an earlier run, which may not "
                            + "match the exported Forage configuration. Export again with --fresh.");
                }
                return exported.result();
            }
            QuarkusConfigExport.checkExported(run, resources);
            QuarkusConfigExport.write(run.result(), resources);
            Printer printer = new Printer.SystemOutPrinter();
            run.result().messages().forEach(printer::println);
            return run.result();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write the Quarkus properties of the exported project", e);
        }
    }

    private void beforeRun(KameletMain main, List<String> files) {
        Map<String, String> runProperties = new LinkedHashMap<>();
        Properties arguments = main.getArgumentProperties();
        if (arguments != null) {
            arguments.stringPropertyNames().forEach(key -> runProperties.put(key, arguments.getProperty(key)));
        }
        runStarted(runProperties);
        resolveConfigDir(files);
        propagateProfile(main);
        addCatalogDrivenRepos(main);
        validateProperties();
    }

    /**
     * Validates Forage properties when running via plain {@code camel run}.
     *
     * <p>Skipped when a Forage command ({@code camel forage run}/{@code camel forage export}) has
     * already validated with the user's {@code --skip-validation}/{@code --strict} flags, to avoid
     * printing warnings twice.
     *
     * <p>For plain {@code camel run} (where the Forage command options are not available),
     * validation is controlled via the {@code forage.validation.skip} system property (or
     * {@code FORAGE_VALIDATION_SKIP} environment variable) to skip validation, and
     * {@code forage.validation.strict} (or {@code FORAGE_VALIDATION_STRICT}) to fail the run on
     * validation warnings.
     *
     * <p>{@link PluginRunCustomizer#beforeRun} returns {@code void} and Camel JBang's {@code Run}
     * invokes it outside any try/catch, so in strict mode the only way to abort the run is to
     * throw. The exception propagates to picocli's execution exception handler, which prints it
     * and exits with a non-zero code.
     */
    private static void validateProperties() {
        if (ForagePropertyValidator.consumeValidationHandled()) {
            return;
        }

        boolean skip = booleanOverride("forage.validation.skip", "FORAGE_VALIDATION_SKIP");
        boolean strict = booleanOverride("forage.validation.strict", "FORAGE_VALIDATION_STRICT");

        int result = ForagePropertyValidator.validateAndReport(new Printer.SystemOutPrinter(), skip, strict);
        if (result != 0) {
            throw new RuntimeException("Forage property validation failed in strict mode. "
                    + "Fix the reported warnings, or unset forage.validation.strict / FORAGE_VALIDATION_STRICT.");
        }
    }

    private static boolean booleanOverride(String systemProperty, String envVar) {
        String value = System.getProperty(systemProperty);
        if (value == null) {
            value = System.getenv(envVar);
        }
        return Boolean.parseBoolean(value);
    }

    // JVM-global side effect: sets camel.main.profile so ForagePropertyScanner can resolve the
    // active profile. Safe because camel run/export each run in their own JVM process — no cleanup
    // needed. Consistent with how forage.config.dir is handled in resolveConfigDir.
    private static void propagateProfile(KameletMain main) {
        if (System.getProperty("camel.main.profile") != null) {
            return;
        }
        String profile = main.getProfile();
        if (profile != null) {
            LOG.debug("Propagating camel.main.profile={} from KameletMain", profile);
            System.setProperty("camel.main.profile", profile);
        }
    }

    private static void addCatalogDrivenRepos(KameletMain main) {
        try {
            CatalogDrivenExportCustomizer customizer = new CatalogDrivenExportCustomizer();
            Set<String> repos = customizer.resolveRepositories();
            for (String repoUrl : repos) {
                addRepository(main, repoUrl);
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve catalog-driven repositories: {}", e.getMessage());
        }
    }

    private static void addRepository(KameletMain main, String repoUrl) {
        String existing = main.getRepositories();
        if (existing != null && existing.contains(repoUrl)) {
            return;
        }
        String repos = existing == null || existing.isBlank() ? repoUrl : existing + "," + repoUrl;
        main.setRepositories(repos);
    }

    /**
     * Derives the configuration directory from the file arguments, mirroring
     * how Camel's {@code Run} resolves its base directory.
     *
     * <ul>
     *   <li>Single directory argument (e.g., {@code camel run agent}) — use that directory</li>
     *   <li>Multiple files sharing a common parent different from CWD
     *       (e.g., shell-expanded {@code agent/*}) — use that common parent</li>
     *   <li>Otherwise — leave unset (CWD is fine)</li>
     * </ul>
     *
     * <p>Sets {@code forage.config.dir} system property so that {@code ConfigStore},
     * {@code ConfigHelper}, and {@code ForagePropertyValidator}
     * all resolve properties from the correct location.
     *
     * <p>Called from both the {@link PluginRunCustomizer} SPI ({@code camel run})
     * and {@link ForageRun} ({@code camel forage run}).
     */
    static void resolveConfigDir(List<String> files) {
        if (files == null || files.isEmpty()) {
            return;
        }

        Path configDir = null;

        // Single directory argument
        if (files.size() == 1) {
            String name = FileUtil.stripTrailingSeparator(files.get(0));
            Path first = Path.of(name);
            if (Files.isDirectory(first)) {
                configDir = first;
            }
        }

        // Multiple files (e.g., shell-expanded glob) — find common parent
        if (configDir == null && files.size() > 1) {
            configDir = commonParent(files);
        }

        if (configDir != null) {
            Path cwd = Path.of("").toAbsolutePath();
            Path resolved = configDir.toAbsolutePath().normalize();
            if (!resolved.equals(cwd)) {
                LOG.debug("Setting forage.config.dir={} (derived from file arguments)", resolved);
                System.setProperty("forage.config.dir", resolved.toString());
            }
        }
    }

    /**
     * Returns the common parent directory of the given file paths,
     * or {@code null} if there is no single common parent.
     */
    static Path commonParent(List<String> filePaths) {
        Path common = null;
        for (String filePath : filePaths) {
            Path parent = Path.of(filePath).toAbsolutePath().normalize().getParent();
            if (parent == null) {
                return null;
            }
            if (common == null) {
                common = parent;
            } else if (!common.equals(parent)) {
                return null;
            }
        }
        return common;
    }
}
