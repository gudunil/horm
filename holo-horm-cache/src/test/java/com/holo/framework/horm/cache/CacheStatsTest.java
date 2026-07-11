package com.holo.framework.horm.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link CacheStats}.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>{@link CacheStats#hitRate()} arithmetic, including the 0/0
 *       boundary that must return {@code 0.0} (not {@code NaN});</li>
 *   <li>{@link CacheStats#missRate()} as the complement of hitRate;</li>
 *   <li>{@link CacheStats#empty()} returns the canonical zero instance;</li>
 *   <li>record field accessors preserve constructor arguments.</li>
 * </ul>
 */
class CacheStatsTest {

    // ── empty() factory ────────────────────────────────────────────────

    @Test
    void emptyReturnsZeroCounters() {
        CacheStats stats = CacheStats.empty();
        assertThat(stats.hits()).isZero();
        assertThat(stats.misses()).isZero();
        assertThat(stats.evictions()).isZero();
        assertThat(stats.expirations()).isZero();
        assertThat(stats.loads()).isZero();
        assertThat(stats.loadFailures()).isZero();
        assertThat(stats.averageLoadTime()).isEqualTo(Duration.ZERO);
        assertThat(stats.estimatedSize()).isZero();
    }

    @Test
    void emptyReturnsSameInstance() {
        // Canonical immutable singleton: every call yields the same instance.
        assertThat(CacheStats.empty()).isSameAs(CacheStats.empty());
    }

    // ── hitRate arithmetic ─────────────────────────────────────────────

    @Test
    void hitRateIsZeroOnZeroHitsAndZeroMisses() {
        // The 0/0 boundary: must NOT be NaN.
        CacheStats stats = new CacheStats(0L, 0L, 0L, 0L, 0L, 0L, Duration.ZERO, 0L);
        assertThat(stats.hitRate()).isEqualTo(0.0);
        assertThat(stats.hitRate()).isNotNaN();
    }

    @Test
    void hitRateIsOneWhenNoMisses() {
        CacheStats stats = new CacheStats(100L, 0L, 0L, 0L, 0L, 0L, Duration.ZERO, 100L);
        assertThat(stats.hitRate()).isEqualTo(1.0);
    }

    @Test
    void hitRateIsZeroWhenNoHits() {
        CacheStats stats = new CacheStats(0L, 100L, 0L, 0L, 0L, 0L, Duration.ZERO, 0L);
        assertThat(stats.hitRate()).isEqualTo(0.0);
    }

    @Test
    void hitRateComputesHitsOverTotal() {
        // 80 hits, 20 misses → 0.8 hit rate.
        CacheStats stats = new CacheStats(80L, 20L, 0L, 0L, 0L, 0L, Duration.ZERO, 80L);
        assertThat(stats.hitRate()).isEqualTo(0.8);
    }

    @Test
    void hitRateIsAFractionBetweenZeroAndOne() {
        CacheStats stats = new CacheStats(3L, 7L, 0L, 0L, 0L, 0L, Duration.ZERO, 3L);
        double rate = stats.hitRate();
        assertThat(rate).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(rate).isEqualTo(0.3);
    }

    // ── missRate arithmetic ────────────────────────────────────────────

    @Test
    void missRateIsZeroOnZeroHitsAndZeroMisses() {
        CacheStats stats = CacheStats.empty();
        assertThat(stats.missRate()).isEqualTo(0.0);
        assertThat(stats.missRate()).isNotNaN();
    }

    @Test
    void missRateIsComplementOfHitRate() {
        CacheStats stats = new CacheStats(70L, 30L, 0L, 0L, 0L, 0L, Duration.ZERO, 70L);
        // missRate is computed directly as misses/total to avoid floating-point
        // subtraction error from 1 - hitRate, so compare with tolerance.
        assertThat(stats.missRate()).isCloseTo(1.0 - stats.hitRate(), within(1e-9));
        assertThat(stats.missRate()).isEqualTo(0.3);
    }

    // ── field accessors preserve constructor args ──────────────────────

    @Test
    void accessorsReturnConstructorValues() {
        Duration avgLoad = Duration.ofMillis(15);
        CacheStats stats = new CacheStats(1L, 2L, 3L, 4L, 5L, 6L, avgLoad, 7L);

        assertThat(stats.hits()).isEqualTo(1L);
        assertThat(stats.misses()).isEqualTo(2L);
        assertThat(stats.evictions()).isEqualTo(3L);
        assertThat(stats.expirations()).isEqualTo(4L);
        assertThat(stats.loads()).isEqualTo(5L);
        assertThat(stats.loadFailures()).isEqualTo(6L);
        assertThat(stats.averageLoadTime()).isSameAs(avgLoad);
        assertThat(stats.estimatedSize()).isEqualTo(7L);
    }
}
