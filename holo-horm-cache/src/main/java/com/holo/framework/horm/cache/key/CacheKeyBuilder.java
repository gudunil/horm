package com.holo.framework.horm.cache.key;

/**
 * Fluent builder for {@link CacheKey}. Defaults {@code partition="default"}
 * and {@code version="v1"} so the common single-tenant / M6 path needs only
 * an entity type plus one key segment:
 *
 * <pre>{@code
 * CacheKey key = new CacheKeyBuilder()
 *     .entityType(User.class)
 *     .idKey(123L)
 *     .build();
 * // -> "User:default:id:123:v1"
 *
 * CacheKey queryKey = new CacheKeyBuilder()
 *     .entityType(User.class)
 *     .queryKey(QueryHash.hash("WHERE email = ?", "ORDER BY id", 0L, 10L))
 *     .build();
 * // -> "User:default:query:abcdef0123456789:v1"
 * }</pre>
 *
 * <p>The builder is mutable and not thread-safe; each building thread should
 * construct its own instance. After {@link #build()} the builder may be
 * reused, but doing so is discouraged — the fluent chain reads more naturally
 * as a one-shot expression.
 *
 * <p>Sensitive values (PII, credentials) should be routed through
 * {@link #sensitive(Object)} so the raw value never lands in the cache key
 * string; only a non-reversible {@link SensitiveHash} digest is stored.
 */
public final class CacheKeyBuilder {

    /** Default tenant / shard segment for single-tenant deployments. */
    public static final String DEFAULT_PARTITION = "default";

    /** Default schema/structure version for the M6 milestone. */
    public static final String DEFAULT_VERSION = "v1";

    /** keyType marker for primary-key lookups. */
    public static final String KEY_TYPE_ID = "id";

    /** keyType marker for query-result caching (keyValue is a query hash). */
    public static final String KEY_TYPE_QUERY = "query";

    /** keyType marker for sensitive lookups (keyValue is a SensitiveHash digest). */
    public static final String KEY_TYPE_SENSITIVE = "sensitive";

    private String entityType;
    private String partition = DEFAULT_PARTITION;
    private String keyType;
    private String keyValue;
    private String version = DEFAULT_VERSION;

    /**
     * Set {@code entityType} from a {@link Class} using
     * {@link Class#getSimpleName()}. The simple name keeps cache keys short
     * and stable across refactors that change packages but not class names.
     *
     * @throws IllegalArgumentException if {@code type} is null or has an
     *         empty simple name (anonymous/local class)
     */
    public CacheKeyBuilder entityType(Class<?> type) {
        requireNonNull(type, "entityType");
        String simpleName = type.getSimpleName();
        if (simpleName.isEmpty()) {
            throw new IllegalArgumentException(
                "entityType class " + type.getName() + " has no simple name "
                    + "(anonymous/local classes are not supported as cache entity types)");
        }
        this.entityType = simpleName;
        return this;
    }

    /** Set {@code entityType} directly, e.g. {@code "User"}. */
    public CacheKeyBuilder entityType(String type) {
        this.entityType = requireNonBlank(type, "entityType");
        return this;
    }

    /** Set the tenant / shard partition; defaults to {@code "default"}. */
    public CacheKeyBuilder partition(String partition) {
        this.partition = requireNonBlank(partition, "partition");
        return this;
    }

    /**
     * Primary-key lookup: sets {@code keyType="id"} and
     * {@code keyValue=id.toString()}.
     */
    public CacheKeyBuilder idKey(Object id) {
        requireNonNull(id, "id");
        this.keyType = KEY_TYPE_ID;
        this.keyValue = id.toString();
        return this;
    }

    /**
     * Query-result lookup: sets {@code keyType="query"} and
     * {@code keyValue=queryHash}. The hash should normally be produced by
     * {@link QueryHash#hash(String...)} or
     * {@link QueryHash#hash(String, String, long, long)}.
     */
    public CacheKeyBuilder queryKey(String queryHash) {
        this.keyType = KEY_TYPE_QUERY;
        this.keyValue = requireNonBlank(queryHash, "queryHash");
        return this;
    }

    /**
     * Custom keyType / keyValue pair, e.g.
     * {@code customKey("email", "alice@holo.dev")}. Use this when the cache
     * is keyed by a non-id unique field that is not sensitive.
     */
    public CacheKeyBuilder customKey(String keyType, Object keyValue) {
        this.keyType = requireNonBlank(keyType, "keyType");
        requireNonNull(keyValue, "keyValue");
        this.keyValue = keyValue.toString();
        return this;
    }

    /**
     * Sensitive lookup (PII / credentials): sets {@code keyType="sensitive"}
     * and {@code keyValue=SensitiveHash.hash(value)} so the raw value never
     * appears in the cache key string.
     */
    public CacheKeyBuilder sensitive(Object value) {
        requireNonNull(value, "value");
        this.keyType = KEY_TYPE_SENSITIVE;
        this.keyValue = SensitiveHash.hash(value);
        return this;
    }

    /** Override the default {@code "v1"} version. */
    public CacheKeyBuilder version(String version) {
        this.version = requireNonBlank(version, "version");
        return this;
    }

    /**
     * Build the {@link CacheKey}.
     *
     * @throws IllegalStateException if {@code entityType} was never set or
     *         no key segment ({@code idKey}/{@code queryKey}/
     *         {@code customKey}/{@code sensitive}) was supplied
     */
    public CacheKey build() {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalStateException("entityType must be set before build()");
        }
        if (keyType == null) {
            throw new IllegalStateException(
                "a key segment (idKey/queryKey/customKey/sensitive) must be set before build()");
        }
        if (keyValue == null || keyValue.isBlank()) {
            // Defensive: every key-setting method validates keyValue, but a
            // future customKey/Object.toString() returning "" could slip through.
            throw new IllegalStateException("keyValue must be non-blank before build()");
        }
        return new CacheKey(entityType, partition, keyType, keyValue, version);
    }

    // ===== Shared validation helpers (also used by CacheKey.of) =====

    static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
        return value;
    }
}
