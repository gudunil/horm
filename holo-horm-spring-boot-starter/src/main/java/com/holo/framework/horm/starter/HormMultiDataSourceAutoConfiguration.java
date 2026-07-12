package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.core.dialect.Dialect;
import com.holo.framework.horm.core.dialect.DialectDetector;
import com.holo.framework.horm.core.dialect.MySqlDialect;
import com.holo.framework.horm.core.dialect.PostgresDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多数据源自动配置。
 *
 * <p>从 {@code spring.datasource.<name>.*} 读取多个数据源配置，
 * 为每个数据源创建 {@link DataSourceProvider} 并注册到 {@link DataSourceRegistry}。
 *
 * <p>配置示例：
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
@Configuration
@ConditionalOnClass(Horm.class)
@EnableConfigurationProperties(HormDataSourceProperties.class)
public class HormMultiDataSourceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HormMultiDataSourceAutoConfiguration.class);

    /**
     * 创建多数据源注册表。
     *
     * <p>从配置中读取所有数据源，为每个数据源创建 {@link DataSourceProvider}，
     * 并注册到 {@link DataSourceRegistry}。配置中第一个数据源会被注册为默认数据源
     * （依赖 {@link HormDataSourceProperties} 中 {@link LinkedHashMap} 保序特性）。
     *
     * @param properties 多数据源配置属性
     * @return 配置好的 DataSourceRegistry
     */
    @Bean
    @ConditionalOnMissingBean
    public DataSourceRegistry dataSourceRegistry(HormDataSourceProperties properties) {
        DataSourceRegistry registry = new DataSourceRegistry();

        Map<String, HormDataSourceProperties.DataSourceConfig> datasources = properties.getDatasources();
        if (datasources.isEmpty()) {
            log.warn("No datasources configured under 'spring.datasource.<name>.*'");
            return registry;
        }

        boolean first = true;
        for (Map.Entry<String, HormDataSourceProperties.DataSourceConfig> entry : datasources.entrySet()) {
            String name = entry.getKey();
            HormDataSourceProperties.DataSourceConfig config = entry.getValue();

            DataSourceProvider provider = createDataSourceProvider(config);
            registry.register(name, provider);

            if (first) {
                registry.registerDefault(provider);
                log.info("Registered '{}' as default datasource", name);
                first = false;
            } else {
                log.info("Registered datasource '{}'", name);
            }
        }

        return registry;
    }

    /**
     * 创建并安装 HormContext。
     *
     * <p>使用多数据源注册表和方言映射创建 {@link HormContext} 并安装到运行时。
     * 方言通过 JDBC URL 自动检测，可通过 {@code dialect} 配置项手动覆盖。
     *
     * @param registry   数据源注册表
     * @param properties 多数据源配置属性（用于方言检测）
     * @return 配置好的 HormContext
     */
    @Bean
    @ConditionalOnMissingBean
    public HormContext hormContext(DataSourceRegistry registry, HormDataSourceProperties properties) {
        if (!registry.hasDefault()) {
            log.warn("No default datasource configured; HormContext bean will not be created. " +
                "Define at least one datasource under 'spring.datasource.<name>.*' or " +
                "provide your own HormContext bean.");
            return null;
        }

        Map<String, Dialect> dialects = detectDialects(properties);
        HormContext context = new HormContext(registry, null, dialects);
        Horm.install(context);
        return context;
    }

    /**
     * 从配置中检测每个数据源的方言。
     *
     * <p>对每个数据源配置，优先使用手动指定的 {@code dialect} 属性，
     * 否则从 JDBC URL 自动检测方言。
     *
     * @param properties 多数据源配置属性
     * @return 数据源名称到方言的映射
     */
    private Map<String, Dialect> detectDialects(HormDataSourceProperties properties) {
        Map<String, Dialect> dialects = new LinkedHashMap<>();
        Map<String, HormDataSourceProperties.DataSourceConfig> datasources = properties.getDatasources();

        Dialect defaultDialect = null;
        for (Map.Entry<String, HormDataSourceProperties.DataSourceConfig> entry : datasources.entrySet()) {
            String name = entry.getKey();
            HormDataSourceProperties.DataSourceConfig config = entry.getValue();
            Dialect dialect = resolveDialect(config);
            dialects.put(name, dialect);
            if (defaultDialect == null) {
                defaultDialect = dialect;
            }
            log.info("Detected dialect '{}' for datasource '{}'", dialect.name(), name);
        }

        // The first configured datasource is registered as the default datasource
        // under the reserved name "default"; mirror its dialect so that
        // unqualified entity lookups resolve to the correct dialect.
        if (defaultDialect != null) {
            dialects.put(com.holo.framework.horm.core.datasource.DataSourceRegistry.DEFAULT_NAME, defaultDialect);
        }

        return dialects;
    }

    /**
     * 解析单个数据源的方言。
     *
     * <p>如果配置中指定了 {@code dialect} 属性，则使用手动指定的方言；
     * 否则从 JDBC URL 自动检测。
     *
     * @param config 数据源配置
     * @return 解析后的方言
     */
    private Dialect resolveDialect(HormDataSourceProperties.DataSourceConfig config) {
        String dialectName = config.getDialect();
        if (dialectName != null && !dialectName.isBlank()) {
            return createDialectByName(dialectName);
        }
        return DialectDetector.detect(config.getUrl());
    }

    /**
     * 根据方言名称创建方言实例。
     *
     * @param dialectName 方言名称（如 "mysql"、"postgresql"）
     * @return 方言实例
     */
    private Dialect createDialectByName(String dialectName) {
        return switch (dialectName.toLowerCase()) {
            case "mysql" -> new MySqlDialect();
            case "postgresql", "postgres" -> new PostgresDialect();
            default -> {
                log.warn("Unknown dialect '{}'; falling back to MySQL. Supported values: 'mysql', 'postgresql'. "
                    + "For H2, omit the 'dialect' property to use auto-detection.", dialectName);
                yield new MySqlDialect();
            }
        };
    }

    /**
     * 根据配置创建 DataSourceProvider。
     *
     * @param config 数据源配置
     * @return DataSourceProvider 实例
     */
    private DataSourceProvider createDataSourceProvider(HormDataSourceProperties.DataSourceConfig config) {
        return new ConfigurableDataSourceProvider(config);
    }

    /**
     * 基于配置的 DataSourceProvider 实现。
     */
    private static class ConfigurableDataSourceProvider implements DataSourceProvider {

        private final HormDataSourceProperties.DataSourceConfig config;
        private volatile DataSource dataSource;

        ConfigurableDataSourceProvider(HormDataSourceProperties.DataSourceConfig config) {
            this.config = config;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (dataSource == null) {
                synchronized (this) {
                    if (dataSource == null) {
                        dataSource = createDataSource();
                    }
                }
            }
            return dataSource.getConnection();
        }

        @Override
        public void releaseConnection(Connection connection) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // Best-effort release
                }
            }
        }

        private DataSource createDataSource() {
            // 使用 Spring Boot 的 DataSourceBuilder 创建数据源
            // DataSourceBuilder 会自动根据 URL 推断合适的连接池实现（如 HikariCP）
            org.springframework.boot.jdbc.DataSourceBuilder<?> builder =
                org.springframework.boot.jdbc.DataSourceBuilder.create();

            if (config.getUrl() != null) {
                builder.url(config.getUrl());
            }
            if (config.getUsername() != null) {
                builder.username(config.getUsername());
            }
            if (config.getPassword() != null) {
                builder.password(config.getPassword());
            }
            if (config.getDriverClassName() != null) {
                builder.driverClassName(config.getDriverClassName());
            }

            return builder.build();
        }
    }
}
