package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
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
     * 并注册到 {@link DataSourceRegistry}。第一个数据源会被注册为默认数据源。
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
     * <p>使用多数据源注册表创建 {@link HormContext} 并安装到运行时。
     *
     * @param registry 数据源注册表
     * @return 配置好的 HormContext
     */
    @Bean
    @ConditionalOnMissingBean
    public HormContext hormContext(DataSourceRegistry registry) {
        HormContext context = new HormContext(registry);
        Horm.install(context);
        return context;
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
            // driverClassName 由 DataSourceBuilder 内部通过 JDBC URL 自动处理

            return builder.build();
        }
    }
}
