package com.holo.framework.horm.meta;

import com.holo.framework.horm.meta.annotation.EvictionPolicy;
import com.holo.framework.horm.meta.annotation.WriteStrategy;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable cache policy descriptor assembled by the APT processor from the
 * {@link com.holo.framework.horm.meta.annotation.CachePolicy @CachePolicy}
 * annotation and stored in the generated {@code XxxMeta} companion class.
 *
 * <p>This class lives in the meta module so that {@link EntityMeta} can
 * expose a typed {@code cachePolicy()} accessor without depending on the
 * cache module (which would create a circular dependency: meta → cache →
 * core → meta). The ORM integration layer in the core module converts
 * this descriptor into a runtime
 * {@code com.holo.framework.horm.cache.CachePolicy} instance when invoking
 * the cache chain.
 *
 * <p>Fields mirror {@code com.holo.framework.horm.cache.CachePolicy}:
 * <ul>
 *   <li>{@link #ttl()} — time-to-live for non-null entries</li>
 *   <li>{@link #evictionPolicy()} — eviction strategy</li>
 *   <li>{@link #maxEntries()} — entry-count bound</li>
 *   <li>{@link #maxWeight()} — weight bound ({@code -1} = unbounded)</li>
 *   <li>{@link #writeStrategy()} — write propagation strategy</li>
 *   <li>{@link #nullable()} — whether null sentinels may be cached</li>
 *   <li>{@link #nullTtl()} — TTL for cached null sentinels</li>
 * </ul>
 *
 * <p>Instances are created exclusively through {@link #builder()}. The class
 * is {@code final} and every accessor returns a primitive or an immutable
 * reference, so it is safe to share across threads without defensive copies.
 */
public final class CachePolicy {

    private final Duration ttl;
    private final EvictionPolicy evictionPolicy;
    private final int maxEntries;
    private final long maxWeight;
    private final WriteStrategy writeStrategy;
    private final boolean nullable;
    private final Duration nullTtl;

    private CachePolicy(Builder b) {
        this.ttl = Objects.requireNonNull(b.ttl, "ttl");
        this.evictionPolicy = Objects.requireNonNull(b.evictionPolicy, "evictionPolicy");
        this.maxEntries = b.maxEntries;
        this.maxWeight = b.maxWeight;
        this.writeStrategy = Objects.requireNonNull(b.writeStrategy, "writeStrategy");
        this.nullable = b.nullable;
        this.nullTtl = Objects.requireNonNull(b.nullTtl, "nullTtl");
    }

    /** Returns a fresh {@link Builder} pre-seeded with defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Time-to-live for non-null entries. Never {@code null}. */
    public Duration ttl() {
        return ttl;
    }

    /** Eviction policy. Never {@code null}. */
    public EvictionPolicy evictionPolicy() {
        return evictionPolicy;
    }

    /** Maximum number of entries before eviction kicks in. */
    public int maxEntries() {
        return maxEntries;
    }

    /** Maximum total weight ({@code -1} = unbounded). */
    public long maxWeight() {
        return maxWeight;
    }

    /** Write propagation strategy. Never {@code null}. */
    public WriteStrategy writeStrategy() {
        return writeStrategy;
    }

    /** Whether {@code null} values may be cached as sentinels. */
    public boolean nullable() {
        return nullable;
    }

    /** TTL for cached null sentinels. Only consulted when {@link #nullable()} is {@code true}. */
    public Duration nullTtl() {
        return nullTtl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CachePolicy that)) return false;
        return nullable == that.nullable
            && maxEntries == that.maxEntries
            && maxWeight == that.maxWeight
            && ttl.equals(that.ttl)
            && evictionPolicy == that.evictionPolicy
            && writeStrategy == that.writeStrategy
            && nullTtl.equals(that.nullTtl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ttl, evictionPolicy, maxEntries, maxWeight,
            writeStrategy, nullable, nullTtl);
    }

    @Override
    public String toString() {
        return "CachePolicy{ttl=" + ttl
            + ", evictionPolicy=" + evictionPolicy
            + ", maxEntries=" + maxEntries
            + ", maxWeight=" + maxWeight
            + ", writeStrategy=" + writeStrategy
            + ", nullable=" + nullable
            + ", nullTtl=" + nullTtl
            + '}';
    }

    /** Builder for {@link CachePolicy}, pre-seeded with the defaults from {@code @CachePolicy}. */
    public static final class Builder {
        private Duration ttl = Duration.ofMinutes(30);
        private EvictionPolicy evictionPolicy = EvictionPolicy.LRU;
        private int maxEntries = 10_000;
        private long maxWeight = -1L;
        private WriteStrategy writeStrategy = WriteStrategy.AROUND;
        private boolean nullable = true;
        private Duration nullTtl = Duration.ofMinutes(1);

        public Builder ttl(Duration ttl) { this.ttl = ttl; return this; }
        public Builder evictionPolicy(EvictionPolicy evictionPolicy) { this.evictionPolicy = evictionPolicy; return this; }
        public Builder maxEntries(int maxEntries) { this.maxEntries = maxEntries; return this; }
        public Builder maxWeight(long maxWeight) { this.maxWeight = maxWeight; return this; }
        public Builder writeStrategy(WriteStrategy writeStrategy) { this.writeStrategy = writeStrategy; return this; }
        public Builder nullable(boolean nullable) { this.nullable = nullable; return this; }
        public Builder nullTtl(Duration nullTtl) { this.nullTtl = nullTtl; return this; }

        public CachePolicy build() { return new CachePolicy(this); }
    }
}
