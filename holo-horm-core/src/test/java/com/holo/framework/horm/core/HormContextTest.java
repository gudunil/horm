package com.holo.framework.horm.core;

import com.holo.framework.horm.cache.CacheChain;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link HormContext}.
 *
 * <p>Covers the install/current lifecycle, connection accessor, and
 * close-time {@link SQLException} wrapping. The {@link HormContext} class
 * is {@code final} and constructed directly with a mocked
 * {@link Connection}.
 */
class HormContextTest {

    @AfterEach
    void resetContext() {
        // Reset the process-wide slot so tests do not leak state.
        HormContext.install(null);
    }

    @Test
    void currentThrowsWhenNotInstalled() {
        HormContext.install(null);
        assertThatThrownBy(HormContext::current)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not installed");
    }

    @Test
    void installThenCurrentReturnsSameContext() {
        HormContext ctx = new HormContext(mock(Connection.class));
        HormContext.install(ctx);
        assertThat(HormContext.current()).isSameAs(ctx);
    }

    @Test
    void connectionReturnsProvidedConnection() {
        Connection conn = mock(Connection.class);
        HormContext ctx = new HormContext(conn);
        assertThat(ctx.connection()).isSameAs(conn);
    }

    @Test
    void closeClosesUnderlyingConnection() throws SQLException {
        Connection conn = mock(Connection.class);
        HormContext ctx = new HormContext(conn);
        ctx.close();
        verify(conn).close();
    }

    @Test
    void closeWrapsSqlExceptionAsRuntimeException() throws SQLException {
        Connection conn = mock(Connection.class);
        doThrow(new SQLException("boom")).when(conn).close();
        HormContext ctx = new HormContext(conn);
        assertThatThrownBy(ctx::close)
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to close HormContext")
            .hasCauseInstanceOf(SQLException.class);
    }

    // ----- M6 cache-chain constructors -----

    @Test
    void cacheChainReturnsNullForLegacyConnectionConstructor() {
        Connection conn = mock(Connection.class);
        HormContext ctx = new HormContext(conn);
        assertThat(ctx.cacheChain()).isNull();
    }

    @Test
    void cacheChainReturnsInjectedChainForConnectionConstructor() {
        Connection conn = mock(Connection.class);
        CacheChain chain = mock(CacheChain.class);
        HormContext ctx = new HormContext(conn, chain);
        assertThat(ctx.cacheChain()).isSameAs(chain);
    }

    @Test
    void cacheChainReturnsInjectedChainForProviderConstructor() {
        DataSourceProvider provider = mock(DataSourceProvider.class);
        CacheChain chain = mock(CacheChain.class);
        HormContext ctx = new HormContext(provider, chain);
        assertThat(ctx.cacheChain()).isSameAs(chain);
    }

    @Test
    void cacheChainReturnsInjectedChainForRegistryConstructor() {
        DataSourceProvider provider = mock(DataSourceProvider.class);
        DataSourceRegistry registry = new DataSourceRegistry();
        registry.registerDefault(provider);
        CacheChain chain = mock(CacheChain.class);
        HormContext ctx = new HormContext(registry, chain);
        assertThat(ctx.cacheChain()).isSameAs(chain);
    }

    @Test
    void legacyConstructorsKeepCacheChainDisabled() {
        DataSourceProvider provider = mock(DataSourceProvider.class);
        DataSourceRegistry registry = new DataSourceRegistry();
        registry.registerDefault(provider);

        assertThat(new HormContext(mock(Connection.class)).cacheChain()).isNull();
        assertThat(new HormContext(provider).cacheChain()).isNull();
        assertThat(new HormContext(registry).cacheChain()).isNull();
    }
}
