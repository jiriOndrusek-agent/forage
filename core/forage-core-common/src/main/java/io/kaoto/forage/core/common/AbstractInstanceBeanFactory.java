package io.kaoto.forage.core.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.camel.CamelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.kaoto.forage.core.util.config.Config;
import io.kaoto.forage.core.util.config.ConfigStore;

/**
 * Binds one bean per configured instance of a module: a bean per named instance, or the default
 * bean ({@link ForageModuleDescriptor#defaultBeanName()}) when there is none. Beans already in the
 * registry are kept, and {@link #cleanup()} unbinds only the beans this factory bound.
 *
 * @param <T> the bean type
 * @since 1.6
 */
public abstract class AbstractInstanceBeanFactory<T> implements BeanFactory {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractInstanceBeanFactory.class);

    private final ForageModuleDescriptor<?, ?> descriptor;
    private final Map<String, T> beans = new LinkedHashMap<>();
    private CamelContext camelContext;

    protected AbstractInstanceBeanFactory(ForageModuleDescriptor<?, ?> descriptor) {
        this.descriptor = descriptor;
    }

    /**
     * Creates the bean of an instance.
     *
     * @param prefix the instance name, or {@code null} for the default instance
     */
    protected abstract T createBean(String prefix);

    /**
     * Finds the configured instances: the bean name mapped to the config prefix ({@code null} for the
     * default bean). Named instances replace the default one. Runtime adapters use it too.
     *
     * @param extraNames instance names found by the runtime itself (e.g. Quarkus config sources)
     */
    public static Map<String, String> instances(ForageModuleDescriptor<?, ?> descriptor, Set<String> extraNames) {
        Config config = descriptor.createConfig(null);
        Set<String> names =
                new TreeSet<>(ConfigStore.getInstance().readPrefixes(config, descriptor.namedPropertyRegexp()));
        names.addAll(extraNames);
        Map<String, String> instances = new LinkedHashMap<>();
        if (!names.isEmpty()) {
            names.forEach(name -> instances.put(name, name));
        } else if (!ConfigStore.getInstance()
                .readPrefixes(config, descriptor.defaultPropertyRegexp())
                .isEmpty()) {
            instances.put(descriptor.defaultBeanName(), null);
        }
        return instances;
    }

    @Override
    public synchronized void configure() {
        Map<String, T> created = new LinkedHashMap<>();
        instances(descriptor, Set.of()).forEach((name, prefix) -> {
            if (camelContext.getRegistry().lookupByNameAndType(name, descriptor.primaryBeanClass()) == null) {
                created.put(name, createBean(prefix));
            }
        });
        // Bind once all beans are created, so a failing instance binds none of them
        created.forEach((name, bean) -> {
            camelContext.getRegistry().bind(name, bean);
            LOG.info("Registered {} bean '{}'", descriptor.primaryBeanClass().getSimpleName(), name);
        });
        beans.putAll(created);
    }

    @Override
    public synchronized void cleanup() {
        beans.forEach((name, bean) -> {
            if (camelContext.getRegistry().lookupByNameAndType(name, descriptor.primaryBeanClass()) == bean) {
                camelContext.getRegistry().unbind(name);
            }
        });
        beans.clear();
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
        ConfigStore.getInstance().setClassLoader(camelContext.getApplicationContextClassLoader());
    }

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }
}
