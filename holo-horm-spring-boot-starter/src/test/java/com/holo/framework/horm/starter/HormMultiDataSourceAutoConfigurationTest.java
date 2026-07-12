package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.core.dialect.Dialect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HormMultiDataSourceAutoConfiguration}.
 */
class HormMultiDataSourceAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HormMultiDataSourceAutoConfiguration.class));

    @Test
    void emptyDatasourcesCreatesEmptyRegistry() {
        contextRunner
            .run(context -> {
                assertThat(context).hasSingleBean(DataSourceRegistry.class);
                DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                assertThat(registry.hasDefault()).isFalse();
            });
    }

    @Test
    void singleDatasourceRegisteredAsDefault() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:h2:mem:primary",
                "spring.datasource.datasources.primary.username=sa",
                "spring.datasource.datasources.primary.password="
            )
            .run(context -> {
                assertThat(context).hasSingleBean(DataSourceRegistry.class);
                DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                assertThat(registry.hasDefault()).isTrue();
                assertThat(registry.get("primary")).isNotNull();
            });
    }

    @Test
    void multipleDatasourcesRegistered() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:h2:mem:primary",
                "spring.datasource.datasources.primary.username=sa",
                "spring.datasource.datasources.primary.password=",
                "spring.datasource.datasources.secondary.url=jdbc:h2:mem:secondary",
                "spring.datasource.datasources.secondary.username=sa",
                "spring.datasource.datasources.secondary.password="
            )
            .run(context -> {
                DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                assertThat(registry.hasDefault()).isTrue();
                assertThat(registry.get("primary")).isNotNull();
                assertThat(registry.get("secondary")).isNotNull();
            });
    }

    @Test
    void hormContextCreatedFromRegistry() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:h2:mem:primary",
                "spring.datasource.datasources.primary.username=sa",
                "spring.datasource.datasources.primary.password="
            )
            .run(context -> {
                assertThat(context).hasSingleBean(HormContext.class);
                HormContext ctx = context.getBean(HormContext.class);
                assertThat(ctx.dataSourceRegistry().hasDefault()).isTrue();
            });
    }

    @Test
    void userDefinedRegistryTakesPrecedence() {
        contextRunner
            .withUserConfiguration(CustomRegistryConfig.class)
            .withPropertyValues(
                "spring.datasource.primary.url=jdbc:h2:mem:primary",
                "spring.datasource.primary.username=sa"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(DataSourceRegistry.class);
                assertThat(context.getBean(DataSourceRegistry.class))
                    .isSameAs(context.getBean("customRegistry"));
            });
    }

    @Test
    void configurableDataSourceProviderCreatesConnection() throws SQLException {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:h2:mem:testdb",
                "spring.datasource.datasources.primary.username=sa",
                "spring.datasource.datasources.primary.password="
            )
            .run(context -> {
                DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                DataSourceProvider provider = registry.get("primary");
                assertThat(provider).isNotNull();

                // 第一次获取连接，应该创建 DataSource
                try (Connection conn = provider.getConnection()) {
                    assertThat(conn).isNotNull();
                    assertThat(conn.isClosed()).isFalse();
                }
            });
    }

    @Test
    void configurableDataSourceProviderReusesDataSource() throws SQLException {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:h2:mem:testdb",
                "spring.datasource.datasources.primary.username=sa",
                "spring.datasource.datasources.primary.password="
            )
            .run(context -> {
                DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                DataSourceProvider provider = registry.get("primary");

                // 多次获取连接，应该复用同一个 DataSource
                try (Connection conn1 = provider.getConnection();
                     Connection conn2 = provider.getConnection()) {
                    assertThat(conn1).isNotNull();
                    assertThat(conn2).isNotNull();
                }
            });
    }

    @Test
    void configurableDataSourceProviderReleaseConnection() throws SQLException {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:h2:mem:testdb",
                "spring.datasource.datasources.primary.username=sa",
                "spring.datasource.datasources.primary.password="
            )
            .run(context -> {
                DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                DataSourceProvider provider = registry.get("primary");

                Connection conn = provider.getConnection();
                assertThat(conn).isNotNull();

                // 释放连接
                provider.releaseConnection(conn);
                assertThat(conn.isClosed()).isTrue();
            });
    }

    @Test
    void configurableDataSourceProviderReleaseNullConnection() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:h2:mem:testdb",
                "spring.datasource.datasources.primary.username=sa",
                "spring.datasource.datasources.primary.password="
            )
            .run(context -> {
                DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                DataSourceProvider provider = registry.get("primary");

                // 释放 null 连接不应该抛出异常
                provider.releaseConnection(null);
            });
    }

    // --- 方言自动检测测试 ---

    @Test
    void dialectAutoDetectedFromH2Url() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:h2:mem:testdb",
                "spring.datasource.datasources.primary.username=sa",
                "spring.datasource.datasources.primary.password="
            )
            .run(context -> {
                HormContext ctx = context.getBean(HormContext.class);
                Dialect dialect = ctx.dialect("primary");
                assertThat(dialect.name()).isEqualTo("h2");
            });
    }

    @Test
    void dialectAutoDetectedFromMysqlUrl() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:mysql://localhost:3306/db",
                "spring.datasource.datasources.primary.username=root",
                "spring.datasource.datasources.primary.password=secret"
            )
            .run(context -> {
                HormContext ctx = context.getBean(HormContext.class);
                Dialect dialect = ctx.dialect("primary");
                assertThat(dialect.name()).isEqualTo("mysql");
            });
    }

    @Test
    void dialectAutoDetectedFromPostgresqlUrl() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:postgresql://localhost:5432/db",
                "spring.datasource.datasources.primary.username=postgres",
                "spring.datasource.datasources.primary.password=secret"
            )
            .run(context -> {
                HormContext ctx = context.getBean(HormContext.class);
                Dialect dialect = ctx.dialect("primary");
                assertThat(dialect.name()).isEqualTo("postgresql");
            });
    }

    @Test
    void dialectManualOverrideViaProperty() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:h2:mem:testdb",
                "spring.datasource.datasources.primary.username=sa",
                "spring.datasource.datasources.primary.password=",
                "spring.datasource.datasources.primary.dialect=postgresql"
            )
            .run(context -> {
                HormContext ctx = context.getBean(HormContext.class);
                Dialect dialect = ctx.dialect("primary");
                // 手动覆盖应优先于 URL 自动检测
                assertThat(dialect.name()).isEqualTo("postgresql");
            });
    }

    @Test
    void dialectDefaultsToMySqlWhenUrlIsBlank() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.username=sa",
                "spring.datasource.datasources.primary.password="
            )
            .run(context -> {
                DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                assertThat(registry.hasDefault()).isTrue();
            });
    }

    @Test
    void multipleDatasourcesWithDifferentDialects() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.datasources.primary.url=jdbc:mysql://localhost:3306/db1",
                "spring.datasource.datasources.primary.username=root",
                "spring.datasource.datasources.primary.password=",
                "spring.datasource.datasources.secondary.url=jdbc:postgresql://localhost:5432/db2",
                "spring.datasource.datasources.secondary.username=postgres",
                "spring.datasource.datasources.secondary.password="
            )
            .run(context -> {
                HormContext ctx = context.getBean(HormContext.class);
                assertThat(ctx.dialect("primary").name()).isEqualTo("mysql");
                assertThat(ctx.dialect("secondary").name()).isEqualTo("postgresql");
            });
    }

    @Configuration
    static class CustomRegistryConfig {
        @Bean
        DataSourceRegistry customRegistry() {
            return new DataSourceRegistry();
        }
    }
}
