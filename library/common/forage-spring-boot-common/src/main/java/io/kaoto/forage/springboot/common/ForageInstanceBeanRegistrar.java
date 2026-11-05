package io.kaoto.forage.springboot.common;

import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import io.kaoto.forage.core.common.AbstractInstanceBeanFactory;
import io.kaoto.forage.core.common.ForageModuleDescriptor;

/**
 * Registers the beans of an {@link AbstractInstanceBeanFactory} module like plain Camel does: the default
 * bean, or one bean per named instance. Bean definitions of the same type are kept; a name used by
 * another type fails the context.
 *
 * @since 1.6
 */
public class ForageInstanceBeanRegistrar implements BeanDefinitionRegistryPostProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(ForageInstanceBeanRegistrar.class);

    private final ForageModuleDescriptor<?, ?> descriptor;
    private final Function<String, ?> creator;

    /**
     * @param creator creates the bean of an instance; the argument is the instance name, or {@code null}
     */
    public ForageInstanceBeanRegistrar(ForageModuleDescriptor<?, ?> descriptor, Function<String, ?> creator) {
        this.descriptor = descriptor;
        this.creator = creator;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        Class<?> type = descriptor.primaryBeanClass();
        AbstractInstanceBeanFactory.instances(descriptor, Set.of()).forEach((name, prefix) -> {
            if (registry.isBeanNameInUse(name)) {
                Class<?> existing = registry instanceof ConfigurableListableBeanFactory factory
                        ? factory.getType(name, false)
                        : null;
                if (existing != null && !type.isAssignableFrom(existing)) {
                    throw new IllegalStateException(
                            AbstractInstanceBeanFactory.nameInUse(name, type.getName(), existing.getName()));
                }
                return;
            }
            GenericBeanDefinition definition = new GenericBeanDefinition();
            definition.setBeanClass(type);
            definition.setInstanceSupplier(() -> creator.apply(prefix));
            registry.registerBeanDefinition(name, definition);
            LOG.info("Registered {} bean definition '{}'", type.getSimpleName(), name);
        });
    }
}
