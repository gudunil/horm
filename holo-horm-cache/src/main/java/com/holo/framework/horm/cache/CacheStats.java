package com.holo.framework.horm.cache;

import java.time.Duration;

/**
 * Snapshot of operational counters for a {@link Cache}.
 *
 * <p>Implementations of {@link Cache#stats()} return an immutable
 * {@code CacheStats} reflecting the cache's lifetime counters (or a
 * windowed view, at the implementation's discretion). Callers can derive
 * health signals — hit rate, eviction ratio, load failure rate — by
 * combining the fields:
 *
 * <ul>
 *   <li>{@link #hitRate()} — convenience for {@code hits / (hits + misses)};</li>
 *   <li>{@link #missRate()} — the complement, useful for alarm thresholds;</li>
 *   <li>{@code (double) loadFailures / max(1, loads)} — loader reliability;</li>
 *   <li>{@code (double) evictions / max(1, estimatedSize)} — eviction
 *       pressure on a bounded cache.</li>
 * </ul>
 *
 * <p>The 0/0 boundary of {@link #hitRate()} returns {@code 0.0} (rather
 * than {@code NaN}) so that a freshly-created cache does not pollute
 * downstream aggregation. This matches the convention used by Caffeine
 * and Micrometer's {@code CacheMetrics}.
 *
 * @param hits             count of {@code get} calls that found a live entry
 * @param misses           count of {@code get} calls that did not find a live entry
 * @param evictions        count of entries removed by the eviction policy
 * @param expirations      count of entries removed by TTL expiry
 * @param loads            count of {@link CacheLoader} invocations
 * @param loadFailures     count of {@link CacheLoader} invocations that threw
 * @param averageLoadTime  mean loader latency across {@link #loads()}
 * @param estimatedSize    best-effort current entry count (approximate for
 *                         concurrent caches)
 */
public record CacheStats(long hits,
                         long misses,
                         long evictions,
                         long expirations,
                         long loads,
                         long loadFailures,
                         Duration averageLoadTime,
                         long estimatedSize) {

    /** Snapshot with all counters at zero; the canonical "fresh cache" view. */
    private static final CacheStats EMPTY = new CacheStats(
        0L, 0L, 0L, 0L, 0L, 0L, Duration.ZERO, 0L);

    /**
     * Returns the canonical zero-value instance. The same instance is
     * returned on every call because {@code CacheStats} is immutable;
     * callers MUST NOT mutate fields (records make this guarantee at the
     * language level).
     */
    public static CacheStats empty() {
        return EMPTY;
    }

    /**
     * Hit rate as a fraction in {@code [0, 1]}.
     *
     * <p>Returns {@code 0.0} when {@code hits + misses == 0} (the
     * fresh-cache case) instead of {@code NaN}, so that downstream
     * averaging and threshold checks do not need a special-case guard.
     */
    public double hitRate() {
        long total = hits + misses;
        return total == 0L ? 0.0 : (double) hits / (double) total;
    }

    /**
     * Miss rate as a fraction in {@code [0, 1]}; the complement of
     * {@link #hitRate()}. Returns {@code 0.0} on the 0/0 boundary.
     *
     * <p>Computed directly as {@code misses / (hits + misses)} rather than
     * {@code 1 - hitRate()} to avoid floating-point subtraction error
     * (e.g. {@code 1 - 0.7} yields {@code 0.30000000000000004}).
     */
    public double missRate() {
        long total = hits + misses;
        return total == 0L ? 0.0 : (double) misses / (double) total;
    }
}
