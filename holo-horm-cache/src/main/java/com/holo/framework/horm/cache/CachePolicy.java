package com.holo.framework.horm.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for a {@link Cache} entry or a cache region.
 *
 * <p>A {@code CachePolicy} bundles the dimensions that an implementation
 * consults at runtime:
 * <ul>
 *   <li>time-to-live ({@link #ttl()} and a separate {@link #nullTtl()} for
 *       cached null sentinels, when {@link #nullable()} is {@code true});</li>
 *   <li>eviction policy and bounds ({@link #evictionPolicy()},
 *       {@link #maxEntries()}, {@link #maxWeight()});</li>
 *   <li>write propagation ({@link #writeStrategy()});</li>
 *   <li>negative caching ({@link #nullable()}).</li>
 * </ul>
 *
 * <p>Instances are created exclusively through {@link #builder()} (which
 * supplies sensible defaults). All fields are non-null where indicated;
 * the class is {@code final} and every accessor returns a primitive or an
 * immutable reference, so {@code CachePolicy} is safe to share across
 * threads without defensive copies.
 *
 * <p>The defaults are tuned for the common HORM read-through scenario:
 * a 30-minute TTL with LRU eviction of 10 000 entries, write-around
 * semantics (the database remains the system of record) and negative
 * caching of absent keys for one minute to absorb cache-penetration
 * attempts.
 */
public final class CachePolicy {

    /** Default TTL applied when the builder omits {@code ttl(...)}. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    /** Default eviction policy. */
    public static final EvictionPolicy DEFAULT_EVICTION = EvictionPolicy.LRU;
    /** Default upper bound on entry count. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;
    /** Default weight bound ({@code -1} = unbounded). */
    public static final long DEFAULT_MAX_WEIGHT = -1L;
    /** Default write strategy. */
    public static final WriteStrategy DEFAULT_WRITE_STRATEGY = WriteStrategy.AROUND;
    /** Default negative-caching flag. */
    public static final boolean DEFAULT_NULLABLE = true;
    /** Default TTL for cached null sentinels. */
    public static final Duration DEFAULT_NULL_TTL = Duration.ofMinutes(1);

    private final Duration ttl;
    private final EvictionPolicy evictionPolicy;
    private final int maxEntries;
    private final long maxWeight;
    private final WriteStrategy writeStrategy;
    private final boolean nullable;
    private final Duration nullTtl;

    /**
     * Package-private constructor; the only sanctioned creation path is
     * {@link #builder()}. The {@link CachePolicyBuilder} lives in the same
     * package and validates inputs before delegating here.
     */
    CachePolicy(Duration ttl,
                EvictionPolicy evictionPolicy,
                int maxEntries,
                long maxWeight,
                WriteStrategy writeStrategy,
                boolean nullable,
                Duration nullTtl) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.evictionPolicy = Objects.requireNonNull(evictionPolicy, "evictionPolicy");
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
        this.writeStrategy = Objects.requireNonNull(writeStrategy, "writeStrategy");
        this.nullable = nullable;
        this.nullTtl = Objects.requireNonNull(nullTtl, "nullTtl");
    }

    /** Returns a fresh {@link CachePolicyBuilder} pre-seeded with defaults. */
    public static CachePolicyBuilder builder() {
        return new CachePolicyBuilder();
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
}
