package io.kaoto.forage.catalog.model;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Marks the config entry whose value selects the bean (the kind) of a module the Quarkus export translates. The
 * selected bean needs Quarkus runtime dependencies in the catalog.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuarkusSelector {

    /** The kinds translated, each with the Quarkus keys it adds; {@code null}: any kind. */
    @JsonProperty("kinds")
    private Map<String, Map<String, String>> kinds;

    /** Only the default configuration is translated, not named ones. Only written when {@code true}. */
    @JsonProperty("defaultOnly")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean defaultOnly;

    /** Per kind: the Quarkus key of each Forage key (after {@code forage.[<name>.]<module>.}). */
    @JsonProperty("properties")
    private Map<String, Map<String, String>> properties;

    /**
     * Per kind: the Quarkus artifacts replacing the selected bean's artifact. With these the module keeps its Forage
     * runtime, so its keys without a Quarkus key stay {@code forage.*}.
     */
    @JsonProperty("dependencies")
    private Map<String, List<String>> dependencies;

    public QuarkusSelector() {}

    public QuarkusSelector(Map<String, Map<String, String>> kinds, boolean defaultOnly) {
        this.kinds = kinds;
        this.defaultOnly = defaultOnly;
    }

    public Map<String, Map<String, String>> getKinds() {
        return kinds;
    }

    public void setKinds(Map<String, Map<String, String>> kinds) {
        this.kinds = kinds;
    }

    public boolean isDefaultOnly() {
        return defaultOnly;
    }

    public void setDefaultOnly(boolean defaultOnly) {
        this.defaultOnly = defaultOnly;
    }

    public Map<String, Map<String, String>> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Map<String, String>> properties) {
        this.properties = properties;
    }

    public Map<String, List<String>> getDependencies() {
        return dependencies;
    }

    public void setDependencies(Map<String, List<String>> dependencies) {
        this.dependencies = dependencies;
    }
}
