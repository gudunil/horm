package com.holo.framework.horm.migration;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.migration.internal.ConnectionDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 多数据源迁移运行器。
 *
 * <p>为 {@link DataSourceRegistry} 中的每个数据源创建独立的 Flyway 实例，
 * 各自维护独立的迁移历史表。
 */
public final class MultiDataSourceMigrationRunner {

    private final DataSourceRegistry registry;
    private final FlywayMigrationConfig config;

    public MultiDataSourceMigrationRunner(DataSourceRegistry registry, FlywayMigrationConfig config) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * 对所有注册的数据源执行迁移。
     *
     * @return 每个数据源名称到已应用迁移数量的映射
     */
    public Map<String, Integer> migrateAll() {
        Map<String, Integer> results = new LinkedHashMap<>();
        for (Map.Entry<String, DataSourceProvider> entry : registry.entries()) {
            String name = entry.getKey();
            DataSourceProvider provider = entry.getValue();
            try {
                Connection conn = provider.getConnection();
                try {
                    ConnectionDataSource ds = new ConnectionDataSource(conn);
                    FlywayMigrationRunner runner = new FlywayMigrationRunner(ds, config);
                    int count = runner.migrate();
                    results.put(name, count);
                } finally {
                    provider.releaseConnection(conn);
                }
            } catch (SQLException e) {
                throw new MigrationException("Failed to migrate datasource: " + name, e);
            }
        }
        return results;
    }

    /**
     * 对指定数据源执行迁移。
     *
     * @param dataSourceName 数据源名称
     * @return 已应用的迁移数量
     */
    public int migrate(String dataSourceName) {
        DataSourceProvider provider = registry.get(dataSourceName);
        try {
            Connection conn = provider.getConnection();
            try {
                ConnectionDataSource ds = new ConnectionDataSource(conn);
                FlywayMigrationRunner runner = new FlywayMigrationRunner(ds, config);
                return runner.migrate();
            } finally {
                provider.releaseConnection(conn);
            }
        } catch (SQLException e) {
            throw new MigrationException("Failed to migrate datasource: " + dataSourceName, e);
        }
    }
}
