package io.kaoto.forage.core.common;

import java.util.Map;
import java.util.Set;
import io.kaoto.forage.core.util.config.Config;

/**
 * Opts a Forage module in to {@code camel export --runtime=quarkus} as native {@code quarkus.*} properties. Register
 * implementations in {@code META-INF/services}; modules without one stay {@code forage.*}.
 */
public interface QuarkusNativeHook<C extends Config> {

    ForageModuleDescriptor<C, ?> descriptor();

    /** Why the configuration ({@code name} is {@code null} for the default one) stays forage.*, or {@code null}. */
    default String unsupported(String name, C config) {
        return null;
    }

    /** Adjusts the translated properties and returns the catalog groups they enable. */
    default Set<String> adjust(C config, Map<String, String> translated) {
        return Set.of();
    }
}
