package com.holo.framework.horm.core;

/**
 * Process-wide entrypoint for the HORM runtime.
 *
 * <p>Callers install a {@link HormContext} once at startup (e.g. when the
 * application boots) and subsequently obtain repositories through
 * {@link #repository(Class)} for entity-specific CRUD operations. The
 * {@link Model} base class delegates to {@link Horm} internally.
 *
 * <p>M1-7 wires {@link #repository(Class)} to {@link JdbcRepository}, which
 * translates Active Record calls into parameterized JDBC statements driven
 * by the {@link com.holo.framework.horm.meta.EntityMeta} registered for
 * each entity type.
 */
public final class Horm {

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
     * {@link DataSourceProvider}. Convenience for
     * {@code HormContext.install(new HormContext(provider))}.
     */
    public static void install(DataSourceProvider provider) {
        HormContext.install(new HormContext(provider));
    }

    /** Returns the installed context. */
    public static HormContext context() {
        return HormContext.current();
    }

    /**
     * Returns the {@link Repository} for the given entity type.
     *
     * <p>Each call constructs a fresh {@link JdbcRepository} bound to the
     * currently installed {@link HormContext}. The {@code T extends Model<T>}
     * bound mirrors the {@link JdbcRepository} constructor so the Active
     * Record surface in {@link Model} can route through here without
     * unchecked casts at the call site.
     *
     * @throws IllegalStateException if no {@link HormContext} is installed
     * @throws com.holo.framework.horm.meta.EntityMeta lookup failures if
     *         {@code entityType} is not registered
     */
    public static <T extends Model<T>> Repository<T> repository(Class<T> entityType) {
        return new JdbcRepository<>(entityType, HormContext.current());
    }
}
