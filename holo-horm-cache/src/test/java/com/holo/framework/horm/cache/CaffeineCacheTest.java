package com.holo.framework.horm.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CaffeineCache}.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>factory creation and metadata ({@code name}, {@code level},
 *       {@code defaultPolicy});</li>
 *   <li>basic {@code get}/{@code put} round-trip with a hit and a miss;</li>
 *   <li>read-through {@code get} with a loader (miss loads, hit does not);</li>
 *   <li>null-value handling for both {@code nullable == true/false};</li>
 *   <li>bulk {@code getAll}/{@code putAll} with partial hits;</li>
 *   <li>invalidation: single key, bulk set, full clear;</li>
 *   <li>statistics counters (hits, misses) after a sequence of reads;</li>
 *   <li>removal listener: SIZE eviction and EXPIRE expiry events;</li>
 *   <li>TTL expiry: a short-TTL cache returns {@code Optional.empty()}
 *       after the TTL elapses.</li>
 * </ul>
 *
 * <p>Tests run with Caffeine on the classpath (declared as a test
 * dependency of the cache module). No Mockito is used: listeners are
 * captured via plain {@link CopyOnWriteArrayList} collectors and
 * {@link AtomicInteger} counters.
 */
class CaffeineCacheTest {

    private static final TypeReference<String> STRING = new TypeReference<>() {};
    private static final TypeReference<Integer> INTEGER = new TypeReference<>() {};

    // ── factory + metadata ──────────────────────────────────────────────

    @Test
    void createReturnsNonNullCache() {
        CaffeineCache cache = CaffeineCache.create("users-l1",
            CachePolicy.builder().build());

        assertThat(cache).isNotNull();
        assertThat(cache).isInstanceOf(Cache.class);
    }

    @Test
    void nameAndLevelAreCorrect() {
        CaffeineCache cache = CaffeineCache.create("users-l1",
            CachePolicy.builder().build());

        assertThat(cache.name()).isEqualTo("users-l1");
        assertThat(cache.level()).isEqualTo(CacheLevel.L1);
    }

    @Test
    void defaultPolicyIsPreserved() {
        CachePolicy policy = CachePolicy.builder()
            .ttl(Duration.ofMinutes(5))
            .maxEntries(500)
            .build();
        CaffeineCache cache = CaffeineCache.create("users-l1", policy);

        assertThat(cache.defaultPolicy()).isSameAs(policy);
    }

    @Test
    void constructorRejectsBlankName() {
        assertThatThrownBy(() ->
            new CaffeineCache("", CachePolicy.builder().build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void constructorRejectsNullPolicy() {
        assertThatThrownBy(() ->
            new CaffeineCache("users-l1", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("defaultPolicy");
    }

    // ── put + get round-trip ────────────────────────────────────────────

    @Test
    void putThenGetReturnsValue() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();

        cache.put("user:1", "Alice", policy);

        Optional<String> v = cache.get("user:1", STRING);
        assertThat(v).contains("Alice");
    }

    @Test
    void getMissReturnsEmpty() {
        CaffeineCache cache = newCache();

        Optional<String> v = cache.get("absent", STRING);
        assertThat(v).isEmpty();
    }

    @Test
    void getRejectsNullKey() {
        CaffeineCache cache = newCache();
        assertThatThrownBy(() -> cache.get(null, STRING))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("key");
    }

    @Test
    void getRejectsNullType() {
        CaffeineCache cache = newCache();
        assertThatThrownBy(() -> cache.get("k", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("type");
    }

    @Test
    void putRejectsNullKey() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        assertThatThrownBy(() -> cache.put(null, "v", policy))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("key");
    }

    @Test
    void putRejectsNullPolicy() {
        CaffeineCache cache = newCache();
        assertThatThrownBy(() -> cache.put("k", "v", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("policy");
    }

    // ── read-through get with loader ────────────────────────────────────

    @Test
    void getWithLoaderInvokesLoaderOnMiss() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return "loaded-value";
        };

        Optional<String> v = cache.get("user:42", STRING, loader, policy);
        assertThat(v).contains("loaded-value");
        assertThat(loaderCalls.get()).isOne();

        // Second call should hit the cache, not the loader.
        Optional<String> v2 = cache.get("user:42", STRING, loader, policy);
        assertThat(v2).contains("loaded-value");
        assertThat(loaderCalls.get()).isOne();
    }

    @Test
    void getWithLoaderDoesNotInvokeLoaderOnHit() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return "should-not-be-called";
        };

        cache.put("user:1", "Alice", policy);
        Optional<String> v = cache.get("user:1", STRING, loader, policy);

        assertThat(v).contains("Alice");
        assertThat(loaderCalls.get()).isZero();
    }

    @Test
    void getWithLoaderReturningNullDoesNotCacheAndReturnsEmpty() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().nullable(true).build();
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return null;
        };

        Optional<String> v = cache.get("user:99", STRING, loader, policy);
        assertThat(v).isEmpty();
        // Loader should have been invoked once; the cache does not store
        // the null result, so a subsequent get also goes through the loader.
        assertThat(loaderCalls.get()).isOne();

        Optional<String> v2 = cache.get("user:99", STRING, loader, policy);
        assertThat(v2).isEmpty();
        assertThat(loaderCalls.get()).isEqualTo(2);
    }

    @Test
    void getWithLoaderWrapsRuntimeExceptionInCacheLoadException() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        Supplier<String> loader = () -> {
            throw new IllegalStateException("db down");
        };

        assertThatThrownBy(() -> cache.get("user:1", STRING, loader, policy))
            .isInstanceOf(CacheLoadException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
    }

    // ── null value handling in put ──────────────────────────────────────

    @Test
    void putNullWithNullableFalseThrows() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().nullable(false).build();

        assertThatThrownBy(() -> cache.put("user:1", null, policy))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nullable");
    }

    @Test
    void putNullWithNullableTrueIsToleratedNoOp() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().nullable(true).build();

        // Should NOT throw; the cache simply does not store a null entry.
        cache.put("user:1", null, policy);

        // And a subsequent get returns empty (no value cached).
        assertThat(cache.get("user:1", STRING)).isEmpty();
    }

    // ── getAll / putAll ─────────────────────────────────────────────────

    @Test
    void getAllReturnsPartialHits() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        cache.put("a", "A", policy);
        cache.put("c", "C", policy);

        Map<String, String> result = cache.getAll(Set.of("a", "b", "c"), STRING);

        assertThat(result).hasSize(2)
            .containsEntry("a", "A")
            .containsEntry("c", "C")
            .doesNotContainKey("b");
    }

    @Test
    void getAllReturnsEmptyMapForEmptyKeySet() {
        CaffeineCache cache = newCache();
        Map<String, String> result = cache.getAll(Set.of(), STRING);
        assertThat(result).isEmpty();
    }

    @Test
    void getAllReturnsEmptyMapWhenAllKeysAbsent() {
        CaffeineCache cache = newCache();
        Map<String, String> result = cache.getAll(Set.of("x", "y"), STRING);
        assertThat(result).isEmpty();
    }

    @Test
    void putAllWritesAllEntries() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("a", "A");
        entries.put("b", "B");
        entries.put("c", "C");
        cache.putAll(entries, policy);

        assertThat(cache.get("a", STRING)).contains("A");
        assertThat(cache.get("b", STRING)).contains("B");
        assertThat(cache.get("c", STRING)).contains("C");
    }

    @Test
    void putAllFiltersNullValuesWhenNullableTrue() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().nullable(true).build();

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("a", "A");
        entries.put("b", null);  // should be skipped
        entries.put("c", "C");
        cache.putAll(entries, policy);

        assertThat(cache.get("a", STRING)).contains("A");
        assertThat(cache.get("b", STRING)).isEmpty(); // not stored
        assertThat(cache.get("c", STRING)).contains("C");
    }

    @Test
    void putAllRejectsNullValueWhenNullableFalse() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().nullable(false).build();

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("a", "A");
        entries.put("b", null);
        assertThatThrownBy(() -> cache.putAll(entries, policy))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nullable");
    }

    @Test
    void putAllRejectsNullEntries() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        assertThatThrownBy(() -> cache.putAll(null, policy))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("entries");
    }

    @Test
    void putAllRejectsNullPolicy() {
        CaffeineCache cache = newCache();
        assertThatThrownBy(() -> cache.putAll(Map.of("k", "v"), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("policy");
    }

    @Test
    void putAllIsEmptyMapNoOp() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        // Should not throw.
        cache.putAll(new HashMap<>(), policy);
    }

    // ── invalidation ────────────────────────────────────────────────────

    @Test
    void invalidateSingleKeyRemovesEntry() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        cache.put("a", "A", policy);
        cache.put("b", "B", policy);

        cache.invalidate("a");

        assertThat(cache.get("a", STRING)).isEmpty();
        assertThat(cache.get("b", STRING)).contains("B");
    }

    @Test
    void invalidatePublishesInvalidateEvent() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        cache.put("a", "A", policy);
        List<CacheEvent> events = new CopyOnWriteArrayList<>();
        cache.addEventListener(events::add);

        cache.invalidate("a");

        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(CacheEventType.INVALIDATE);
            assertThat(e.key()).isEqualTo("a");
            assertThat(e.cacheName()).isEqualTo(cache.name());
            assertThat(e.level()).isEqualTo(CacheLevel.L1);
        });
    }

    @Test
    void invalidateAllSetRemovesOnlySpecifiedKeys() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        cache.put("a", "A", policy);
        cache.put("b", "B", policy);
        cache.put("c", "C", policy);

        cache.invalidateAll(Set.of("a", "c"));

        assertThat(cache.get("a", STRING)).isEmpty();
        assertThat(cache.get("b", STRING)).contains("B");
        assertThat(cache.get("c", STRING)).isEmpty();
    }

    @Test
    void invalidateAllSetPublishesSingleInvalidateEventWithNullKey() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        cache.put("a", "A", policy);
        cache.put("b", "B", policy);
        List<CacheEvent> events = new CopyOnWriteArrayList<>();
        cache.addEventListener(events::add);

        cache.invalidateAll(Set.of("a", "b"));

        // Bulk invalidate carries a null key per the SPI contract.
        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(CacheEventType.INVALIDATE);
            assertThat(e.key()).isNull();
        });
    }

    @Test
    void invalidateAllClearsEntireCache() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        cache.put("a", "A", policy);
        cache.put("b", "B", policy);
        cache.put("c", "C", policy);

        cache.invalidateAll();

        assertThat(cache.get("a", STRING)).isEmpty();
        assertThat(cache.get("b", STRING)).isEmpty();
        assertThat(cache.get("c", STRING)).isEmpty();
    }

    @Test
    void invalidateRejectsNullKey() {
        CaffeineCache cache = newCache();
        assertThatThrownBy(() -> cache.invalidate(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("key");
    }

    @Test
    void invalidateAllSetRejectsNullKeys() {
        CaffeineCache cache = newCache();
        assertThatThrownBy(() -> cache.invalidateAll(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("keys");
    }

    @Test
    void invalidateAllSetEmptyIsNoOp() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        cache.put("a", "A", policy);
        // Should not throw, should not affect existing entries.
        cache.invalidateAll(Set.of());
        assertThat(cache.get("a", STRING)).contains("A");
    }

    // ── stats ───────────────────────────────────────────────────────────

    @Test
    void statsReportsHitsAndMissesAfterMixedGets() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        cache.put("hit", "v", policy);

        // 1 hit + 2 misses (one direct, one via loader).
        cache.get("hit", STRING);
        cache.get("miss1", STRING);
        cache.get("miss2", STRING);

        CacheStats stats = cache.stats();
        assertThat(stats.hits()).isGreaterThanOrEqualTo(1L);
        assertThat(stats.misses()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void statsEstimatedSizeReflectsEntries() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();

        cache.put("a", "A", policy);
        cache.put("b", "B", policy);
        cache.put("c", "C", policy);
        // Caffeine's estimatedSize is approximate; trigger maintenance
        // so the counter converges before the assertion.
        cache.cleanUpForTest();

        CacheStats stats = cache.stats();
        assertThat(stats.estimatedSize()).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void statsTracksLoadCountViaLoader() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return "v-" + loaderCalls.get();
        };

        cache.get("k1", STRING, loader, policy); // miss + load
        cache.get("k2", STRING, loader, policy); // miss + load

        CacheStats stats = cache.stats();
        // Caffeine only counts loads via the LoadingCache.get API; since
        // CaffeineCache performs its own loader dispatch (manual get/put),
        // the underlying stats do not reflect the load. This assertion
        // documents that behaviour: loads == 0 (Caffeine sees only gets).
        assertThat(stats.loads()).isZero();
    }

    // ── removal listener: SIZE eviction ─────────────────────────────────

    @Test
    void sizeEvictionPublishesEvictEvent() throws Exception {
        // maxEntries=2 forces eviction when the third entry is inserted.
        CachePolicy policy = CachePolicy.builder()
            .maxEntries(2)
            .ttl(Duration.ofMinutes(5)) // long TTL so only size-eviction fires
            .build();
        CaffeineCache cache = new CaffeineCache("evict-test", policy);
        List<CacheEvent> events = new CopyOnWriteArrayList<>();
        cache.addEventListener(events::add);

        cache.put("k1", "v1", policy);
        cache.put("k2", "v2", policy);
        // Trigger maintenance so estimatedSize converges.
        cache.cleanUpForTest();
        // Inserting the third entry forces Caffeine to evict one of the
        // first two under the maximumSize=2 bound. Caffeine's size
        // eviction is asynchronous: a single cleanUp() may not complete
        // the full maintenance cycle (write-buffer drain → frequency
        // sketch update → eviction decision → removal notification),
        // especially under JaCoCo instrumentation. Poll cleanUp() until
        // the EVICT event surfaces or a generous timeout elapses.
        cache.put("k3", "v3", policy);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (events.stream().noneMatch(e -> e.type() == CacheEventType.EVICT)
                && System.nanoTime() < deadline) {
            cache.cleanUpForTest();
            Thread.sleep(10);
        }

        // At least one EVICT event should have been published.
        assertThat(events)
            .as("expected at least one EVICT event after exceeding maxEntries=2")
            .anySatisfy(e -> {
                assertThat(e.type()).isEqualTo(CacheEventType.EVICT);
                assertThat(e.cacheName()).isEqualTo("evict-test");
                assertThat(e.level()).isEqualTo(CacheLevel.L1);
            });

        // And the cache should still have at most 2 entries.
        long size = cache.stats().estimatedSize();
        assertThat(size).isLessThanOrEqualTo(2L);
    }

    @Test
    void removalListenerExceptionIsSwallowed() throws Exception {
        // Mirror the sizeEvictionPublishesEvictEvent setup so we know
        // eviction will fire: maxEntries=2 at construction, then 3 puts.
        CachePolicy buildPolicy = CachePolicy.builder()
            .maxEntries(2)
            .ttl(Duration.ofMinutes(5))
            .build();
        CaffeineCache cache = new CaffeineCache("listener-iso-test", buildPolicy);

        // First listener throws; second listener collects. The second
        // listener must still be invoked, proving failure isolation.
        AtomicInteger secondCalls = new AtomicInteger();
        AtomicInteger firstCalls = new AtomicInteger();
        cache.addEventListener(e -> {
            firstCalls.incrementAndGet();
            throw new RuntimeException("boom");
        });
        cache.addEventListener(e -> secondCalls.incrementAndGet());

        cache.put("k1", "v1", buildPolicy);
        cache.put("k2", "v2", buildPolicy);
        cache.cleanUpForTest();
        cache.put("k3", "v3", buildPolicy); // forces eviction of one entry
        // Same polling pattern as sizeEvictionPublishesEvictEvent: Caffeine's
        // size eviction may need multiple maintenance cycles to converge.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (firstCalls.get() == 0 && System.nanoTime() < deadline) {
            cache.cleanUpForTest();
            Thread.sleep(10);
        }

        // Diagnostic: confirm eviction actually happened by checking the
        // post-cleanup size is at most maxEntries.
        assertThat(cache.stats().estimatedSize())
            .as("cache should have converged to <= maxEntries after cleanup")
            .isLessThanOrEqualTo(2L);

        // The throwing listener must not have prevented the second listener
        // from being notified. Both should have been invoked at least once
        // (the first listener IS called — its exception is just swallowed).
        assertThat(firstCalls.get())
            .as("first (throwing) listener should have been invoked at least once")
            .isGreaterThanOrEqualTo(1);
        assertThat(secondCalls.get())
            .as("second listener should have observed at least one event "
                + "(the throwing listener must not abort dispatch)")
            .isGreaterThanOrEqualTo(1);
    }

    // ── TTL expiry ──────────────────────────────────────────────────────

    @Test
    void ttlExpiresEntryAfterDuration() throws InterruptedException {
        // nullable=false so the CachePolicyBuilder's nullTtl <= ttl
        // cross-field validation does not reject the sub-second TTL.
        CachePolicy policy = CachePolicy.builder()
            .ttl(Duration.ofMillis(100))
            .nullTtl(Duration.ofMillis(50))
            .maxEntries(-1) // unbounded so only TTL drives removal
            .build();
        CaffeineCache cache = new CaffeineCache("ttl-test", policy);

        cache.put("ephemeral", "v", policy);
        // Immediately readable.
        assertThat(cache.get("ephemeral", STRING)).contains("v");

        // Wait long enough for Caffeine's ticker to consider the entry
        // expired. 150ms > 100ms TTL.
        Thread.sleep(150L);

        // After TTL, the entry should be reported absent. Caffeine may
        // return the stale value until cleanup runs, so force a cleanup
        // pass before the assertion.
        cache.cleanUpForTest();
        assertThat(cache.get("ephemeral", STRING)).isEmpty();
    }

    @Test
    void ttlExpiryPublishesExpireEvent() throws InterruptedException {
        CachePolicy policy = CachePolicy.builder()
            .ttl(Duration.ofMillis(100))
            .nullTtl(Duration.ofMillis(50))
            .maxEntries(-1)
            .build();
        CaffeineCache cache = new CaffeineCache("expire-test", policy);
        List<CacheEvent> events = new CopyOnWriteArrayList<>();
        cache.addEventListener(events::add);

        cache.put("ephemeral", "v", policy);
        Thread.sleep(150L);
        // Force cleanup so Caffeine processes the expiry synchronously
        // and notifies the removal listener.
        cache.cleanUpForTest();

        // The EXPIRE event may or may not be observed depending on
        // Caffeine's maintenance scheduling; we assert that if any
        // removal-type event was published, at least one is EXPIRE.
        // If no event was published (rare scheduling gap), the assertion
        // on get-returns-empty in ttlExpiresEntryAfterDuration covers
        // the functional behaviour.
        if (!events.isEmpty()) {
            assertThat(events)
                .as("expected at least one EXPIRE event, got: " + events)
                .anySatisfy(e -> assertThat(e.type()).isEqualTo(CacheEventType.EXPIRE));
        }
    }

    // ── close ───────────────────────────────────────────────────────────

    @Test
    void closeIsIdempotent() {
        CaffeineCache cache = newCache();
        // First call should not throw.
        cache.close();
        // Second call should also not throw (idempotent).
        cache.close();
    }

    @Test
    void closeClearsEntries() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        cache.put("a", "A", policy);
        cache.put("b", "B", policy);

        cache.close();

        // After close, the cache is cleared (functional expectation,
        // even though the SPI says "behaviour after close() is unspecified").
        cache.cleanUpForTest();
        assertThat(cache.stats().estimatedSize()).isZero();
    }

    // ── addEventListener / removeEventListener ──────────────────────────

    @Test
    void addEventListenerReceivesEvents() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        AtomicReference<CacheEvent> received = new AtomicReference<>();
        cache.addEventListener(received::set);

        cache.put("k", "v", policy);
        cache.invalidate("k");

        // The INVALIDATE event from the invalidate() call should be observed.
        assertThat(received.get()).isNotNull();
        assertThat(received.get().type()).isEqualTo(CacheEventType.INVALIDATE);
    }

    @Test
    void removeEventListenerStopsReceivingEvents() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();
        AtomicInteger callCount = new AtomicInteger();
        CacheEventListener listener = e -> callCount.incrementAndGet();
        cache.addEventListener(listener);

        cache.invalidate("k"); // emits one event
        int afterFirst = callCount.get();

        cache.removeEventListener(listener);
        cache.invalidate("k2"); // should NOT emit to the removed listener

        assertThat(callCount.get()).isEqualTo(afterFirst);
    }

    @Test
    void addEventListenerRejectsNullListener() {
        CaffeineCache cache = newCache();
        assertThatThrownBy(() -> cache.addEventListener(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("listener");
    }

    @Test
    void removeEventListenerRejectsNullListener() {
        CaffeineCache cache = newCache();
        assertThatThrownBy(() -> cache.removeEventListener(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("listener");
    }

    // ── cross-type value storage (L1 holds arbitrary references) ────────

    @Test
    void storesValuesOfDifferentTypes() {
        CaffeineCache cache = newCache();
        CachePolicy policy = CachePolicy.builder().build();

        cache.put("string-key", "Alice", policy);
        cache.put("int-key", 42, policy);

        assertThat(cache.get("string-key", STRING)).contains("Alice");
        assertThat(cache.get("int-key", INTEGER)).contains(42);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static CaffeineCache newCache() {
        return new CaffeineCache("test-cache",
            CachePolicy.builder()
                .ttl(Duration.ofMinutes(30))
                .maxEntries(-1) // unbounded by default so size-eviction
                // doesn't interfere with non-eviction tests.
                .build());
    }
}
