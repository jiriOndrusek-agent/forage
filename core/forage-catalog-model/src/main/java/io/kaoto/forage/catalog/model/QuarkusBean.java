package io.kaoto.forage.catalog.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How the Quarkus export creates the bean of a module with plain Camel {@code camel.beans.<bean>} properties. Forage
 * keys are given after {@code forage.<module>.}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class QuarkusBean {

    /** A {@code {key}} of an alternative: the value of a Forage key. */
    public static final Pattern KEY = Pattern.compile("\\{([a-z0-9.-]+)}");

    /**
     * The alternatives for {@code camel.beans.<bean>}; {@code {key}} is the value of a Forage key. The first one whose
     * keys are all set (or have a default) is used.
     */
    @JsonProperty("create")
    private List<String> create;

    /** Per Forage key: the bean properties it sets (dotted: a nested bean). */
    @JsonProperty("properties")
    private Map<String, List<String>> properties;

    /** Per bean property: the {@code #class:} value creating it, written only when a key mapped below it is set. */
    @JsonProperty("nested")
    private Map<String, String> nested;

    /** Other classes the native build reflects on (the {@code #class:} ones are added). */
    @JsonProperty("reflection")
    private List<String> reflection;

    /** The {@code groupId:artifactId} of the classes the native build reflects on. */
    @JsonProperty("indexDependencies")
    private List<String> indexDependencies;

    /** The Forage keys naming a classpath resource the native image must include. */
    @JsonProperty("resources")
    private List<String> resources;

    /** Native-image arguments the bean needs (no commas). */
    @JsonProperty("nativeBuildArgs")
    private List<String> nativeBuildArgs;

    public QuarkusBean() {}

    public QuarkusBean(
            List<String> create,
            Map<String, List<String>> properties,
            Map<String, String> nested,
            List<String> reflection,
            List<String> indexDependencies,
            List<String> resources,
            List<String> nativeBuildArgs) {
        this.create = create;
        this.properties = properties;
        this.nested = nested;
        this.reflection = reflection;
        this.indexDependencies = indexDependencies;
        this.resources = resources;
        this.nativeBuildArgs = nativeBuildArgs;
    }

    public List<String> getCreate() {
        return create == null ? List.of() : create;
    }

    public void setCreate(List<String> create) {
        this.create = create;
    }

    public Map<String, List<String>> getProperties() {
        return properties == null ? Map.of() : properties;
    }

    public void setProperties(Map<String, List<String>> properties) {
        this.properties = properties;
    }

    public Map<String, String> getNested() {
        return nested == null ? Map.of() : nested;
    }

    public void setNested(Map<String, String> nested) {
        this.nested = nested;
    }

    public List<String> getReflection() {
        return reflection == null ? List.of() : reflection;
    }

    public void setReflection(List<String> reflection) {
        this.reflection = reflection;
    }

    public List<String> getIndexDependencies() {
        return indexDependencies == null ? List.of() : indexDependencies;
    }

    public void setIndexDependencies(List<String> indexDependencies) {
        this.indexDependencies = indexDependencies;
    }

    public List<String> getResources() {
        return resources == null ? List.of() : resources;
    }

    public void setResources(List<String> resources) {
        this.resources = resources;
    }

    public List<String> getNativeBuildArgs() {
        return nativeBuildArgs == null ? List.of() : nativeBuildArgs;
    }

    public void setNativeBuildArgs(List<String> nativeBuildArgs) {
        this.nativeBuildArgs = nativeBuildArgs;
    }

    /** The Forage keys of the {@code {key}} values of an alternative, in order. */
    public static Set<String> keys(String alternative) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = KEY.matcher(alternative);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }
}
