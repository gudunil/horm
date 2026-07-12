package com.holo.framework.horm.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adds a small random jitter to a base TTL to prevent cache stampede
 * (a.k.a. thundering herd) at TTL expiry.
 *
 * <p>When many entries are written with the same TTL, they expire at the
 * same instant, triggering a synchronized reload storm against the
 * backing store. Adding a small randomised extension ({@code 0%} to
 * {@code factor * 100%} of the base TTL) spreads expiries across a
 * window, so reloads are distributed over time rather than clustered.
 *
 * <p>The default {@link #jitter(Duration)} applies a 10% factor, which
 * is the value recommended by the HORM cache module: for a 30-minute
 * TTL, the effective TTL becomes 30 to 33 minutes.
 *
 * <p><b>Range contract.</b> For a base TTL {@code b} and a factor
 * {@code f}, the returned duration {@code r} satisfies
 * {@code b <= r < b + b * f} (i.e. {@code b <= r < b * (1 + f)}). The
 * upper bound is exclusive because the underlying {@link Random#nextDouble()}
 * returns a value in {@code [0.0, 1.0)}.
 *
 * <p><b>Nullability.</b> The {@code base} parameter must not be
 * {@code null}; passing {@code null} throws {@link NullPointerException}.
 * {@link Duration#ZERO} is a valid input and yields {@code Duration#ZERO}.
 *
 * <p>This is a utility class: it cannot be instantiated and exposes only
 * static methods. It is thread-safe because it holds no state and uses
 * {@link ThreadLocalRandom} by default.
 */
public final class TtlJitter {

    /** Default jitter factor (10%). */
    public static final double DEFAULT_FACTOR = 0.1;

    private TtlJitter() {
        // utility class — no instances
    }

    /**
     * Returns a jittered TTL using the default 10% factor and
     * {@link ThreadLocalRandom}.
     *
     * <p>Equivalent to {@code jitter(base, 0.1)}.
     *
     * @param base the base TTL; must not be {@code null}
     * @return a non-null duration in {@code [base, base * 1.1)}
     */
    public static Duration jitter(Duration base) {
        return jitter(base, DEFAULT_FACTOR);
    }

    /**
     * Returns a jittered TTL using the supplied factor and
     * {@link ThreadLocalRandom}.
     *
     * @param base   the base TTL; must not be {@code null}
     * @param factor the jitter factor in {@code [0.0, 1.0]}; e.g.
     *               {@code 0.1} means up to 10% of {@code base} is
     *               added on top
     * @return a non-null duration in {@code [base, base * (1 + factor))}
     * @throws IllegalArgumentException if {@code factor < 0} or
     *         {@code factor > 1}
     */
    public static Duration jitter(Duration base, double factor) {
        return jitter(base, factor, ThreadLocalRandom.current());
    }

    /**
     * Returns a jittered TTL using the supplied factor and an injected
     * {@link Random}. The {@code random} argument makes this overload
     * deterministic for testing: callers can pass a seeded
     * {@link java.util.Random} to reproduce a specific jitter sequence.
     *
     * @param base   the base TTL; must not be {@code null}
     * @param factor the jitter factor in {@code [0.0, 1.0]}
     * @param random the source of randomness; must not be {@code null}
     * @return a non-null duration in {@code [base, base * (1 + factor))}
     * @throws IllegalArgumentException if {@code factor < 0} or
     *         {@code factor > 1}
     */
    public static Duration jitter(Duration base, double factor, Random random) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(random, "random");
        if (factor < 0.0 || factor > 1.0 || Double.isNaN(factor)) {
            throw new IllegalArgumentException(
                "factor must be in [0.0, 1.0]: " + factor);
        }
        long baseNanos = base.toNanos();
        if (baseNanos == 0L) {
            // Avoid division/modulo edge cases; zero base yields zero.
            return Duration.ZERO;
        }
        double r = random.nextDouble();  // [0.0, 1.0)
        long jitterNanos = (long) (baseNanos * factor * r);
        return base.plusNanos(jitterNanos);
    }
}
