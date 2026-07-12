package com.holo.framework.horm.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多数据源配置属性。
 *
 * <p>支持从 {@code spring.datasource.<name>.*} 读取多个数据源配置：
 * <pre>{@code
 * spring:
 *   datasource:
 *     primary:
 *       url: jdbc:mysql://localhost:3306/db1
 *       username: root
 *       password: password
 *       driver-class-name: com.mysql.cj.jdbc.Driver
 *     secondary:
 *       url: jdbc:mysql://localhost:3306/db2
 *       username: root
 *       password: password
 *       driver-class-name: com.mysql.cj.jdbc.Driver
 * }</pre>
 *
 * @author Holo Framework Team
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "spring.datasource")
public class HormDataSourceProperties {

    /**
     * 数据源配置映射，key 为数据源名称，value 为数据源属性。
     *
     * <p>使用 {@link LinkedHashMap} 保持配置声明顺序，确保第一个配置的数据源
     * 被注册为默认数据源时行为可预测。
     */
    private Map<String, DataSourceConfig> datasources = new LinkedHashMap<>();

    public Map<String, DataSourceConfig> getDatasources() {
        return datasources;
    }

    public void setDatasources(Map<String, DataSourceConfig> datasources) {
        this.datasources = datasources;
    }

    /**
     * 单个数据源的配置。
     */
    public static class DataSourceConfig {

        /**
         * JDBC URL。
         */
        private String url;

        /**
         * 数据库用户名。
         */
        private String username;

        /**
         * 数据库密码。
         */
        private String password;

        /**
         * JDBC 驱动类名。
         */
        private String driverClassName;

        /**
         * 方言覆盖。可选值："mysql"、"postgresql" 等。
         *
         * <p>当设置此值时，将覆盖从 JDBC URL 自动检测到的方言。
         * 当未设置时，系统将从 JDBC URL 自动检测方言。
         */
        private String dialect;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public String getDialect() {
            return dialect;
        }

        public void setDialect(String dialect) {
            this.dialect = dialect;
        }
    }
}
