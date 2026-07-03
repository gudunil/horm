package com.holo.framework.horm.core;

/**
 * Process-wide entrypoint for the HORM runtime.
 *
 * <p>Callers install a {@link HormContext} once at startup (e.g. when the
 * application boots) and subsequently obtain repositories through
 * {@link #repository(Class)} for entity-specific CRUD operations. The
 * {@link Model} base class delegates to {@link Horm} internally.
 *
 * <p>M1-6 ships only the context plumbing; {@link #repository(Class)} will
 * be wired to {@code JdbcRepository} in M1-7.
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

    /** Returns the installed context. */
    public static HormContext context() {
        return HormContext.current();
    }

    /**
     * Returns the {@link Repository} for the given entity type.
     *
     * <p>M1-6 stub: throws {@link UnsupportedOperationException}. M1-7 wires
     * this to {@code new JdbcRepository<>(entityType, current())}.
     */
    public static <T> Repository<T> repository(Class<T> entityType) {
        // TODO M1-7: return new JdbcRepository<>(entityType, current());
        throw new UnsupportedOperationException("Repository not implemented until M1-7");
    }
}
