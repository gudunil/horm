package com.holo.framework.horm.cache.key;

import java.util.Objects;

/**
 * Immutable cache key rendered as
 * {@code {entityType}:{partition}:{keyType}:{keyValue}:{version}}.
 *
 * <p>Instances are created exclusively through {@link CacheKeyBuilder} (the
 * only constructor is package-private), which keeps the invariant that every
 * field is non-null and the canonical string form is computed once at
 * construction time. Cache lookups, comparisons and storage in
 * {@code Map}/{@code Set} all delegate to {@link #toString()} so a single
 * cached string powers every code path.
 *
 * <p>The five-segment shape encodes the dimensions a multi-tenant HORM cache
 * needs to disambiguate entries:
 * <ul>
 *   <li>{@code entityType} — entity class simple name ({@code User}, {@code Order})</li>
 *   <li>{@code partition} — tenant / shard identifier ({@code default} for single-tenant)</li>
 *   <li>{@code keyType} — {@code id}, {@code query}, {@code custom} or a sensitive marker</li>
 *   <li>{@code keyValue} — the lookup payload (id value, query hash, custom key, sensitive hash)</li>
 *   <li>{@code version} — schema/structure version ({@code v1} for M6) for cheap invalidation</li>
 * </ul>
 *
 * <p>{@code null} segments are rejected at construction; callers that need a
 * "wildcard" segment must pass an explicit placeholder string.
 */
public final class CacheKey {

    private final String entityType;
    private final String partition;
    private final String keyType;
    private final String keyValue;
    private final String version;
    /** Cached string representation for fast equals/hashCode. */
    private final String stringForm;

    /**
     * Package-private constructor. Only {@link CacheKeyBuilder} is expected
     * to call this; the builder enforces the non-null/non-blank invariants
     * before delegating here.
     */
    CacheKey(String entityType, String partition, String keyType, String keyValue, String version) {
        this.entityType = entityType;
        this.partition = partition;
        this.keyType = keyType;
        this.keyValue = keyValue;
        this.version = version;
        // Compute once at construction time; equals/hashCode are called
        // frequently in cache lookups and we want to avoid repeated
        // string concatenation.
        this.stringForm = entityType + ":" + partition + ":" + keyType + ":" + keyValue + ":" + version;
    }

    /**
     * Convenience factory mirroring the constructor signature. Useful for
     * tests or callers that already have all five segments in hand and do
     * not need builder defaults.
     *
     * @throws IllegalArgumentException if any argument is {@code null} or blank
     */
    public static CacheKey of(String entityType,
                              String partition,
                              String keyType,
                              String keyValue,
                              String version) {
        CacheKeyBuilder.requireNonBlank(entityType, "entityType");
        CacheKeyBuilder.requireNonBlank(partition, "partition");
        CacheKeyBuilder.requireNonBlank(keyType, "keyType");
        CacheKeyBuilder.requireNonBlank(keyValue, "keyValue");
        CacheKeyBuilder.requireNonBlank(version, "version");
        return new CacheKey(entityType, partition, keyType, keyValue, version);
    }

    /** Entity class simple name, e.g. {@code User}. */
    public String entityType() {
        return entityType;
    }

    /** Tenant / shard identifier; {@code default} for single-tenant deployments. */
    public String partition() {
        return partition;
    }

    /** Segment kind: {@code id}, {@code query}, {@code custom} or a sensitive marker. */
    public String keyType() {
        return keyType;
    }

    /** Lookup payload: id value, query hash, custom key, or sensitive hash. */
    public String keyValue() {
        return keyValue;
    }

    /** Schema/structure version ({@code v1} for M6). */
    public String version() {
        return version;
    }

    /**
     * Canonical string form {@code {entityType}:{partition}:{keyType}:{keyValue}:{version}}.
     *
     * <p>This is the value stored in the cache backend and the basis for
     * {@link #equals}/{@link #hashCode}; computed once at construction time.
     */
    @Override
    public String toString() {
        return stringForm;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CacheKey other)) {
            return false;
        }
        return stringForm.equals(other.stringForm);
    }

    @Override
    public int hashCode() {
        return stringForm.hashCode();
    }
}
