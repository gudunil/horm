package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HormMigrationAutoConfiguration}.
 */
class HormMigrationAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HormMigrationAutoConfiguration.class));

    @Test
    void migrationAutoConfigurationLoaded() {
        contextRunner
            .withUserConfiguration(TestConfig.class)
            .run(context -> {
                assertThat(context).hasSingleBean(HormMigrationAutoConfiguration.class);
            });
    }

    @Test
    void migrationDisabledByProperty() {
        contextRunner
            .withPropertyValues("holo.horm.migration.enabled=false")
            .withUserConfiguration(TestConfig.class)
            .run(context -> {
                assertThat(context).doesNotHaveBean(HormMigrationAutoConfiguration.class);
            });
    }

    @Test
    void migrationEnabledByDefault() {
        contextRunner
            .withUserConfiguration(TestConfig.class)
            .run(context -> {
                assertThat(context).hasSingleBean(HormMigrationAutoConfiguration.class);
            });
    }

    @Test
    void autoOnStartupDefaultValueTrue() {
        contextRunner
            .withUserConfiguration(TestConfig.class)
            .run(context -> {
                HormMigrationAutoConfiguration config = context.getBean(HormMigrationAutoConfiguration.class);
                assertThat(config).isNotNull();
            });
    }

    @Configuration
    static class TestConfig {
        @Bean
        HormContext hormContext() {
            return new HormContext(new TestDataSourceProvider());
        }
    }
}
