package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.annotation.Isolation;
import com.holo.framework.horm.meta.annotation.Propagation;

/**
 * Immutable definition of a transaction's characteristics.
 *
 * <p>Created via {@link #builder()} or implicitly by
 * {@link Horm#tx(Runnable)} (which uses defaults).
 */
public final class TransactionDefinition {

    private final Propagation propagation;
    private final Isolation isolation;
    private final int timeout;
    private final boolean readOnly;

    private TransactionDefinition(Builder b) {
        this.propagation = b.propagation != null ? b.propagation : Propagation.REQUIRED;
        this.isolation = b.isolation != null ? b.isolation : Isolation.DEFAULT;
        this.timeout = b.timeout;
        this.readOnly = b.readOnly;
    }

    public Propagation propagation() { return propagation; }
    public Isolation isolation() { return isolation; }

    /** Timeout in seconds; {@code -1} means use the data source default. */
    public int timeout() { return timeout; }
    public boolean readOnly() { return readOnly; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Propagation propagation;
        private Isolation isolation;
        private int timeout = -1;
        private boolean readOnly;

        public Builder propagation(Propagation propagation) { this.propagation = propagation; return this; }
        public Builder isolation(Isolation isolation) { this.isolation = isolation; return this; }
        public Builder timeout(int timeout) { this.timeout = timeout; return this; }
        public Builder readOnly(boolean readOnly) { this.readOnly = readOnly; return this; }
        public TransactionDefinition build() { return new TransactionDefinition(this); }
    }
}
