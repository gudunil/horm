package com.holo.framework.horm.core;

import com.holo.framework.horm.cache.CacheChain;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.meta.EntityMeta;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Ambient runtime context holding the active datasource registry and
 * transaction handles.
 *
 * <p>A single context is installed process-wide via {@link #install(HormContext)}
 * and retrieved by {@link Model} / repository code through {@link #current()}.
 *
 * <p>M5 introduces multi-datasource support. The context now holds a
 * {@link DataSourceRegistry} instead of a single {@link DataSourceProvider}.
 * Entities declare their target datasource via {@code @Entity(dataSource = "name")};
 * the runtime resolves the correct provider through
 * {@link #getDataSourceForEntity(Class)}.
 *
 * <p>M6 introduces optional cache-chain integration. When a non-null
 * {@link CacheChain} is supplied via the cache-aware constructors, the
 * repository/query layer consults it for read-through loading and
 * post-commit invalidation. When {@code null} (the default for all
 * pre-M6 constructors), caching is short-circuited and behaviour matches
 * M5 exactly.
 *
 * <p>Backward compatibility: the single-connection and single-provider
 * constructors still work; they internally register the provider as the
 * default datasource.
 *
 * <p>{@code HormContext} is {@link AutoCloseable}; closing it closes the
 * underlying connection (if created from a single-connection constructor).
 * Contexts created from a {@code DataSourceProvider} or
 * {@code DataSourceRegistry} do <em>not</em> close the provider on
 * {@code close()} — the provider lifecycle is managed externally.
 */
public final class HormContext implements AutoCloseable {

    private static volatile HormContext current;

    private final Connection connection;
    private final DataSourceRegistry registry;
    private final CacheChain cacheChain;

    /**
     * Legacy constructor that wraps a single {@link Connection} in a
     * {@link SimpleDataSourceProvider} registered as the default datasource.
     * Behaves identically to M1-M4. Caching is disabled ({@link #cacheChain()}
     * returns {@code null}).
     */
    public HormContext(Connection connection) {
        this(connection, (CacheChain) null);
    }

    /**
     * M6 constructor that wraps a single {@link Connection} together with a
     * {@link CacheChain} for read-through caching and post-commit invalidation.
     *
     * @param connection the JDBC connection (wrapped as the default datasource)
     * @param cacheChain the cache chain, or {@code null} to disable caching
     */
    public HormContext(Connection connection, CacheChain cacheChain) {
        this.connection = connection;
        this.registry = new DataSourceRegistry();
        this.registry.registerDefault(new SimpleDataSourceProvider(connection));
        this.cacheChain = cacheChain;
    }

    /**
     * Creates a context backed by a single {@link DataSourceProvider},
     * registered as the default datasource. Caching is disabled.
     */
    public HormContext(DataSourceProvider dataSourceProvider) {
        this(dataSourceProvider, (CacheChain) null);
    }

    /**
     * M6 constructor that combines a single {@link DataSourceProvider} with a
     * {@link CacheChain}.
     *
     * @param dataSourceProvider the datasource provider (registered as default)
     * @param cacheChain         the cache chain, or {@code null} to disable caching
     */
    public HormContext(DataSourceProvider dataSourceProvider, CacheChain cacheChain) {
        this.connection = null;
        this.registry = new DataSourceRegistry();
        this.registry.registerDefault(dataSourceProvider);
        this.cacheChain = cacheChain;
    }

    /**
     * Creates a context backed by a {@link DataSourceRegistry} supporting
     * multiple named datasources. Caching is disabled.
     *
     * @param registry the datasource registry (must have a default registered)
     * @throws IllegalStateException if no default datasource is registered
     */
    public HormContext(DataSourceRegistry registry) {
        this(registry, (CacheChain) null);
    }

    /**
     * M6 constructor that combines a {@link DataSourceRegistry} with a
     * {@link CacheChain}.
     *
     * @param registry    the datasource registry (must have a default registered)
     * @param cacheChain  the cache chain, or {@code null} to disable caching
     * @throws IllegalStateException if no default datasource is registered
     */
    public HormContext(DataSourceRegistry registry, CacheChain cacheChain) {
        this.connection = null;
        if (!registry.hasDefault()) {
            throw new IllegalStateException(
                "DataSourceRegistry must have a default datasource registered");
        }
        this.registry = registry;
        this.cacheChain = cacheChain;
    }

    /**
     * Returns the JDBC connection from the default datasource.
     *
     * <p>For transaction-aware connection resolution, prefer
     * {@link TransactionManager#currentConnection(HormContext)} which
     * returns the thread-bound transaction connection when active.
     *
     * <p>For multi-datasource scenarios, use
     * {@link #getDataSource(String)} or {@link #getDataSourceForEntity(Class)}
     * to obtain the correct provider, then call {@code getConnection()} on it.
     */
    public Connection connection() {
        if (connection != null) {
            return connection;
        }
        try {
            return registry.getDefault().getConnection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to obtain connection from default DataSourceProvider", e);
        }
    }

    /**
     * Returns the {@link DataSourceProvider} for the default datasource.
     *
     * @deprecated Use {@link #getDataSource(String)} or
     * {@link #getDataSourceForEntity(Class)} for multi-datasource support.
     * Kept for backward compatibility with M4 code.
     */
    @Deprecated
    public DataSourceProvider dataSourceProvider() {
        return registry.getDefault();
    }

    /**
     * Returns the {@link DataSourceRegistry} holding all registered datasources.
     */
    public DataSourceRegistry dataSourceRegistry() {
        return registry;
    }

    /**
     * Returns the {@link CacheChain} installed on this context, or {@code null}
     * when caching is disabled.
     *
     * <p>Repository and query code must consult this accessor (rather than
     * assuming a chain is present) before invoking cache operations. A
     * {@code null} return signals the M5 code path: all reads go straight to
     * the database and no post-commit invalidation is queued.
     *
     * @return the cache chain, or {@code null} if caching is disabled
     */
    public CacheChain cacheChain() {
        return cacheChain;
    }

    /**
     * Returns the {@link DataSourceProvider} registered under the given name.
     *
     * @param name the datasource name
     * @return the provider
     * @throws IllegalStateException if no datasource is registered with that name
     */
    public DataSourceProvider getDataSource(String name) {
        return registry.get(name);
    }

    /**
     * Returns the {@link DataSourceProvider} for the given entity type,
     * resolved from its {@code @Entity(dataSource = ...)} annotation.
     *
     * <p>If the entity has no explicit datasource (or it is empty), the
     * default datasource is returned.
     *
     * @param entityType the entity class
     * @return the resolved provider
     * @throws IllegalStateException if the named datasource is not registered
     */
    public DataSourceProvider getDataSourceForEntity(Class<?> entityType) {
        EntityMeta<?> meta = EntityMetaRegistry.lookup(entityType);
        String dsName = meta.dataSource();
        return registry.resolve(dsName);
    }

    /**
     * Releases a connection obtained from this context's default
     * {@link DataSourceProvider} when it is no longer needed.
     *
     * <p>For the legacy single-connection context this is a no-op because the
     * underlying connection is closed by {@link #close()}.
     */
    public void releaseConnection(Connection connection) {
        registry.getDefault().releaseConnection(connection);
    }

    /**
     * Releases a connection obtained from the named datasource.
     *
     * @param name           the datasource name
     * @param connection     the connection to release
     */
    public void releaseConnection(String name, Connection connection) {
        registry.get(name).releaseConnection(connection);
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to close HormContext", e);
            }
        }
    }

    /**
     * Installs {@code ctx} as the process-wide current context. The previous
     * context (if any) is replaced without closing — the caller is
     * responsible for lifecycle management of the prior instance.
     */
    public static void install(HormContext ctx) {
        current = ctx;
    }

    /**
     * Returns {@code true} if a context has been installed.
     */
    public static boolean isInstalled() {
        return current != null;
    }

    /**
     * Returns the installed context.
     *
     * @throws IllegalStateException if no context has been installed via
     *         {@link #install(HormContext)} (or {@link Horm#install(HormContext)})
     *         yet
     */
    public static HormContext current() {
        HormContext ctx = current;
        if (ctx == null) {
            throw new IllegalStateException(
                "HormContext not installed; call Horm.install(ctx) first");
        }
        return ctx;
    }
}
