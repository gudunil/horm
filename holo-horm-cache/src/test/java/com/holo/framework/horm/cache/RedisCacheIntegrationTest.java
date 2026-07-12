package com.holo.framework.horm.cache;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link RedisCache} using a real Redis instance.
 *
 * <p>These tests require a local Redis server running on port 6379 without
 * authentication. The tests create a unique key prefix per test run to avoid
 * interference between concurrent test executions.
 *
 * <p><b>Test coverage:</b>
 * <ul>
 *   <li>Single-key get/put operations</li>
 *   <li>Bulk getAll/putAll operations</li>
 *   <li>TTL expiration behavior</li>
 *   <li>Invalidate single and multiple keys</li>
 *   <li>Null value handling (should be ignored)</li>
 *   <li>Serialization/deserialization round-trips</li>
 *   <li>Exception handling for invalid inputs</li>
 * </ul>
 */
class RedisCacheIntegrationTest {

    private static RedissonClient redissonClient;
    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;

    private RedisCache cache;
    private String testKeyPrefix;

    @BeforeAll
    static void setUpRedis() {
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://" + REDIS_HOST + ":" + REDIS_PORT)
            .setConnectionMinimumIdleSize(1)
            .setConnectionPoolSize(2)
            .setConnectTimeout(5000)
            .setTimeout(3000);

        redissonClient = Redisson.create(config);

        // Skip test suite gracefully when Redis is unavailable (CI without Redis)
        try {
            redissonClient.getKeys().count();
        } catch (Exception e) {
            redissonClient.shutdown();
            redissonClient = null;
            Assumptions.assumeTrue(false,
                "Redis not available at " + REDIS_HOST + ":" + REDIS_PORT
                    + " — skipping integration tests: " + e.getMessage());
        }
    }

    @AfterAll
    static void tearDownRedis() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        // Generate unique prefix for each test to avoid interference
        testKeyPrefix = "test:" + UUID.randomUUID().toString() + ":";

        CachePolicy defaultPolicy = CachePolicy.builder()
            .ttl(Duration.ofMinutes(5))
            .build();

        cache = new RedisCache(
            "integration-test",
            redissonClient,
            JdkSerializer.instance(),
            defaultPolicy
        );
    }

    // ── Single-key operations ─────────────────────────────────────────────

    @Test
    void putAndGet_singleKey() {
        String key = testKeyPrefix + "single-key";
        String value = "test-value-" + UUID.randomUUID();

        cache.put(key, value, defaultPolicy());

        Optional<String> retrieved = cache.get(key, new TypeReference<String>() {});

        assertThat(retrieved).isPresent().contains(value);
    }

    @Test
    void get_missingKey_returnsEmpty() {
        String key = testKeyPrefix + "missing-key";

        Optional<String> retrieved = cache.get(key, new TypeReference<String>() {});

        assertThat(retrieved).isEmpty();
    }

    @Test
    void put_nullValue_ignored() {
        String key = testKeyPrefix + "null-value";

        cache.put(key, null, defaultPolicy());

        Optional<String> retrieved = cache.get(key, new TypeReference<String>() {});
        assertThat(retrieved).isEmpty();
    }

    @Test
    void put_overwritesExistingValue() {
        String key = testKeyPrefix + "overwrite";
        String value1 = "value1";
        String value2 = "value2";

        cache.put(key, value1, defaultPolicy());
        cache.put(key, value2, defaultPolicy());

        Optional<String> retrieved = cache.get(key, new TypeReference<String>() {});
        assertThat(retrieved).isPresent().contains(value2);
    }

    // ── Bulk operations ───────────────────────────────────────────────────

    @Test
    void putAllAndGetAll_multipleKeys() {
        Map<String, String> entries = new HashMap<>();
        entries.put(testKeyPrefix + "bulk1", "value1");
        entries.put(testKeyPrefix + "bulk2", "value2");
        entries.put(testKeyPrefix + "bulk3", "value3");

        cache.putAll(entries, defaultPolicy());

        Set<String> keys = entries.keySet();
        Map<String, String> retrieved = cache.getAll(keys, new TypeReference<String>() {});

        assertThat(retrieved).containsAllEntriesOf(entries);
    }

    @Test
    void getAll_partialHits() {
        String key1 = testKeyPrefix + "partial1";
        String key2 = testKeyPrefix + "partial2";
        String key3 = testKeyPrefix + "partial3";

        cache.put(key1, "value1", defaultPolicy());
        cache.put(key3, "value3", defaultPolicy());
        // key2 is not put

        Set<String> keys = Set.of(key1, key2, key3);
        Map<String, String> retrieved = cache.getAll(keys, new TypeReference<String>() {});

        assertThat(retrieved).hasSize(2).containsKeys(key1, key3);
    }

    @Test
    void putAll_withNullValues_nullsIgnored() {
        Map<String, String> entries = new HashMap<>();
        entries.put(testKeyPrefix + "null1", "value1");
        entries.put(testKeyPrefix + "null2", null);
        entries.put(testKeyPrefix + "null3", "value3");

        cache.putAll(entries, defaultPolicy());

        Set<String> keys = entries.keySet();
        Map<String, String> retrieved = cache.getAll(keys, new TypeReference<String>() {});

        assertThat(retrieved).hasSize(2).containsKeys(testKeyPrefix + "null1", testKeyPrefix + "null3");
    }

    // ── TTL expiration ────────────────────────────────────────────────────

    @Test
    void put_withTtl_expiresAfterDuration() throws InterruptedException {
        String key = testKeyPrefix + "ttl-key";
        String value = "ttl-value";

        CachePolicy shortTtlPolicy = CachePolicy.builder()
            .ttl(Duration.ofSeconds(1))
            .nullTtl(Duration.ofSeconds(1))
            .nullable(false)
            .build();

        cache.put(key, value, shortTtlPolicy);

        // Should be present immediately
        Optional<String> immediate = cache.get(key, new TypeReference<String>() {});
        assertThat(immediate).isPresent().contains(value);

        // Wait for expiration
        Thread.sleep(1500);

        // Should be expired
        Optional<String> expired = cache.get(key, new TypeReference<String>() {});
        assertThat(expired).isEmpty();
    }

    // ── Invalidation ──────────────────────────────────────────────────────

    @Test
    void invalidate_singleKey() {
        String key = testKeyPrefix + "invalidate-single";
        cache.put(key, "value", defaultPolicy());

        cache.invalidate(key);

        Optional<String> retrieved = cache.get(key, new TypeReference<String>() {});
        assertThat(retrieved).isEmpty();
    }

    @Test
    void invalidateAll_multipleKeys() {
        Map<String, String> entries = new HashMap<>();
        entries.put(testKeyPrefix + "inv1", "value1");
        entries.put(testKeyPrefix + "inv2", "value2");
        entries.put(testKeyPrefix + "inv3", "value3");

        cache.putAll(entries, defaultPolicy());

        Set<String> keys = entries.keySet();
        cache.invalidateAll(keys);

        Map<String, String> retrieved = cache.getAll(keys, new TypeReference<String>() {});
        assertThat(retrieved).isEmpty();
    }

    @Test
    void invalidateAll_noArgs_throwsUnsupportedOperationException() {
        assertThatThrownBy(() -> cache.invalidateAll())
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("M6");
    }

    // ── Serialization ─────────────────────────────────────────────────────

    @Test
    void putAndGet_complexObject() {
        String key = testKeyPrefix + "complex-object";
        TestData value = new TestData("name-" + UUID.randomUUID(), 42, true);

        cache.put(key, value, defaultPolicy());

        Optional<TestData> retrieved = cache.get(key, new TypeReference<TestData>() {});

        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().name).isEqualTo(value.name);
        assertThat(retrieved.get().age).isEqualTo(value.age);
        assertThat(retrieved.get().active).isEqualTo(value.active);
    }

    @Test
    void putAllAndGetAll_complexObjects() {
        Map<String, TestData> entries = new HashMap<>();
        entries.put(testKeyPrefix + "obj1", new TestData("Alice", 30, true));
        entries.put(testKeyPrefix + "obj2", new TestData("Bob", 25, false));
        entries.put(testKeyPrefix + "obj3", new TestData("Charlie", 35, true));

        cache.putAll(entries, defaultPolicy());

        Set<String> keys = entries.keySet();
        Map<String, TestData> retrieved = cache.getAll(keys, new TypeReference<TestData>() {});

        assertThat(retrieved).hasSize(3);
        assertThat(retrieved.get(testKeyPrefix + "obj1").name).isEqualTo("Alice");
        assertThat(retrieved.get(testKeyPrefix + "obj2").name).isEqualTo("Bob");
        assertThat(retrieved.get(testKeyPrefix + "obj3").name).isEqualTo("Charlie");
    }

    // ── Exception handling ────────────────────────────────────────────────

    @Test
    void get_nullKey_throwsNullPointerException() {
        assertThatThrownBy(() -> cache.get(null, new TypeReference<String>() {}))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("key");
    }

    @Test
    void get_nullType_throwsNullPointerException() {
        assertThatThrownBy(() -> cache.get("key", (TypeReference<String>) null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("type");
    }

    @Test
    void put_nullKey_throwsNullPointerException() {
        assertThatThrownBy(() -> cache.put(null, "value", defaultPolicy()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("key");
    }

    @Test
    void put_nullPolicy_throwsNullPointerException() {
        assertThatThrownBy(() -> cache.put("key", "value", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("policy");
    }

    @Test
    void invalidate_nullKey_throwsNullPointerException() {
        assertThatThrownBy(() -> cache.invalidate(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("key");
    }

    // ── SPI methods ───────────────────────────────────────────────────────

    @Test
    void name_returnsConfiguredName() {
        assertThat(cache.name()).isEqualTo("integration-test");
    }

    @Test
    void level_returnsL2() {
        assertThat(cache.level()).isEqualTo(CacheLevel.L2);
    }

    @Test
    void stats_returnsEmptyStats() {
        CacheStats stats = cache.stats();

        assertThat(stats.hits()).isZero();
        assertThat(stats.misses()).isZero();
        assertThat(stats.evictions()).isZero();
        assertThat(stats.estimatedSize()).isZero();
    }

    @Test
    void close_isIdempotent() {
        cache.close();
        cache.close();

        // Should not throw, and cache should still be usable
        // (close is a no-op for RedisCache)
        String key = testKeyPrefix + "after-close";
        cache.put(key, "value", defaultPolicy());

        Optional<String> retrieved = cache.get(key, new TypeReference<String>() {});
        assertThat(retrieved).isPresent().contains("value");
    }

    // ── Helper methods ────────────────────────────────────────────────────

    private CachePolicy defaultPolicy() {
        return CachePolicy.builder()
            .ttl(Duration.ofMinutes(5))
            .build();
    }

    /**
     * Simple test data class for serialization tests.
     */
    private static class TestData implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        final String name;
        final int age;
        final boolean active;

        TestData(String name, int age, boolean active) {
            this.name = name;
            this.age = age;
            this.active = active;
        }
    }
}
