package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.SimpleDataSourceProvider;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HORM 框架的 Spring Boot 自动配置类。
 *
 * <p>当类路径中存在 {@link Horm} 类时自动激活，从 Spring 的 {@link DataSource} 构造
 * {@link DataSourceProvider}，创建 {@link HormContext} 并安装到运行时。
 *
 * <p>所有 Bean 都使用 {@link ConditionalOnMissingBean} 注解，允许用户在应用中自定义覆盖。
 *
 * @author Holo Framework Team
 * @since 1.0.0
 */
@Configuration
@ConditionalOnClass(Horm.class)
@EnableConfigurationProperties(HormProperties.class)
public class HormAutoConfiguration {

    /**
     * 创建 {@link HormContext} Bean。
     *
     * <p>从 Spring 管理的 {@link DataSource} 构造 {@link SimpleDataSourceProvider}，
     * 创建 {@link HormContext} 并通过 {@link Horm#install(HormContext)} 安装到运行时。
     *
     * @param dataSource Spring 管理的 DataSource（通常来自 spring.datasource.* 配置）
     * @return 配置好的 HormContext
     */
    @Bean
    @ConditionalOnMissingBean
    public HormContext hormContext(DataSource dataSource) {
        DataSourceProvider provider = new SpringDataSourceProvider(dataSource);
        HormContext context = new HormContext(provider);
        Horm.install(context);
        return context;
    }

    /**
     * 基于 Spring DataSource 的 {@link DataSourceProvider} 实现。
     *
     * <p>与 {@link SimpleDataSourceProvider} 不同，此实现从 Spring 管理的连接池获取连接，
     * 适合生产环境使用。
     */
    private static class SpringDataSourceProvider implements DataSourceProvider {

        private final DataSource dataSource;

        SpringDataSourceProvider(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void releaseConnection(java.sql.Connection connection) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (java.sql.SQLException e) {
                    // Best-effort release; suppress so cleanup does not mask
                    // the original failure.
                }
            }
        }
    }
}
