package com.holo.framework.horm.migration;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.migration.internal.ConnectionDataSource;

import java.sql.Connection;

/**
 * 执行迁移命令。
 */
public final class MigrateCommand implements MigrationCommand {

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
                int count = runner.migrate();
                return new CommandResult(true, "Successfully applied " + count + " migration(s)");
            } finally {
                provider.releaseConnection(conn);
            }
        } catch (Exception e) {
            return new CommandResult(false, "Migration failed: " + e.getMessage());
        }
    }
}
