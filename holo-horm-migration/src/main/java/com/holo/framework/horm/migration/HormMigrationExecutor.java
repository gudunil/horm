package com.holo.framework.horm.migration;

import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.MigrationExecutor;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Default implementation of {@link MigrationExecutor} that integrates with HORM.
 *
 * <p>This executor uses the current {@link HormContext} to access the
 * {@link DataSourceRegistry} and executes migrations on all registered
 * datasources using {@link MultiDataSourceMigrationRunner}.
 *
 * <p>Discovered via {@link java.util.ServiceLoader} when {@code Horm.migrate()}
 * is called.
 *
 * <p>To customize migration configuration before calling {@code Horm.migrate()}:
 * <pre>{@code
 * HormMigrationExecutor.configure(c -> c.table("my_history").addLocation("classpath:db/migrations"));
 * }</pre>
 */
public final class HormMigrationExecutor implements MigrationExecutor {

    private static volatile FlywayMigrationConfig config = FlywayMigrationConfig.builder().build();

    /**
     * Returns the current migration configuration.
     */
    public static FlywayMigrationConfig getConfig() {
        return config;
    }

    /**
     * Replaces the current migration configuration with the given one.
     *
     * @param config the new configuration
     */
    public static void configure(FlywayMigrationConfig config) {
        HormMigrationExecutor.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Configures the migration using a {@link FlywayMigrationConfig.Builder} consumer.
     *
     * <p>This is the recommended way to customize migration settings:
     * <pre>{@code
     * HormMigrationExecutor.configure(c -> {
     *     c.table("my_history");
     *     c.addLocation("classpath:db/migrations");
     * });
     * }</pre>
     *
     * @param configurer a consumer that configures the builder
     */
    public static void configure(Consumer<FlywayMigrationConfig.Builder> configurer) {
        Objects.requireNonNull(configurer, "configurer must not be null");
        FlywayMigrationConfig.Builder builder = FlywayMigrationConfig.builder();
        configurer.accept(builder);
        config = builder.build();
    }

    @Override
    public Map<String, Integer> migrateAll() {
        HormContext ctx = HormContext.current();
        DataSourceRegistry registry = ctx.dataSourceRegistry();
        MultiDataSourceMigrationRunner runner = new MultiDataSourceMigrationRunner(registry, config);
        return runner.migrateAll();
    }

    @Override
    public int migrate(String dataSourceName) {
        HormContext ctx = HormContext.current();
        DataSourceRegistry registry = ctx.dataSourceRegistry();
        MultiDataSourceMigrationRunner runner = new MultiDataSourceMigrationRunner(registry, config);
        return runner.migrate(dataSourceName);
    }
}
