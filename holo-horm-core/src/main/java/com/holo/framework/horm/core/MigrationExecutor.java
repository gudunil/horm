package com.holo.framework.horm.core;

import java.util.Map;

/**
 * SPI for executing database migrations.
 *
 * <p>This interface is implemented by the migration module and discovered
 * via {@link java.util.ServiceLoader} at runtime. The core module defines
 * this SPI to avoid a direct dependency on the migration module.
 *
 * <p>Implementations should be thread-safe and reusable across multiple
 * migration invocations.
 */
public interface MigrationExecutor {

    /**
     * Executes migrations on all registered datasources.
     *
     * @return a map from datasource name to the number of migrations applied
     */
    Map<String, Integer> migrateAll();

    /**
     * Executes migrations on the specified datasource.
     *
     * @param dataSourceName the target datasource name
     * @return the number of migrations applied
     */
    int migrate(String dataSourceName);
}
