package com.holo.framework.horm.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * 基于 Flyway 的迁移运行器。
 *
 * <p>封装 Flyway 引擎，支持 Java 迁移和 SQL 文件迁移。
 */
public final class FlywayMigrationRunner implements MigrationRunner {

    private final DataSource dataSource;
    private final FlywayMigrationConfig config;
    private final Flyway flyway;

    public FlywayMigrationRunner(DataSource dataSource, FlywayMigrationConfig config) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.flyway = buildFlyway();
    }

    private Flyway buildFlyway() {
        FluentConfiguration fluent = Flyway.configure()
            .dataSource(dataSource)
            .table(config.getTable())
            .baselineOnMigrate(config.isBaselineOnMigrate())
            .baselineVersion(config.getBaselineVersion());

        if (!config.getJavaMigrations().isEmpty()) {
            fluent.javaMigrations(config.getJavaMigrations().toArray(
                new org.flywaydb.core.api.migration.JavaMigration[0]));
        }

        if (!config.getLocations().isEmpty()) {
            fluent.locations(config.getLocations().toArray(new String[0]));
        }

        return fluent.load();
    }

    @Override
    public int migrate() {
        return flyway.migrate().migrationsExecuted;
    }

    @Override
    public MigrationStatus status() {
        MigrationInfoService info = flyway.info();
        int applied = 0;
        int pending = 0;
        for (MigrationInfo mi : info.all()) {
            if (mi.getState().isApplied()) {
                applied++;
            } else if (mi.getState() == org.flywaydb.core.api.MigrationState.PENDING) {
                pending++;
            }
        }
        return new MigrationStatus(applied, pending);
    }

    /**
     * 返回底层 Flyway 实例，供高级用法使用。
     */
    public Flyway flyway() {
        return flyway;
    }
}
