package com.holo.framework.horm.core;

import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.core.query.RawSql;
import com.holo.framework.horm.core.query.RawSqlImpl;
import com.holo.framework.horm.meta.annotation.Propagation;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide entrypoint for the HORM runtime.
 *
 * <p>Callers install a {@link HormContext} once at startup (e.g. when the
 * application boots) and subsequently obtain repositories through
 * {@link #repository(Class)} for entity-specific CRUD operations. The
 * {@link Model} base class delegates to {@link Horm} internally.
 *
 * <p>M5 introduces multi-datasource support. Use
 * {@link #install(String, DataSourceProvider)} to register named datasources;
 * entities declare their target datasource via {@code @Entity(dataSource = "name")}.
 *
 * <p>M1-7 wires {@link #repository(Class)} to {@link JdbcRepository}, which
 * translates Active Record calls into parameterized JDBC statements driven
 * by the {@link com.holo.framework.horm.meta.EntityMeta} registered for
 * each entity type.
 */
public final class Horm {

    /**
     * Per-context cache of {@link JdbcRepository} instances. Each installed
     * {@link HormContext} gets its own sub-cache so that switching contexts
     * (e.g. in multi-tenant or parallel-test scenarios) no longer clears the
     * entire repository cache. {@link HormContext} is used as the map key via
     * identity semantics (it does not override {@code equals}/{@code hashCode}).
     */
    private static final ConcurrentHashMap<HormContext, ConcurrentHashMap<Class<?>, JdbcRepository<?>>>
        REPOSITORY_CACHE_BY_CONTEXT = new ConcurrentHashMap<>();

    private Horm() {
    }

    /**
     * Installs {@code ctx} as the current {@link HormContext}. Convenience
     * equivalent to {@link HormContext#install(HormContext)}.
     */
    public static void install(HormContext ctx) {
        HormContext.install(ctx);
    }

    /**
     * Installs a {@link HormContext} backed by the given
     * {@link DataSourceProvider} as the default datasource. Convenience for
     * {@code HormContext.install(new HormContext(provider))}.
     */
    public static void install(DataSourceProvider provider) {
        HormContext.install(new HormContext(provider));
    }

    /**
     * Registers a named datasource. The datasource is added to the current
     * context's {@link DataSourceRegistry}.
     *
     * <p>A {@link HormContext} must already be installed (e.g. via
     * {@link #install(DataSourceProvider)}); otherwise an
     * {@link IllegalStateException} is thrown. To register a named datasource
     * when no context exists yet, use {@link #installOrRegister(String, DataSourceProvider)}.
     *
     * @param name     the logical datasource name
     * @param provider the datasource provider
     * @throws IllegalStateException if no context is installed, or if a
     *         datasource with the same name is already registered
     */
    public static void install(String name, DataSourceProvider provider) {
        HormContext ctx = HormContext.current();
        ctx.dataSourceRegistry().register(name, provider);
    }

    /**
     * Registers a named datasource, creating a new context if none exists.
     *
     * <p>This overload is useful during bootstrap when no context has been
     * installed yet. If a context already exists, the datasource is added
     * to its registry. If no context exists, a new one is created with the
     * given datasource registered under the specified name AND as the default.
     *
     * @param name     the logical datasource name
     * @param provider the datasource provider
     */
    public static void installOrRegister(String name, DataSourceProvider provider) {
        if (HormContext.isInstalled()) {
            HormContext.current().dataSourceRegistry().register(name, provider);
        } else {
            DataSourceRegistry registry = new DataSourceRegistry();
            registry.register(name, provider);
            if (!DataSourceRegistry.DEFAULT_NAME.equals(name)) {
                registry.registerDefault(provider);
            }
            HormContext.install(new HormContext(registry));
        }
    }

    /**
     * Returns the installed context.
     */
    public static HormContext context() {
        return HormContext.current();
    }

    /**
     * Returns the {@link Repository} for the given entity type.
     *
     * <p>Repositories are cached per {@link HormContext} <em>and</em> per entity
     * type. Switching contexts no longer invalidates the cache for other
     * contexts, so multi-tenant and parallel-test scenarios keep their cached
     * {@link JdbcRepository} instances. On the fast path — same context and
     * cache hit — this method returns the cached repository without any
     * allocation.
     *
     * <p>Each {@link HormContext} retains its own sub-cache via identity
     * semantics. Context switching is therefore safe and no longer incurs the
     * cost of re-creating repository instances for previously seen contexts.
     *
     * <p>The repository automatically routes to the datasource declared in
     * the entity's {@code @Entity(dataSource = ...)} annotation.
     *
     * <p>The {@code T extends Model<T>} bound mirrors the {@link JdbcRepository}
     * constructor so the Active Record surface in {@link Model} can route
     * through here without unchecked casts at the call site.
     *
     * @throws IllegalStateException if no {@link HormContext} is installed
     * @throws com.holo.framework.horm.meta.EntityMeta lookup failures if
     *         {@code entityType} is not registered
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T extends Model<T>> Repository<T> repository(Class<T> entityType) {
        HormContext ctx = HormContext.current();
        ConcurrentHashMap<Class<?>, JdbcRepository<?>> cache =
            REPOSITORY_CACHE_BY_CONTEXT.computeIfAbsent(ctx, k -> new ConcurrentHashMap<>());
        JdbcRepository<?> cached = cache.get(entityType);
        if (cached != null) {
            return (Repository<T>) cached;
        }
        synchronized (Horm.class) {
            cache = REPOSITORY_CACHE_BY_CONTEXT.computeIfAbsent(ctx, k -> new ConcurrentHashMap<>());
            return (Repository<T>) cache.computeIfAbsent(
                entityType, t -> new JdbcRepository<>((Class<? extends Model>) t, ctx));
        }
    }

    // ===== Programmatic transaction API =====

    /**
     * Executes the given action within a transaction on the default datasource
     * (REQUIRED propagation, DEFAULT isolation). Commits on success; rolls
     * back on exception.
     */
    public static void tx(Runnable action) {
        TransactionManager.execute(HormContext.current(), action);
    }

    /**
     * Executes the given action within a transaction on the specified datasource
     * (REQUIRED propagation, DEFAULT isolation). Commits on success; rolls
     * back on exception.
     *
     * @param dataSourceName the target datasource name
     */
    public static void tx(String dataSourceName, Runnable action) {
        TransactionManager.execute(HormContext.current(), dataSourceName, action);
    }

    /**
     * Executes the given action within a transaction on the default datasource
     * using the specified propagation behavior.
     */
    public static void tx(Propagation propagation, Runnable action) {
        TransactionManager.execute(HormContext.current(), propagation, action);
    }

    /**
     * Executes the given action within a transaction on the specified datasource
     * using the specified propagation behavior.
     *
     * @param dataSourceName the target datasource name
     */
    public static void tx(String dataSourceName, Propagation propagation, Runnable action) {
        TransactionManager.execute(HormContext.current(), dataSourceName,
            TransactionDefinition.builder().propagation(propagation).build(), action);
    }

    /**
     * Executes the given callable within a transaction on the default datasource
     * and returns its result (REQUIRED propagation, DEFAULT isolation).
     */
    public static <T> T tx(java.util.concurrent.Callable<T> action) {
        return TransactionManager.execute(HormContext.current(), action);
    }

    /**
     * Executes the given callable within a transaction on the specified datasource
     * and returns its result (REQUIRED propagation, DEFAULT isolation).
     *
     * @param dataSourceName the target datasource name
     */
    public static <T> T tx(String dataSourceName, java.util.concurrent.Callable<T> action) {
        return TransactionManager.execute(HormContext.current(), dataSourceName, action);
    }

    /**
     * Executes the given callable within a transaction on the default datasource
     * using the specified propagation behavior and returns its result.
     */
    public static <T> T tx(Propagation propagation,
                           java.util.concurrent.Callable<T> action) {
        return TransactionManager.execute(HormContext.current(), propagation, action);
    }

    /**
     * Executes the given callable within a transaction on the specified datasource
     * using the specified propagation behavior and returns its result.
     *
     * @param dataSourceName the target datasource name
     */
    public static <T> T tx(String dataSourceName, Propagation propagation,
                           java.util.concurrent.Callable<T> action) {
        return TransactionManager.execute(HormContext.current(), dataSourceName,
            TransactionDefinition.builder().propagation(propagation).build(), action);
    }

    // ===== Raw SQL API =====

    /**
     * Global raw SQL entry point on the default datasource, not bound to
     * a specific entity type. Naming aligns with {@link #install}/
     * {@link #migrate} verb style.
     *
     * <p>Does not participate in cache chain (no APT metadata for CacheKey).
     */
    public static RawSql rawSql() {
        return new RawSqlImpl(HormContext.current(), DataSourceRegistry.DEFAULT_NAME);
    }

    /**
     * Raw SQL entry point on the specified datasource.
     * Naming aligns with {@link #tx(String, Runnable)} datasource overload.
     *
     * <p>Does not participate in cache chain (no APT metadata for CacheKey).
     *
     * @param dataSourceName the target datasource name
     */
    public static RawSql rawSql(String dataSourceName) {
        return new RawSqlImpl(HormContext.current(), dataSourceName);
    }

    // ===== Migration API =====

    /**
     * Executes database migrations on all registered datasources.
     *
     * <p>This method discovers a {@link MigrationExecutor} implementation via
     * {@link ServiceLoader} and delegates migration execution to it. Each
     * datasource maintains its own migration history table.
     *
     * @return a map from datasource name to the number of migrations applied
     * @throws IllegalStateException if no {@link HormContext} is installed
     * @throws IllegalStateException if no {@link MigrationExecutor} implementation is found
     */
    public static Map<String, Integer> migrate() {
        MigrationExecutor executor = loadMigrationExecutor();
        return executor.migrateAll();
    }

    /**
     * Executes database migrations on the specified datasource.
     *
     * <p>This method discovers a {@link MigrationExecutor} implementation via
     * {@link ServiceLoader} and delegates migration execution to it.
     *
     * @param dataSourceName the target datasource name
     * @return the number of migrations applied
     * @throws IllegalStateException if no {@link HormContext} is installed
     * @throws IllegalStateException if no {@link MigrationExecutor} implementation is found
     * @throws IllegalStateException if the specified datasource is not registered
     */
    public static int migrate(String dataSourceName) {
        MigrationExecutor executor = loadMigrationExecutor();
        return executor.migrate(dataSourceName);
    }

    private static MigrationExecutor loadMigrationExecutor() {
        ServiceLoader<MigrationExecutor> loader = ServiceLoader.load(MigrationExecutor.class);
        return loader.findFirst().orElseThrow(() ->
            new IllegalStateException(
                "No MigrationExecutor implementation found. " +
                "Ensure holo-horm-migration module is on the classpath."));
    }
}
