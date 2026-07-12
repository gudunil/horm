package com.holo.framework.horm.starter;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HormDataSourceProperties}.
 */
class HormDataSourcePropertiesTest {

    @Test
    void defaultValues() {
        HormDataSourceProperties properties = new HormDataSourceProperties();
        assertThat(properties.getDatasources()).isNotNull();
        assertThat(properties.getDatasources()).isEmpty();
    }

    @Test
    void setDatasources() {
        HormDataSourceProperties properties = new HormDataSourceProperties();
        Map<String, HormDataSourceProperties.DataSourceConfig> datasources = new HashMap<>();

        HormDataSourceProperties.DataSourceConfig config = new HormDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:h2:mem:test");
        config.setUsername("sa");
        config.setPassword("");
        config.setDriverClassName("org.h2.Driver");

        datasources.put("primary", config);
        properties.setDatasources(datasources);

        assertThat(properties.getDatasources()).hasSize(1);
        assertThat(properties.getDatasources()).containsKey("primary");

        HormDataSourceProperties.DataSourceConfig retrieved = properties.getDatasources().get("primary");
        assertThat(retrieved.getUrl()).isEqualTo("jdbc:h2:mem:test");
        assertThat(retrieved.getUsername()).isEqualTo("sa");
        assertThat(retrieved.getPassword()).isEqualTo("");
        assertThat(retrieved.getDriverClassName()).isEqualTo("org.h2.Driver");
    }

    @Test
    void dataSourceConfigGettersAndSetters() {
        HormDataSourceProperties.DataSourceConfig config = new HormDataSourceProperties.DataSourceConfig();

        config.setUrl("jdbc:mysql://localhost:3306/db");
        config.setUsername("root");
        config.setPassword("secret");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");

        assertThat(config.getUrl()).isEqualTo("jdbc:mysql://localhost:3306/db");
        assertThat(config.getUsername()).isEqualTo("root");
        assertThat(config.getPassword()).isEqualTo("secret");
        assertThat(config.getDriverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @Test
    void multipleDatasources() {
        HormDataSourceProperties properties = new HormDataSourceProperties();
        Map<String, HormDataSourceProperties.DataSourceConfig> datasources = new HashMap<>();

        HormDataSourceProperties.DataSourceConfig primary = new HormDataSourceProperties.DataSourceConfig();
        primary.setUrl("jdbc:h2:mem:primary");
        datasources.put("primary", primary);

        HormDataSourceProperties.DataSourceConfig secondary = new HormDataSourceProperties.DataSourceConfig();
        secondary.setUrl("jdbc:h2:mem:secondary");
        datasources.put("secondary", secondary);

        properties.setDatasources(datasources);

        assertThat(properties.getDatasources()).hasSize(2);
        assertThat(properties.getDatasources()).containsKeys("primary", "secondary");
    }

    @Test
    void dataSourceConfigDialectGetterAndSetter() {
        HormDataSourceProperties.DataSourceConfig config = new HormDataSourceProperties.DataSourceConfig();
        assertThat(config.getDialect()).isNull();

        config.setDialect("postgresql");
        assertThat(config.getDialect()).isEqualTo("postgresql");
    }

    @Test
    void dataSourceConfigWithDialectOverride() {
        HormDataSourceProperties properties = new HormDataSourceProperties();
        Map<String, HormDataSourceProperties.DataSourceConfig> datasources = new HashMap<>();

        HormDataSourceProperties.DataSourceConfig config = new HormDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:h2:mem:test");
        config.setDialect("postgresql");

        datasources.put("primary", config);
        properties.setDatasources(datasources);

        HormDataSourceProperties.DataSourceConfig retrieved = properties.getDatasources().get("primary");
        assertThat(retrieved.getDialect()).isEqualTo("postgresql");
    }
}
