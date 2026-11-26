package io.kaoto.forage.plugin.config;

import java.util.Arrays;
import java.util.Iterator;
import org.aesh.command.option.Option;
import org.apache.camel.dsl.jbang.core.common.CompletionCandidates;
import io.kaoto.forage.catalog.reader.ForageCatalogReader;
import io.kaoto.forage.config.editor.PropertiesFileStrategy;
import io.kaoto.forage.config.editor.PropertiesFileStrategyType;

/**
 * Mixin providing the shared {@code --strategy} option of the {@code config} commands.
 *
 * <p>The raw value is kept as a string and parsed in {@link #resolve(ForageCatalogReader)}, so that an unknown
 * value is reported by the command as a JSON error (exit code 1) instead of a usage error.
 * Values are matched case-insensitively.
 */
public class PropertiesFileStrategyOption {

    @Option(
            name = "strategy",
            shortName = 's',
            description = "Property file strategy: 'application' uses application.properties (default), "
                    + "'forage' uses the per-factory forage-*.properties files.",
            defaultValue = "application",
            completer = Candidates.class)
    String strategy;

    /**
     * Creates the strategy selected on the command line.
     *
     * @throws IllegalArgumentException if the selected strategy is unknown
     */
    PropertiesFileStrategy resolve(ForageCatalogReader catalog) {
        return PropertiesFileStrategyType.fromId(strategy).create(catalog);
    }

    public static final class Candidates extends CompletionCandidates {
        @Override
        public Iterator<String> iterator() {
            return Arrays.stream(PropertiesFileStrategyType.values())
                    .map(PropertiesFileStrategyType::id)
                    .iterator();
        }
    }
}
