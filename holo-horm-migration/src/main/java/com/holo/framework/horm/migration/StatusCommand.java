package com.holo.framework.horm.migration;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.migration.internal.ConnectionDataSource;

import java.sql.Connection;

/**
 * 查看迁移状态命令。
 */
public final class StatusCommand implements MigrationCommand {

    @Override
    public CommandResult execute(String[] args) {
        try {
            HormContext ctx = HormContext.current();
            DataSourceRegistry registry = ctx.dataSourceRegistry();
            DataSourceProvider provider = registry.getDefault();
            Connection conn = provider.getConnection();
            try {
                ConnectionDataSource ds = new ConnectionDataSource(conn);
                FlywayMigrationConfig config = HormMigrationExecutor.getConfig();
                FlywayMigrationRunner runner = new FlywayMigrationRunner(ds, config);
                MigrationStatus status = runner.status();
                String message = String.format("Applied: %d, Pending: %d",
                    status.appliedCount(), status.pendingCount());
                return new CommandResult(true, message);
            } finally {
                provider.releaseConnection(conn);
            }
        } catch (Exception e) {
            return new CommandResult(false, "Status check failed: " + e.getMessage());
        }
    }
}
