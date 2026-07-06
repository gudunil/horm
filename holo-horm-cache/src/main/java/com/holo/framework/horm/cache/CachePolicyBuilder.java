package com.holo.framework.horm.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * Fluent builder for {@link CachePolicy}.
 *
 * <p>Every setter returns {@code this} for chaining; {@link #build()}
 * validates the assembled state and yields an immutable
 * {@link CachePolicy}. Sensible defaults are seeded from the
 * {@code DEFAULT_*} constants on {@link CachePolicy}, so an untouched
 * builder produces a policy suitable for the common HORM read-through
 * case:
 * <pre>{@code
 * CachePolicy policy = CachePolicy.builder()
 *     .ttl(Duration.ofMinutes(10))
 *     .evictionPolicy(EvictionPolicy.W_TINY_LFU)
 *     .build();
 * }</pre>
 *
 * <p>A single builder instance may be reused to produce multiple policies:
 * {@code build()} does not reset state, so callers should typically obtain
 * a fresh builder per policy. The builder is <em>not</em> thread-safe.
 */
public final class CachePolicyBuilder {

    private Duration ttl = CachePolicy.DEFAULT_TTL;
    private EvictionPolicy evictionPolicy = CachePolicy.DEFAULT_EVICTION;
    private int maxEntries = CachePolicy.DEFAULT_MAX_ENTRIES;
    private long maxWeight = CachePolicy.DEFAULT_MAX_WEIGHT;
    private WriteStrategy writeStrategy = CachePolicy.DEFAULT_WRITE_STRATEGY;
    private boolean nullable = CachePolicy.DEFAULT_NULLABLE;
    private Duration nullTtl = CachePolicy.DEFAULT_NULL_TTL;

    /** Creates a new builder pre-seeded with the default policy values. */
    public CachePolicyBuilder() {
    }

    /** Sets the time-to-live for non-null entries. {@code null} is rejected. */
    public CachePolicyBuilder ttl(Duration ttl) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        return this;
    }

    /** Sets the eviction policy. {@code null} is rejected. */
    public CachePolicyBuilder evictionPolicy(EvictionPolicy evictionPolicy) {
        this.evictionPolicy = Objects.requireNonNull(evictionPolicy, "evictionPolicy");
        return this;
    }

    /** Sets the maximum entry count. Must be positive; {@code -1} means unbounded. */
    public CachePolicyBuilder maxEntries(int maxEntries) {
        if (maxEntries != -1 && maxEntries <= 0) {
            throw new IllegalArgumentException(
                "maxEntries must be positive or -1 (unbounded), got " + maxEntries);
        }
        this.maxEntries = maxEntries;
        return this;
    }

    /** Sets the maximum total weight. {@code -1} means unbounded. */
    public CachePolicyBuilder maxWeight(long maxWeight) {
        if (maxWeight != -1 && maxWeight <= 0) {
            throw new IllegalArgumentException(
                "maxWeight must be positive or -1 (unbounded), got " + maxWeight);
        }
        this.maxWeight = maxWeight;
        return this;
    }

    /** Sets the write propagation strategy. {@code null} is rejected. */
    public CachePolicyBuilder writeStrategy(WriteStrategy writeStrategy) {
        this.writeStrategy = Objects.requireNonNull(writeStrategy, "writeStrategy");
        return this;
    }

    /** Enables or disables negative caching of {@code null} values. */
    public CachePolicyBuilder nullable(boolean nullable) {
        this.nullable = nullable;
        return this;
    }

    /** Sets the TTL for cached null sentinels. {@code null} is rejected. */
    public CachePolicyBuilder nullTtl(Duration nullTtl) {
        this.nullTtl = Objects.requireNonNull(nullTtl, "nullTtl");
        return this;
    }

    /**
     * Builds the {@link CachePolicy}.
     *
     * <p>Cross-field validation runs here so that an inconsistent policy
     * (e.g. negative caching enabled with a longer null TTL than the
     * regular TTL) is rejected at construction time rather than surfacing
     * as confusing runtime behaviour.
     *
     * @throws IllegalArgumentException if {@code nullable} is {@code true}
     *         and {@code nullTtl} exceeds {@code ttl}
     */
    public CachePolicy build() {
        if (nullable && nullTtl.compareTo(ttl) > 0) {
            throw new IllegalArgumentException(
                "nullTtl (" + nullTtl + ") must not exceed ttl (" + ttl
                    + ") when nullable is true");
        }
        return new CachePolicy(ttl, evictionPolicy, maxEntries, maxWeight,
            writeStrategy, nullable, nullTtl);
    }
}
