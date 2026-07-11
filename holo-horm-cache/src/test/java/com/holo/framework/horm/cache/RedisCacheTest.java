package com.holo.framework.horm.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RedisCache}.
 *
 * <p><b>Scope.</b> M6 ships {@code RedisCache} as a functional stub. These
 * tests verify the SPI-level contract — name, level, stats, close, and the
 * unsupported-operation guard on {@link RedisCache#invalidateAll()} —
 * without starting a Redis instance. Read/write round-trips are covered by
 * integration tests in a later milestone; here we only confirm that the
 * cache can be constructed when Redisson is on the classpath and that its
 * no-Redis methods behave as documented.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>{@link RedisCache#create} succeeds when Redisson is on the test
 *       classpath (the {@code Class.forName} probe finds
 *       {@code org.redisson.Redisson});</li>
 *   <li>{@link RedisCache#name()} returns the constructor-supplied value;</li>
 *   <li>{@link RedisCache#level()} returns {@link CacheLevel#L2};</li>
 *   <li>{@link RedisCache#stats()} returns {@link CacheStats#empty()};</li>
 *   <li>{@link RedisCache#close()} is a no-op and idempotent;</li>
 *   <li>{@link RedisCache#invalidateAll()} (no-arg) throws
 *       {@link UnsupportedOperationException} pointing callers at
 *       {@link RedisCache#invalidateAll(java.util.Set)}.</li>
 * </ul>
 *
 * <p><b>No Redis required.</b> The {@code RedissonClient} argument is
 * passed as {@code null} because none of the tested methods touch Redis.
 * The constructor accepts a null client deliberately so that SPI-level
 * tests can run without a Redis instance; production code must supply a
 * real client obtained from {@code Redisson.create(...)}.
 */
class RedisCacheTest {

    /** Cache name used across all test cases; stable for assertions. */
    private static final String CACHE_NAME = "test-cache";

    /**
     * Builds a {@link RedisCache} for SPI-level inspection.
     *
     * <p>The {@code RedissonClient} is {@code null} because no test in
     * this class invokes a Redis-touching method. The
     * {@code Class.forName} probe still runs and verifies that Redisson
     * is on the test classpath.
     */
    private RedisCache createCache() {
        return RedisCache.create(
            CACHE_NAME,
            null,  // client not needed for SPI-only tests
            JdkSerializer.instance(),
            CachePolicy.builder().build());
    }

    // ── construction & Class.forName probe ─────────────────────────────

    @Test
    void createReturnsNonNullWhenRedissonAvailable() {
        // Redisson is declared optional=true in pom.xml but optional
        // dependencies ARE present on the compile/test classpath of the
        // declaring module. Class.forName("org.redisson.Redisson") must
        // therefore succeed, and create() must return a non-null cache.
        assertThat(createCache()).isNotNull();
    }

    @Test
    void constructorThrowsWhenNameIsNull() {
        assertThatThrownBy(() -> new RedisCache(
                null, null, JdkSerializer.instance(), CachePolicy.builder().build()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("name");
    }

    @Test
    void constructorThrowsWhenSerializerIsNull() {
        assertThatThrownBy(() -> new RedisCache(
                CACHE_NAME, null, null, CachePolicy.builder().build()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("serializer");
    }

    @Test
    void constructorThrowsWhenDefaultPolicyIsNull() {
        assertThatThrownBy(() -> new RedisCache(
                CACHE_NAME, null, JdkSerializer.instance(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("defaultPolicy");
    }

    // ── name() and level() ─────────────────────────────────────────────

    @Test
    void nameReturnsConstructorValue() {
        assertThat(createCache().name()).isEqualTo(CACHE_NAME);
    }

    @Test
    void levelReturnsL2() {
        // RedisCache is the canonical L2 tier (distributed, shared across
        // JVM instances). The level is a hard-coded constant, not derived
        // from configuration.
        assertThat(createCache().level()).isEqualTo(CacheLevel.L2);
    }

    // ── stats() ────────────────────────────────────────────────────────

    @Test
    void statsReturnsEmptySnapshot() {
        // M6: Redisson exposes no per-bucket hit/miss counters, so the
        // stub returns the canonical zero snapshot. M7+ may wire
        // Micrometer bindings.
        assertThat(createCache().stats()).isEqualTo(CacheStats.empty());
    }

    @Test
    void statsReturnsCanonicalEmptyInstance() {
        // The same CacheStats.empty() singleton is returned on every call;
        // this lets downstream code compare by reference in hot paths.
        RedisCache cache = createCache();
        assertThat(cache.stats()).isSameAs(CacheStats.empty());
    }

    // ── close() ────────────────────────────────────────────────────────

    @Test
    void closeIsNoOpAndDoesNotThrow() {
        // The RedissonClient lifecycle is owned by the caller; close()
        // must not attempt to shut it down. With a null client, close()
        // must still be a no-op (no NPE).
        RedisCache cache = createCache();
        cache.close();
    }

    @Test
    void closeIsIdempotent() {
        // The Cache SPI contract mandates idempotent close(); calling it
        // twice must not throw or have side effects.
        RedisCache cache = createCache();
        cache.close();
        cache.close();
    }

    // ── invalidateAll() (no-arg) ───────────────────────────────────────

    @Test
    void invalidateAllWithoutKeysThrowsUnsupportedOperation() {
        // M6: full flush is intentionally unsupported because SCAN-and-DELETE
        // over the "name:*" keyspace is O(N) on Redis. The exception message
        // must steer callers towards invalidateAll(Set).
        RedisCache cache = createCache();
        assertThatThrownBy(cache::invalidateAll)
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("invalidateAll(Set)");
    }

    @Test
    void invalidateAllWithoutKeysMessageMentionsM6() {
        // The message should explain WHY the operation is unsupported so
        // that callers reading the stack trace understand the limitation
        // is by design (M6 scope), not a bug.
        RedisCache cache = createCache();
        assertThatThrownBy(cache::invalidateAll)
            .hasMessageContaining("M6");
    }
}
