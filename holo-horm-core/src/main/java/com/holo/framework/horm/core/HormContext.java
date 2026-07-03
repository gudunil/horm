package com.holo.framework.horm.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Ambient runtime context holding the active {@link Connection} (and, in
 * later milestones, the {@code DataSource} SPI, transaction handle, and
 * cache chain).
 *
 * <p>A single context is installed process-wide via {@link #install(HormContext)}
 * and retrieved by {@link Model} / repository code through {@link #current()}.
 * This minimal M1-6 shape intentionally exposes only the connection; M2 will
 * replace it with a richer context bound to the {@code holo-horm-datasource}
 * SPI.
 *
 * <p>{@code HormContext} is {@link AutoCloseable}; closing it closes the
 * underlying connection. Callers should typically use it in a
 * try-with-resources block at the boundary (e.g. a request or transaction
 * scope).
 */
public final class HormContext implements AutoCloseable {

    private static volatile HormContext current;

    private final Connection connection;

    public HormContext(Connection connection) {
        this.connection = connection;
    }

    /** Returns the JDBC connection bound to this context. */
    public Connection connection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close HormContext", e);
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
