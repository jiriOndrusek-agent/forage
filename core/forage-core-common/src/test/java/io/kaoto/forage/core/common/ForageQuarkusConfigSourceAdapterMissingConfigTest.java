package io.kaoto.forage.core.common;

import java.util.Map;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.core.util.config.MissingConfigException;
import io.kaoto.forage.core.util.config.PlaceholderTestConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Quarkus: an incomplete configuration, e.g. a {@code FORAGE_*} variable overriding part of the properties an export
 * translated to {@code quarkus.*}, fails with a hint to the Quarkus configuration.
 */
class ForageQuarkusConfigSourceAdapterMissingConfigTest {

    private static final String PASSWORD_KEY =
            PlaceholderTestConfig.PlaceholderTestConfigEntries.PASSWORD.propertyName();

    @AfterEach
    void reset() {
        System.clearProperty(PASSWORD_KEY);
        ConfigStore.getInstance().reload();
    }

    @Test
    void missingConfigurationPointsToQuarkusConfiguration() {
        System.setProperty(PASSWORD_KEY, "secret");

        assertThatThrownBy(() -> new SmallRyeConfigBuilder()
                        .withSources(new MissingConfigAdapter())
                        .build())
                .hasStackTraceContaining(MissingConfigException.class.getName())
                .hasStackTraceContaining("Kind is required but not configured")
                .hasStackTraceContaining("forage.placeholdertest.*")
                .hasStackTraceContaining("QUARKUS_*");
    }

    static class MissingConfigAdapter extends ForageQuarkusConfigSourceAdapter<PlaceholderTestConfig> {
        @Override
        protected ForageModuleDescriptor<PlaceholderTestConfig, ?> descriptor() {
            return new ForageQuarkusConfigSourceAdapterPlaceholderTest.TestDescriptor() {
                @Override
                public Map<String, String> translateProperties(String prefix, PlaceholderTestConfig config) {
                    throw new MissingConfigException("Kind is required but not configured");
                }
            };
        }
    }
}
