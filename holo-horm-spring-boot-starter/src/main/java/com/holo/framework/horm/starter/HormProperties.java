package com.holo.framework.horm.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HORM 框架的配置属性。
 *
 * <p>通过 {@code application.yml} 或 {@code application.properties} 配置：
 * <pre>{@code
 * holo:
 *   horm:
 *     migration:
 *       enabled: true
 *       auto-on-startup: false
 * }</pre>
 *
 * @author Holo Framework Team
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "holo.horm")
public class HormProperties {

    /**
     * 迁移相关配置。
     */
    private Migration migration = new Migration();

    public Migration getMigration() {
        return migration;
    }

    public void setMigration(Migration migration) {
        this.migration = migration;
    }

    /**
     * 迁移配置。
     */
    public static class Migration {

        /**
         * 是否启用迁移功能。默认为 {@code true}。
         */
        private boolean enabled = true;

        /**
         * 是否在应用启动时自动执行迁移。默认为 {@code false}。
         *
         * <p>当设置为 {@code true} 时，应用启动时会自动执行所有待处理的迁移脚本。
         */
        private boolean autoOnStartup = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAutoOnStartup() {
            return autoOnStartup;
        }

        public void setAutoOnStartup(boolean autoOnStartup) {
            this.autoOnStartup = autoOnStartup;
        }
    }
}
