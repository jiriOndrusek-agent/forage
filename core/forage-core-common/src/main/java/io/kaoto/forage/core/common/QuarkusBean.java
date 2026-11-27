package io.kaoto.forage.core.common;

import java.util.List;
import java.util.Map;

/**
 * How the Quarkus export creates the bean of a module with plain Camel {@code camel.beans.<bean>} properties, without
 * the Forage runtime. A module descriptor returns it from {@code quarkusBean()}; the catalog build reads it by
 * reflection. Forage keys are given after {@code forage.<module>.}.
 *
 * @param create the alternatives for {@code camel.beans.<bean>} (e.g. {@code #class:a.B} or
 *     {@code #class:a.Factory#create('{url}')}); {@code {key}} is the value of a Forage key. The first alternative whose
 *     keys are all set (or have a default) is used
 * @param properties per Forage key: the bean properties it sets ({@code camel.beans.<bean>.<property>}; dotted:
 *     a nested bean)
 * @param nested per bean property: the {@code #class:} value creating it; written only when a key mapped below it is
 *     set
 * @param reflection other classes the native build reflects on (the {@code #class:} ones are added), e.g. of a nested
 *     bean reached through a getter
 * @param indexDependencies the {@code groupId:artifactId} of the classes the native build reflects on
 * @param resources the Forage keys naming a classpath resource the native image must include
 * @param nativeBuildArgs native-image arguments the bean needs (no commas)
 * @since 1.6
 */
public record QuarkusBean(
        List<String> create,
        Map<String, List<String>> properties,
        Map<String, String> nested,
        List<String> reflection,
        List<String> indexDependencies,
        List<String> resources,
        List<String> nativeBuildArgs) {}
