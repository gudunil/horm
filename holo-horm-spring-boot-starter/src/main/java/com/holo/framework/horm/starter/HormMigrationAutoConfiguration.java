package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.Horm;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Flyway 自动迁移配置。
 *
 * <p>当类路径中存在 {@link Horm} 和 {@link Flyway} 类时自动激活，
 * 在应用启动时执行数据库迁移。
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code holo.horm.migration.enabled} - 是否启用迁移功能（默认 true）</li>
 *   <li>{@code holo.horm.migration.auto-on-startup} - 是否在启动时自动执行迁移（默认 false）</li>
 * </ul>
 *
 * @author Holo Framework Team
 * @since 1.0.0
 */
@Configuration
@ConditionalOnClass({Horm.class, Flyway.class})
@ConditionalOnProperty(prefix = "holo.horm.migration", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HormProperties.class)
public class HormMigrationAutoConfiguration implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(HormMigrationAutoConfiguration.class);

    private final HormProperties properties;

    public HormMigrationAutoConfiguration(HormProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!properties.getMigration().isAutoOnStartup()) {
            log.debug("HORM migration auto-on-startup is disabled, skipping migration");
            return;
        }

        log.info("Starting HORM database migration...");
        Map<String, Integer> result = Horm.migrate();

        if (result.isEmpty()) {
            log.info("No migrations executed - all datasources are up to date");
            return;
        }

        result.forEach((datasource, count) -> {
            if (count > 0) {
                log.info("Executed {} migration(s) on datasource '{}'", count, datasource);
            } else {
                log.debug("No migrations executed on datasource '{}'", datasource);
        }
        });

        int total = result.values().stream().mapToInt(Integer::intValue).sum();
        log.info("HORM migration completed: {} total migration(s) executed across {} datasource(s)",
            total, result.size());
    }
}
