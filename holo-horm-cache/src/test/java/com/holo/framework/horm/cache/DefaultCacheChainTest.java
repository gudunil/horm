package com.holo.framework.horm.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DefaultCacheChain}.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>multi-tier read-through with back-fill on hit;</li>
 *   <li>negative caching via {@link NullMarker} when
 *       {@link CachePolicy#nullable()} is enabled;</li>
 *   <li>bulk {@code getAll} with batch loader, including partial hits
 *       and null-value back-fill;</li>
 *   <li>write propagation ({@code put}/{@code putAll}) and invalidation
 *       across every tier;</li>
 *   <li>chain mutation operations ({@code append}/{@code insertAfter}/
 *       {@code remove}/{@code levels});</li>
 *   <li>event dispatch ({@code HIT}/{@code MISS}/{@code INVALIDATE}/
 *       {@code ERROR}) including listener exception isolation;</li>
 *   <li>{@link CacheStats} aggregation across tiers;</li>
 *   <li>{@link CacheLoadException} wrapping of loader failures.</li>
 * </ul>
 *
 * <p>All tests use real {@link TestCache} tiers (no Mockito), per the
 * cache module's testing convention.
 */
class DefaultCacheChainTest {

    /** Convenience type reference for {@code String} values. */
    private static final TypeReference<String> STRING_TYPE = new TypeReference<>() {};

    /** Convenience type reference for {@code Integer} values. */
    private static final TypeReference<Integer> INT_TYPE = new TypeReference<>() {};

    // ── fixtures ────────────────────────────────────────────────────────

    /** Builds a non-nullable policy (default TTL, nullable=false). */
    private static CachePolicy nonNullablePolicy() {
        return CachePolicy.builder()
            .nullable(false)
            .build();
    }

    /** Builds a nullable policy with a short null TTL. */
    private static CachePolicy nullablePolicy() {
        return CachePolicy.builder()
            .nullable(true)
            .ttl(Duration.ofMinutes(5))
            .nullTtl(Duration.ofSeconds(30))
            .build();
    }

    /** A listener that records every event into a list for assertions. */
    private static final class RecordingListener implements CacheEventListener {
        final List<CacheEvent> events = new ArrayList<>();

        @Override
        public void onEvent(CacheEvent event) {
            events.add(event);
        }
    }

    /** A listener that always throws, to test failure isolation. */
    private static final class ThrowingListener implements CacheEventListener {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void onEvent(CacheEvent event) {
            calls.incrementAndGet();
            throw new IllegalStateException("listener always throws");
        }
    }

    /** Creates a fresh two-tier chain (L1 + L2) for the common case. */
    private static DefaultCacheChain newL1L2Chain(TestCache l1, TestCache l2) {
        return new DefaultCacheChain(List.of(l1, l2));
    }

    // ──────────────────────────────────────────────────────────────────
    // 1. Multi-tier back-fill: L1 miss → L2 hit → back-fill L1
    // ──────────────────────────────────────────────────────────────────

    @Test
    void l1MissL2HitBackFillsL1AndReturnsL2Value() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        l2.seed("user:1", "Alice");
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        RecordingListener listener = new RecordingListener();
        chain.addEventListener(listener);

        Optional<String> result = chain.get("user:1", STRING_TYPE);

        assertThat(result).contains("Alice");
        // L1 should have been back-filled with the L2 hit value.
        assertThat(l1.contains("user:1")).isTrue();
        assertThat(l1.raw("user:1")).isEqualTo("Alice");
        // L2 retains the original value.
        assertThat(l2.raw("user:1")).isEqualTo("Alice");

        // Events: L1 MISS then L2 HIT (in order).
        List<CacheEvent> events = listener.events;
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(CacheEventType.MISS);
        assertThat(events.get(0).cacheName()).isEqualTo("l1");
        assertThat(events.get(0).level()).isEqualTo(CacheLevel.L1);
        assertThat(events.get(0).key()).isEqualTo("user:1");
        assertThat(events.get(1).type()).isEqualTo(CacheEventType.HIT);
        assertThat(events.get(1).cacheName()).isEqualTo("l2");
        assertThat(events.get(1).level()).isEqualTo(CacheLevel.L2);
        assertThat(events.get(1).key()).isEqualTo("user:1");
        assertThat(events.get(1).value()).isEqualTo("Alice");
    }

    @Test
    void l1HitShortCircuitsDoesNotQueryL2() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        l1.seed("user:1", "Alice");
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        RecordingListener listener = new RecordingListener();
        chain.addEventListener(listener);

        Optional<String> result = chain.get("user:1", STRING_TYPE);

        assertThat(result).contains("Alice");
        // L2 was not queried.
        assertThat(l2.ops()).isEmpty();
        // Only one event: L1 HIT.
        assertThat(listener.events).hasSize(1);
        assertThat(listener.events.get(0).type()).isEqualTo(CacheEventType.HIT);
        assertThat(listener.events.get(0).cacheName()).isEqualTo("l1");
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. All-miss with non-nullable policy: loader invoked, back-fill all
    // ──────────────────────────────────────────────────────────────────

    @Test
    void allMissWithNonNullLoaderBackFillsEveryTier() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        RecordingListener listener = new RecordingListener();
        chain.addEventListener(listener);
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return "Bob";
        };

        Optional<String> result = chain.get(
            "user:2", STRING_TYPE, loader, nonNullablePolicy());

        assertThat(result).contains("Bob");
        assertThat(loaderCalls.get()).isEqualTo(1);
        // Both tiers back-filled with the loaded value.
        assertThat(l1.raw("user:2")).isEqualTo("Bob");
        assertThat(l2.raw("user:2")).isEqualTo("Bob");
        // Events: L1 MISS, L2 MISS (no HIT since loader was invoked).
        assertThat(listener.events).hasSize(2);
        assertThat(listener.events).extracting(CacheEvent::type)
            .containsExactly(CacheEventType.MISS, CacheEventType.MISS);
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. All-miss, nullable=false, loader returns null: no back-fill
    // ──────────────────────────────────────────────────────────────────

    @Test
    void allMissLoaderReturnsNullAndNullableFalseDoesNotBackFill() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return null;
        };

        Optional<String> result = chain.get(
            "user:3", STRING_TYPE, loader, nonNullablePolicy());

        assertThat(result).isEmpty();
        assertThat(loaderCalls.get()).isEqualTo(1);
        // Nothing cached in either tier.
        assertThat(l1.store()).isEmpty();
        assertThat(l2.store()).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. All-miss, nullable=true, loader returns null: NullMarker cached
    // ──────────────────────────────────────────────────────────────────

    @Test
    void allMissLoaderReturnsNullAndNullableTrueWritesNullMarkerEverywhere() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return null;
        };

        Optional<String> result = chain.get(
            "user:4", STRING_TYPE, loader, nullablePolicy());

        assertThat(result).isEmpty();
        assertThat(loaderCalls.get()).isEqualTo(1);
        // NullMarker cached in both tiers.
        assertThat(l1.contains("user:4")).isTrue();
        assertThat(NullMarker.isNullMarker(l1.raw("user:4"))).isTrue();
        assertThat(l2.contains("user:4")).isTrue();
        assertThat(NullMarker.isNullMarker(l2.raw("user:4"))).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. NullMarker hit: subsequent get does not invoke loader
    // ──────────────────────────────────────────────────────────────────

    @Test
    void nullMarkerHitShortCircuitsLoader() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        l1.seed("user:5", NullMarker.instance());
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return "ShouldNotBeCalled";
        };

        Optional<String> result = chain.get(
            "user:5", STRING_TYPE, loader, nullablePolicy());

        assertThat(result).isEmpty();
        assertThat(loaderCalls.get()).isZero();
        // L2 was not queried because L1 hit (with NullMarker).
        assertThat(l2.ops()).isEmpty();
        // L1 still holds the marker.
        assertThat(NullMarker.isNullMarker(l1.raw("user:5"))).isTrue();
    }

    @Test
    void nullMarkerHitAtDeeperTierBackFillsUpperTiersWithMarker() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        l2.seed("user:6", NullMarker.instance());
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return "ShouldNotBeCalled";
        };

        Optional<String> result = chain.get(
            "user:6", STRING_TYPE, loader, nullablePolicy());

        assertThat(result).isEmpty();
        assertThat(loaderCalls.get()).isZero();
        // L1 back-filled with the marker.
        assertThat(l1.contains("user:6")).isTrue();
        assertThat(NullMarker.isNullMarker(l1.raw("user:6"))).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. Event dispatch: HIT / MISS / INVALIDATE / ERROR
    // ──────────────────────────────────────────────────────────────────

    @Test
    void invalidateKeyPublishesInvalidateEventForEveryTier() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        l1.seed("user:7", "Alice");
        l2.seed("user:7", "Alice");
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        RecordingListener listener = new RecordingListener();
        chain.addEventListener(listener);

        chain.invalidate("user:7");

        assertThat(l1.contains("user:7")).isFalse();
        assertThat(l2.contains("user:7")).isFalse();
        // Two INVALIDATE events: one per tier.
        assertThat(listener.events).hasSize(2);
        assertThat(listener.events).allSatisfy(e ->
            assertThat(e.type()).isEqualTo(CacheEventType.INVALIDATE));
        assertThat(listener.events).extracting(CacheEvent::cacheName)
            .containsExactly("l1", "l2");
        assertThat(listener.events).extracting(CacheEvent::key)
            .containsExactly("user:7", "user:7");
    }

    @Test
    void invalidateAllSetPublishesOneInvalidateEventPerTierWithNullKey() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        l1.seed("a", "1");
        l1.seed("b", "2");
        l2.seed("a", "1");
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        RecordingListener listener = new RecordingListener();
        chain.addEventListener(listener);

        chain.invalidateAll(Set.of("a", "b"));

        assertThat(l1.store()).isEmpty();
        assertThat(l2.store()).isEmpty();
        assertThat(listener.events).hasSize(2);
        assertThat(listener.events).allSatisfy(e ->
            assertThat(e.type()).isEqualTo(CacheEventType.INVALIDATE));
        // Bulk invalidate carries null key per the CacheChain contract.
        assertThat(listener.events).extracting(CacheEvent::key)
            .containsExactly(null, null);
    }

    @Test
    void errorEventPublishedWhenLoaderThrows() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        RecordingListener listener = new RecordingListener();
        chain.addEventListener(listener);
        IllegalStateException boom = new IllegalStateException("db down");
        Supplier<String> loader = () -> { throw boom; };

        assertThatThrownBy(() -> chain.get(
            "user:8", STRING_TYPE, loader, nonNullablePolicy()))
            .isInstanceOf(CacheLoadException.class)
            .hasCause(boom);

        // Events: L1 MISS, L2 MISS, then ERROR.
        assertThat(listener.events).hasSize(3);
        assertThat(listener.events.get(0).type()).isEqualTo(CacheEventType.MISS);
        assertThat(listener.events.get(1).type()).isEqualTo(CacheEventType.MISS);
        CacheEvent errorEvent = listener.events.get(2);
        assertThat(errorEvent.type()).isEqualTo(CacheEventType.ERROR);
        assertThat(errorEvent.error()).isSameAs(boom);
        // Nothing cached.
        assertThat(l1.store()).isEmpty();
        assertThat(l2.store()).isEmpty();
    }

    @Test
    void listenerExceptionDoesNotAbortOperation() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        l2.seed("user:9", "Carol");
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        ThrowingListener throwingListener = new ThrowingListener();
        chain.addEventListener(throwingListener);
        RecordingListener secondListener = new RecordingListener();
        chain.addEventListener(secondListener);

        Optional<String> result = chain.get("user:9", STRING_TYPE);

        // Operation completed despite the throwing listener.
        assertThat(result).contains("Carol");
        // Throwing listener was invoked (at least once for L1 MISS, L2 HIT).
        assertThat(throwingListener.calls.get()).isGreaterThanOrEqualTo(1);
        // Second listener still received events despite the first one throwing.
        assertThat(secondListener.events).isNotEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // 7. Bulk getAll: partial hit, all miss, batch back-fill
    // ──────────────────────────────────────────────────────────────────

    @Test
    void getAllWithBatchLoaderPartialHitBackFillsLoadedEntries() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        l1.seed("a", "1");
        l2.seed("b", "2");
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        AtomicInteger loaderCalls = new AtomicInteger();
        Function<Set<String>, Map<String, String>> batchLoader = missing -> {
            loaderCalls.incrementAndGet();
            Map<String, String> result = new LinkedHashMap<>();
            for (String k : missing) {
                result.put(k, "loaded-" + k);
            }
            return result;
        };

        Map<String, String> result = chain.getAll(
            Set.of("a", "b", "c"), STRING_TYPE, batchLoader, nonNullablePolicy());

        assertThat(result).containsEntry("a", "1")
            .containsEntry("b", "2")
            .containsEntry("c", "loaded-c");
        // Batch loader invoked once with only the missing key.
        assertThat(loaderCalls.get()).isEqualTo(1);
        // Both tiers back-filled with the loaded entry.
        assertThat(l1.raw("c")).isEqualTo("loaded-c");
        assertThat(l2.raw("c")).isEqualTo("loaded-c");
        // "a" was an L1 hit: L2 should not have been queried for "a".
        // "b" was an L2 hit: L1 should have been back-filled with "b".
        assertThat(l1.raw("b")).isEqualTo("2");
    }

    @Test
    void getAllAllMissInvokesBatchLoaderForAllKeys() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        AtomicInteger loaderCalls = new AtomicInteger();
        Function<Set<String>, Map<String, String>> batchLoader = missing -> {
            loaderCalls.incrementAndGet();
            assertThat(missing).containsExactlyInAnyOrder("x", "y");
            Map<String, String> result = new LinkedHashMap<>();
            result.put("x", "1");
            result.put("y", "2");
            return result;
        };

        Map<String, String> result = chain.getAll(
            Set.of("x", "y"), STRING_TYPE, batchLoader, nonNullablePolicy());

        assertThat(result).containsEntry("x", "1").containsEntry("y", "2");
        assertThat(loaderCalls.get()).isEqualTo(1);
        assertThat(l1.raw("x")).isEqualTo("1");
        assertThat(l1.raw("y")).isEqualTo("2");
        assertThat(l2.raw("x")).isEqualTo("1");
        assertThat(l2.raw("y")).isEqualTo("2");
    }

    @Test
    void getAllBatchLoaderReturnsNullValueWithNullableWritesNullMarker() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        Function<Set<String>, Map<String, String>> batchLoader = missing -> {
            Map<String, String> result = new LinkedHashMap<>();
            // Key "absent" is in the missing set but not in the returned map.
            result.put("present", "yes");
            return result;
        };

        Map<String, String> result = chain.getAll(
            Set.of("present", "absent"), STRING_TYPE, batchLoader, nullablePolicy());

        // "present" returned with its loaded value; "absent" returned as null
        // (because the batch loader omitted it and nullable=true).
        assertThat(result).containsEntry("present", "yes");
        assertThat(result).containsEntry("absent", null);
        // Both keys cached: "present" as the value, "absent" as NullMarker.
        assertThat(l1.raw("present")).isEqualTo("yes");
        assertThat(NullMarker.isNullMarker(l1.raw("absent"))).isTrue();
        assertThat(l2.raw("present")).isEqualTo("yes");
        assertThat(NullMarker.isNullMarker(l2.raw("absent"))).isTrue();
    }

    @Test
    void getAllBatchLoaderReturnsNullValueWithNonNullableDoesNotBackFillAbsent() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        Function<Set<String>, Map<String, String>> batchLoader = missing -> {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("present", "yes");
            // "absent" omitted from the returned map.
            return result;
        };

        Map<String, String> result = chain.getAll(
            Set.of("present", "absent"), STRING_TYPE, batchLoader, nonNullablePolicy());

        // "present" returned with its loaded value; "absent" not in the result.
        assertThat(result).containsEntry("present", "yes");
        assertThat(result).doesNotContainKey("absent");
        // "absent" not cached anywhere.
        assertThat(l1.contains("absent")).isFalse();
        assertThat(l2.contains("absent")).isFalse();
    }

    @Test
    void getAllBatchLoaderThrowsWrapsInCacheLoadException() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        IllegalStateException boom = new IllegalStateException("batch db down");
        Function<Set<String>, Map<String, String>> batchLoader = missing -> {
            throw boom;
        };

        assertThatThrownBy(() -> chain.getAll(
            Set.of("x"), STRING_TYPE, batchLoader, nonNullablePolicy()))
            .isInstanceOf(CacheLoadException.class)
            .hasCause(boom);
    }

    @Test
    void getAllWithoutBatchLoaderReturnsHitsOnly() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        l1.seed("a", "1");
        l2.seed("b", "2");
        DefaultCacheChain chain = newL1L2Chain(l1, l2);

        Map<String, String> result = chain.getAll(
            Set.of("a", "b", "c"), STRING_TYPE);

        assertThat(result).containsEntry("a", "1").containsEntry("b", "2");
        assertThat(result).doesNotContainKey("c");
    }

    // ──────────────────────────────────────────────────────────────────
    // 8. Invalidation propagates to every tier
    // ──────────────────────────────────────────────────────────────────

    @Test
    void invalidateClearsKeyFromEveryTier() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        TestCache l3 = new TestCache("l3", CacheLevel.L3);
        l1.seed("k", "v");
        l2.seed("k", "v");
        l3.seed("k", "v");
        DefaultCacheChain chain = new DefaultCacheChain(List.of(l1, l2, l3));

        chain.invalidate("k");

        assertThat(l1.contains("k")).isFalse();
        assertThat(l2.contains("k")).isFalse();
        assertThat(l3.contains("k")).isFalse();
    }

    @Test
    void invalidateAllClearsEveryTier() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        l1.seed("a", "1");
        l1.seed("b", "2");
        l2.seed("c", "3");
        DefaultCacheChain chain = newL1L2Chain(l1, l2);

        chain.invalidateAll();

        assertThat(l1.store()).isEmpty();
        assertThat(l2.store()).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // 9. put / putAll write to every tier
    // ──────────────────────────────────────────────────────────────────

    @Test
    void putWritesToEveryTier() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = newL1L2Chain(l1, l2);

        chain.put("user:10", "Dave", nonNullablePolicy());

        assertThat(l1.raw("user:10")).isEqualTo("Dave");
        assertThat(l2.raw("user:10")).isEqualTo("Dave");
    }

    @Test
    void putAllWritesToEveryTier() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = newL1L2Chain(l1, l2);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("a", "1");
        entries.put("b", "2");

        chain.putAll(entries, nonNullablePolicy());

        assertThat(l1.raw("a")).isEqualTo("1");
        assertThat(l1.raw("b")).isEqualTo("2");
        assertThat(l2.raw("a")).isEqualTo("1");
        assertThat(l2.raw("b")).isEqualTo("2");
    }

    // ──────────────────────────────────────────────────────────────────
    // 10. Chain management: append / insertAfter / remove / levels
    // ──────────────────────────────────────────────────────────────────

    @Test
    void appendAddsTierAtEnd() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        DefaultCacheChain chain = new DefaultCacheChain(l1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);

        DefaultCacheChain returned = chain.append(l2);

        assertThat(returned).isSameAs(chain);
        assertThat(chain.levels()).hasSize(2);
        assertThat(chain.levels().get(0)).isSameAs(l1);
        assertThat(chain.levels().get(1)).isSameAs(l2);
    }

    @Test
    void insertAfterPlacesTierAfterMatchingLevel() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l3 = new TestCache("l3", CacheLevel.L3);
        DefaultCacheChain chain = new DefaultCacheChain(List.of(l1, l3));
        TestCache l2 = new TestCache("l2", CacheLevel.L2);

        chain.insertAfter(CacheLevel.L1, l2);

        assertThat(chain.levels()).containsExactly(l1, l2, l3);
    }

    @Test
    void insertAfterAbsentLevelAppendsToEnd() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        DefaultCacheChain chain = new DefaultCacheChain(l1);
        TestCache l3 = new TestCache("l3", CacheLevel.L3);

        // No L2 tier exists; insertAfter(L2, ...) should append.
        chain.insertAfter(CacheLevel.L2, l3);

        assertThat(chain.levels()).containsExactly(l1, l3);
    }

    @Test
    void removeDropsFirstTierMatchingLevel() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        TestCache l3 = new TestCache("l3", CacheLevel.L3);
        DefaultCacheChain chain = new DefaultCacheChain(List.of(l1, l2, l3));

        DefaultCacheChain returned = chain.remove(CacheLevel.L2);

        assertThat(returned).isSameAs(chain);
        assertThat(chain.levels()).containsExactly(l1, l3);
    }

    @Test
    void removeAbsentLevelIsNoOp() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        DefaultCacheChain chain = new DefaultCacheChain(l1);

        chain.remove(CacheLevel.L3);

        assertThat(chain.levels()).containsExactly(l1);
    }

    @Test
    void levelsReturnsDefensiveSnapshot() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = new DefaultCacheChain(List.of(l1, l2));

        List<Cache> snapshot = chain.levels();
        // Mutating the chain after taking the snapshot does not affect it.
        chain.append(new TestCache("l3", CacheLevel.L3));

        assertThat(snapshot).hasSize(2);
        // The snapshot itself is unmodifiable.
        assertThatThrownBy(() -> snapshot.add(l1))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // 11. stats aggregation
    // ──────────────────────────────────────────────────────────────────

    @Test
    void statsAggregatesHitsAndMissesAcrossTiers() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        l1.seed("a", "1");
        l2.seed("b", "2");
        DefaultCacheChain chain = newL1L2Chain(l1, l2);

        // L1 hit + L2 hit + L1 miss (for "c", which is absent everywhere).
        chain.get("a", STRING_TYPE);
        chain.get("b", STRING_TYPE);
        chain.get("c", STRING_TYPE);

        // Per-tier assertions to isolate aggregation issues.
        // get("a") → L1 hit.
        // get("b") → L1 miss, L2 hit, back-fill L1 with "b".
        // get("c") → L1 miss, L2 miss.
        assertThat(l1.hits()).isEqualTo(1L);   // a
        assertThat(l1.misses()).isEqualTo(2L); // b, c
        assertThat(l2.hits()).isEqualTo(1L);   // b
        assertThat(l2.misses()).isEqualTo(1L); // c

        CacheStats stats = chain.stats();
        assertThat(stats.hits()).isEqualTo(2L);   // L1(a) + L2(b)
        assertThat(stats.misses()).isEqualTo(3L); // L1(b), L1(c), L2(c)
        // L1 has "a" + back-filled "b" (2 entries); L2 has "b" (1 entry).
        assertThat(stats.estimatedSize()).isEqualTo(3L);
    }

    @Test
    void statsAverageLoadTimeAggregatesAcrossTiers() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1) {
            @Override
            public CacheStats stats() {
                return new CacheStats(1L, 0L, 0L, 0L, 2L, 0L,
                    Duration.ofMillis(10), 1L);
            }
        };
        TestCache l2 = new TestCache("l2", CacheLevel.L2) {
            @Override
            public CacheStats stats() {
                return new CacheStats(0L, 1L, 0L, 0L, 2L, 0L,
                    Duration.ofMillis(30), 1L);
            }
        };
        DefaultCacheChain chain = newL1L2Chain(l1, l2);

        CacheStats stats = chain.stats();

        // averageLoadTime = (2 * 10ms + 2 * 30ms) / 4 = 20ms
        assertThat(stats.averageLoadTime()).isEqualTo(Duration.ofMillis(20));
        assertThat(stats.loads()).isEqualTo(4L);
        assertThat(stats.hits()).isEqualTo(1L);
        assertThat(stats.misses()).isEqualTo(1L);
        assertThat(stats.estimatedSize()).isEqualTo(2L);
    }

    // ──────────────────────────────────────────────────────────────────
    // 12. CacheLoadException wrapping
    // ──────────────────────────────────────────────────────────────────

    @Test
    void loaderThrowingCheckedExceptionStyleIsWrapped() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        DefaultCacheChain chain = new DefaultCacheChain(l1);
        RuntimeException boom = new RuntimeException("simulated");
        Supplier<String> loader = () -> { throw boom; };

        assertThatThrownBy(() -> chain.get(
            "k", STRING_TYPE, loader, nonNullablePolicy()))
            .isInstanceOf(CacheLoadException.class)
            .hasMessageContaining("k")
            .hasCause(boom);
    }

    @Test
    void cacheLoadExceptionPassesThroughUnwrapped() {
        // If the loader already throws a CacheLoadException, the chain
        // should not double-wrap it.
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        DefaultCacheChain chain = new DefaultCacheChain(l1);
        CacheLoadException original = new CacheLoadException(
            "pre-wrapped", new IllegalStateException("root"));
        Supplier<String> loader = () -> { throw original; };

        assertThatThrownBy(() -> chain.get(
            "k", STRING_TYPE, loader, nonNullablePolicy()))
            .isSameAs(original);
    }

    // ──────────────────────────────────────────────────────────────────
    // 13. Identity & lifecycle
    // ──────────────────────────────────────────────────────────────────

    @Test
    void nameIsChainAndLevelIsHeadTierLevel() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = new DefaultCacheChain(List.of(l1, l2));

        assertThat(chain.name()).isEqualTo("chain");
        assertThat(chain.level()).isEqualTo(CacheLevel.L1);
    }

    @Test
    void closeClosesEveryTier() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        TestCache l2 = new TestCache("l2", CacheLevel.L2);
        TestCache l3 = new TestCache("l3", CacheLevel.L3);
        DefaultCacheChain chain = new DefaultCacheChain(List.of(l1, l2, l3));

        chain.close();

        assertThat(l1.closed()).isTrue();
        assertThat(l2.closed()).isTrue();
        assertThat(l3.closed()).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    // 14. Construction invariants
    // ──────────────────────────────────────────────────────────────────

    @Test
    void constructorRejectsEmptyVarArgs() {
        assertThatThrownBy(() -> new DefaultCacheChain())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty");
    }

    @Test
    void constructorRejectsEmptyList() {
        assertThatThrownBy(() -> new DefaultCacheChain(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty");
    }

    @Test
    void constructorRejectsNullCacheElement() {
        // Use Arrays.asList (not List.of) so the null element survives
        // to the constructor's defensive copy, where the explicit
        // null-element check fires with a descriptive message.
        assertThatThrownBy(() -> new DefaultCacheChain(java.util.Arrays.asList(
            new TestCache("l1", CacheLevel.L1), null)))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("cache element");
    }

    @Test
    void constructorRejectsNullList() {
        assertThatThrownBy(() -> new DefaultCacheChain((List<Cache>) null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("caches");
    }

    // ──────────────────────────────────────────────────────────────────
    // 15. Single-tier chain edge case
    // ──────────────────────────────────────────────────────────────────

    @Test
    void singleTierChainLoadAndHit() {
        TestCache l1 = new TestCache("l1", CacheLevel.L1);
        DefaultCacheChain chain = new DefaultCacheChain(l1);
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return "loaded";
        };

        Optional<String> first = chain.get(
            "k", STRING_TYPE, loader, nonNullablePolicy());
        Optional<String> second = chain.get("k", STRING_TYPE);

        assertThat(first).contains("loaded");
        assertThat(second).contains("loaded");
        assertThat(loaderCalls.get()).isEqualTo(1); // loaded once, then hit
    }
}
