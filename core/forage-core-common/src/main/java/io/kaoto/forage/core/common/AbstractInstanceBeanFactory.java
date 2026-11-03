package io.kaoto.forage.core.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.camel.CamelContext;
import org.apache.camel.Component;
import org.apache.camel.spi.PropertyConfigurer;
import org.apache.camel.spi.PropertyConfigurerGetter;
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
    // Component options that held a bean of this factory when cleanup() ran
    private final List<ComponentOption> retiredOptions = new ArrayList<>();
    private CamelContext camelContext;

    private record ComponentOption(String component, String option, String bean) {}

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

    /**
     * The error for a bean name that a bean of another type uses, as {@code #bean:<name>} would
     * return either of them. Runtime adapters use it too.
     */
    public static String nameInUse(String name, String type, String otherType) {
        return "Cannot register %s bean '%s': the name is taken by a bean of type %s. Give each bean its own name."
                .formatted(simpleName(type), name, simpleName(otherType));
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    @Override
    public synchronized void configure() {
        Map<String, T> created = new LinkedHashMap<>();
        instances(descriptor, Set.of()).forEach((name, prefix) -> {
            Object existing = camelContext.getRegistry().lookupByName(name);
            if (existing == null) {
                created.put(name, createBean(prefix));
            } else if (!descriptor.primaryBeanClass().isInstance(existing)) {
                throw new IllegalStateException(nameInUse(
                        name,
                        descriptor.primaryBeanClass().getName(),
                        existing.getClass().getName()));
            }
        });
        // Bind once all beans are created, so a failing instance binds none of them
        created.forEach((name, bean) -> {
            camelContext.getRegistry().bind(name, bean);
            LOG.info("Registered {} bean '{}'", descriptor.primaryBeanClass().getSimpleName(), name);
        });
        beans.putAll(created);

        // After a reload, give the components the new beans of the same name
        for (ComponentOption retired : retiredOptions) {
            Object bean = camelContext.getRegistry().lookupByNameAndType(retired.bean(), descriptor.primaryBeanClass());
            Component component = camelContext.hasComponent(retired.component());
            if (bean != null && component != null) {
                component
                        .getComponentPropertyConfigurer()
                        .configure(camelContext, component, retired.option(), bean, true);
            }
        }
        retiredOptions.clear();
    }

    @Override
    public synchronized void cleanup() {
        beans.forEach((name, bean) -> {
            if (camelContext.getRegistry().lookupByNameAndType(name, descriptor.primaryBeanClass()) == bean) {
                camelContext.getRegistry().unbind(name);
            }
        });
        // Components keep the beans they were autowired with: clear them until configure() binds the new ones
        for (String componentName : camelContext.getComponentNames()) {
            Component component = camelContext.hasComponent(componentName);
            PropertyConfigurer configurer = component != null ? component.getComponentPropertyConfigurer() : null;
            if (configurer instanceof PropertyConfigurerGetter getter && getter.getAutowiredNames() != null) {
                for (String option : getter.getAutowiredNames()) {
                    Object value = getter.getOptionValue(component, option, true);
                    beans.forEach((name, bean) -> {
                        if (value == bean) {
                            configurer.configure(camelContext, component, option, null, true);
                            retiredOptions.add(new ComponentOption(componentName, option, name));
                        }
                    });
                }
            }
        }
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
