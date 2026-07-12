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
 * <p>Instances are created through {@link #builder()} (which supplies sensible
 * defaults) or directly via the canonical record constructor. The compact
 * constructor enforces the same null and cross-field invariants the builder
 * does, so bypassing the builder cannot yield an inconsistent policy. Every
 * component is a primitive or an immutable reference, so {@code CachePolicy}
 * is safe to share across threads without defensive copies.
 *
 * <p>The defaults are tuned for the common HORM read-through scenario:
 * a 30-minute TTL with LRU eviction of 10 000 entries, write-around
 * semantics (the database remains the system of record) and negative
 * caching of absent keys for one minute to absorb cache-penetration
 * attempts.
 */
public record CachePolicy(
    Duration ttl,
    EvictionPolicy evictionPolicy,
    int maxEntries,
    long maxWeight,
    WriteStrategy writeStrategy,
    boolean nullable,
    Duration nullTtl
) {

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

    public CachePolicy {
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(evictionPolicy, "evictionPolicy");
        Objects.requireNonNull(writeStrategy, "writeStrategy");
        Objects.requireNonNull(nullTtl, "nullTtl");
        if (nullable && nullTtl.compareTo(ttl) > 0) {
            throw new IllegalArgumentException(
                "nullTtl (" + nullTtl + ") must not exceed ttl (" + ttl
                    + ") when nullable is true");
        }
    }

    /** Returns a fresh {@link CachePolicyBuilder} pre-seeded with defaults. */
    public static CachePolicyBuilder builder() {
        return new CachePolicyBuilder();
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
