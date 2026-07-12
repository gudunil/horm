package com.holo.framework.horm.core;

import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.core.dialect.Dialect;
import com.holo.framework.horm.core.dialect.H2Dialect;
import com.holo.framework.horm.core.dialect.MySqlDialect;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HormContextDialectTest {

    @Test
    void legacyConnectionConstructorReturnsMySqlFallback() {
        var ctx = new HormContext(mock(Connection.class));
        assertThat(ctx.dialect("default")).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void connectionWithCacheChainConstructorReturnsMySqlFallback() {
        var ctx = new HormContext(mock(Connection.class), null);
        assertThat(ctx.dialect("default")).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void providerConstructorReturnsMySqlFallback() {
        var ctx = new HormContext(mock(DataSourceProvider.class));
        assertThat(ctx.dialect("default")).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void providerWithCacheChainConstructorReturnsMySqlFallback() {
        var ctx = new HormContext(mock(DataSourceProvider.class), null);
        assertThat(ctx.dialect("default")).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void registryConstructorReturnsMySqlFallback() {
        var registry = new DataSourceRegistry();
        registry.registerDefault(mock(DataSourceProvider.class));
        var ctx = new HormContext(registry);
        assertThat(ctx.dialect("default")).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void registryWithCacheChainConstructorReturnsMySqlFallback() {
        var registry = new DataSourceRegistry();
        registry.registerDefault(mock(DataSourceProvider.class));
        var ctx = new HormContext(registry, null);
        assertThat(ctx.dialect("default")).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void dialectConstructorReturnsConfiguredDialect() {
        var registry = new DataSourceRegistry();
        registry.registerDefault(mock(DataSourceProvider.class));
        var h2Dialect = new H2Dialect("postgresql");
        var ctx = new HormContext(registry, null, Map.of("analytics", h2Dialect));

        assertThat(ctx.dialect("analytics")).isSameAs(h2Dialect);
    }

    @Test
    void dialectReturnsMySqlFallbackForUnknownDataSource() {
        var registry = new DataSourceRegistry();
        registry.registerDefault(mock(DataSourceProvider.class));
        var h2Dialect = new H2Dialect("postgresql");
        var ctx = new HormContext(registry, null, Map.of("analytics", h2Dialect));

        assertThat(ctx.dialect("unknown")).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void dialectConstructorWithNullDialectsMapReturnsMySqlFallback() {
        var registry = new DataSourceRegistry();
        registry.registerDefault(mock(DataSourceProvider.class));
        var ctx = new HormContext(registry, null, (Map<String, Dialect>) null);

        assertThat(ctx.dialect("default")).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void dialectWithEmptyMapReturnsMySqlFallback() {
        var registry = new DataSourceRegistry();
        registry.registerDefault(mock(DataSourceProvider.class));
        var ctx = new HormContext(registry, null, Map.of());

        assertThat(ctx.dialect("default")).isInstanceOf(MySqlDialect.class);
    }
}
