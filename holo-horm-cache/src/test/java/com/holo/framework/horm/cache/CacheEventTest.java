package com.holo.framework.horm.cache;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CacheEvent}.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>record field accessors preserve constructor arguments;</li>
 *   <li>non-null invariants on {@code type}/{@code cacheName}/{@code level}/
 *       {@code timestamp};</li>
 *   <li>the {@code error}/{@code type} association rule: error must be
 *       non-null iff type is {@link CacheEventType#ERROR};</li>
 *   <li>convenience factories {@link CacheEvent#of} and
 *       {@link CacheEvent#error} produce well-formed events.</li>
 * </ul>
 */
class CacheEventTest {

    // ── field accessors ────────────────────────────────────────────────

    @Test
    void accessorsReturnConstructorValues() {
        Instant ts = Instant.parse("2026-07-06T12:00:00Z");
        CacheEvent event = new CacheEvent(
            CacheEventType.HIT, "users-l1", CacheLevel.L1,
            "user:42", "Alice", ts, null);

        assertThat(event.type()).isEqualTo(CacheEventType.HIT);
        assertThat(event.cacheName()).isEqualTo("users-l1");
        assertThat(event.level()).isEqualTo(CacheLevel.L1);
        assertThat(event.key()).isEqualTo("user:42");
        assertThat(event.value()).isEqualTo("Alice");
        assertThat(event.timestamp()).isSameAs(ts);
        assertThat(event.error()).isNull();
    }

    @Test
    void nullKeyAndValueArePermitted() {
        CacheEvent event = new CacheEvent(
            CacheEventType.INVALIDATE, "users-l1", CacheLevel.L1,
            null, null, Instant.now(), null);

        assertThat(event.key()).isNull();
        assertThat(event.value()).isNull();
    }

    // ── non-null invariants ────────────────────────────────────────────

    @Test
    void rejectsNullType() {
        assertThatThrownBy(() -> new CacheEvent(
            null, "users-l1", CacheLevel.L1, "k", "v", Instant.now(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("type");
    }

    @Test
    void rejectsNullCacheName() {
        assertThatThrownBy(() -> new CacheEvent(
            CacheEventType.HIT, null, CacheLevel.L1, "k", "v", Instant.now(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("cacheName");
    }

    @Test
    void rejectsNullLevel() {
        assertThatThrownBy(() -> new CacheEvent(
            CacheEventType.HIT, "users-l1", null, "k", "v", Instant.now(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("level");
    }

    @Test
    void rejectsNullTimestamp() {
        assertThatThrownBy(() -> new CacheEvent(
            CacheEventType.HIT, "users-l1", CacheLevel.L1, "k", "v", null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("timestamp");
    }

    // ── error / type association ───────────────────────────────────────

    @Test
    void rejectsErrorNonNullForNonErrorType() {
        // error must be null for every non-ERROR type.
        for (CacheEventType type : CacheEventType.values()) {
            if (type == CacheEventType.ERROR) continue;
            assertThatThrownBy(() -> new CacheEvent(
                type, "users-l1", CacheLevel.L1, "k", "v",
                Instant.now(), new RuntimeException("boom")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("error must be null");
        }
    }

    @Test
    void rejectsNullErrorForErrorType() {
        assertThatThrownBy(() -> new CacheEvent(
            CacheEventType.ERROR, "users-l1", CacheLevel.L1, "k", "v",
            Instant.now(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("error must be non-null");
    }

    @Test
    void allowsErrorForErrorType() {
        Throwable cause = new RuntimeException("redis down");
        CacheEvent event = new CacheEvent(
            CacheEventType.ERROR, "users-l2", CacheLevel.L2, "k", null,
            Instant.now(), cause);

        assertThat(event.type()).isEqualTo(CacheEventType.ERROR);
        assertThat(event.error()).isSameAs(cause);
    }

    // ── factories ──────────────────────────────────────────────────────

    @Test
    void ofFactoryProducesEventWithNullErrorAndCurrentTimestamp() {
        Instant before = Instant.now();
        CacheEvent event = CacheEvent.of(
            CacheEventType.MISS, "users-l1", CacheLevel.L1, "user:99", null);
        Instant after = Instant.now();

        assertThat(event.type()).isEqualTo(CacheEventType.MISS);
        assertThat(event.cacheName()).isEqualTo("users-l1");
        assertThat(event.level()).isEqualTo(CacheLevel.L1);
        assertThat(event.key()).isEqualTo("user:99");
        assertThat(event.value()).isNull();
        assertThat(event.error()).isNull();
        // of() injects Instant.now(); ensure it lies in the [before, after] window.
        assertThat(event.timestamp()).isBetween(before, after);
    }

    @Test
    void errorFactoryProducesErrorEvent() {
        Instant before = Instant.now();
        Throwable cause = new IllegalStateException("serialization failed");
        CacheEvent event = CacheEvent.error("users-l2", CacheLevel.L2, "user:1", cause);
        Instant after = Instant.now();

        assertThat(event.type()).isEqualTo(CacheEventType.ERROR);
        assertThat(event.cacheName()).isEqualTo("users-l2");
        assertThat(event.level()).isEqualTo(CacheLevel.L2);
        assertThat(event.key()).isEqualTo("user:1");
        assertThat(event.value()).isNull();
        assertThat(event.error()).isSameAs(cause);
        assertThat(event.timestamp()).isBetween(before, after);
    }

    @Test
    void errorFactoryRejectsNullCause() {
        assertThatThrownBy(() -> CacheEvent.error("users-l1", CacheLevel.L1, "k", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("error");
    }
}
