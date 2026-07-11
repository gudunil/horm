package com.holo.framework.horm.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TtlJitter}.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>default 10% factor returns a duration in
 *       {@code [base, base * 1.1)};</li>
 *   <li>factor = 0 yields the base unchanged (no jitter);</li>
 *   <li>factor = 0.5 yields a duration in {@code [base, base * 1.5)};</li>
 *   <li>factor = 1.0 yields a duration in {@code [base, base * 2)};</li>
 *   <li>factor &lt; 0 or &gt; 1 (or NaN) is rejected;</li>
 *   <li>an injected {@link Random} makes the output reproducible;</li>
 *   <li>boundary inputs: {@link Duration#ZERO} yields zero,
 *       {@code null} throws {@link NullPointerException}.</li>
 * </ul>
 */
class TtlJitterTest {

    // ── default 10% factor ─────────────────────────────────────────────

    @Test
    void jitterWithDefaultFactorStaysWithinTenPercentWindow() {
        Duration base = Duration.ofMinutes(30);
        Duration lower = base;
        Duration upper = base.multipliedBy(11).dividedBy(10);  // base * 1.1

        Duration result = TtlJitter.jitter(base);

        assertThat(result).isGreaterThanOrEqualTo(lower);
        assertThat(result).isLessThan(upper);
    }

    @Test
    void jitterWithDefaultFactorStaysWithinBoundsAcrossManySamples() {
        Duration base = Duration.ofSeconds(60);
        Duration lower = base;
        Duration upper = base.multipliedBy(11).dividedBy(10);

        for (int i = 0; i < 1000; i++) {
            Duration result = TtlJitter.jitter(base);
            assertThat(result)
                .as("iteration %d", i)
                .isGreaterThanOrEqualTo(lower)
                .isLessThan(upper);
        }
    }

    @Test
    void jitterWithDefaultFactorDoesNotShrinkBase() {
        // Even with the smallest possible random draw (0.0), the result
        // equals base exactly.
        Duration base = Duration.ofSeconds(10);
        Duration result = TtlJitter.jitter(base, 0.1, new Random(0L));
        // We don't assert exact equality with base because the first draw
        // of Random(0L) is unlikely to be exactly 0, but we do assert that
        // the result is at least base.
        assertThat(result).isGreaterThanOrEqualTo(base);
    }

    // ── factor = 0 ─────────────────────────────────────────────────────

    @Test
    void jitterWithZeroFactorReturnsBase() {
        Duration base = Duration.ofMinutes(5);

        Duration result = TtlJitter.jitter(base, 0.0);

        assertThat(result).isEqualTo(base);
    }

    @Test
    void jitterWithZeroFactorReturnsBaseRegardlessOfRandom() {
        Duration base = Duration.ofSeconds(30);
        // Two different random sources — both must yield base because
        // factor=0 means jitterNanos = baseNanos * 0 * r = 0.
        Duration a = TtlJitter.jitter(base, 0.0, new Random(123L));
        Duration b = TtlJitter.jitter(base, 0.0, new Random(456L));
        assertThat(a).isEqualTo(base);
        assertThat(b).isEqualTo(base);
    }

    // ── factor = 1.0 (max) ─────────────────────────────────────────────

    @Test
    void jitterWithFactorOneStaysWithinDoubleRange() {
        Duration base = Duration.ofSeconds(60);
        Duration lower = base;
        Duration upper = base.multipliedBy(2);  // base * 2 (exclusive)

        for (int i = 0; i < 1000; i++) {
            Duration result = TtlJitter.jitter(base, 1.0);
            assertThat(result)
                .as("iteration %d", i)
                .isGreaterThanOrEqualTo(lower)
                .isLessThan(upper);
        }
    }

    @Test
    void jitterWithFactorOneCanApproachDoubleAtMaxRandom() {
        // A Random that always returns the largest possible double < 1
        // would push the result close to base * 2. We use a stub Random
        // that returns 0.999999... to verify the upper bound stays below
        // base * 2.
        Duration base = Duration.ofSeconds(60);
        Random nearMax = new Random() {
            @Override
            public double nextDouble() {
                return 0.9999999999999999;
            }
        };
        Duration result = TtlJitter.jitter(base, 1.0, nearMax);
        assertThat(result).isLessThan(base.multipliedBy(2));
        assertThat(result).isGreaterThan(base);
    }

    // ── factor = 0.5 ───────────────────────────────────────────────────

    @Test
    void jitterWithHalfFactorStaysWithinOneAndAHalfRange() {
        Duration base = Duration.ofSeconds(60);
        Duration lower = base;
        Duration upper = base.multipliedBy(3).dividedBy(2);  // base * 1.5

        for (int i = 0; i < 1000; i++) {
            Duration result = TtlJitter.jitter(base, 0.5);
            assertThat(result)
                .as("iteration %d", i)
                .isGreaterThanOrEqualTo(lower)
                .isLessThan(upper);
        }
    }

    // ── factor validation ──────────────────────────────────────────────

    @Test
    void jitterRejectsFactorBelowZero() {
        Duration base = Duration.ofSeconds(10);
        assertThatThrownBy(() -> TtlJitter.jitter(base, -0.01))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("factor");
    }

    @Test
    void jitterRejectsFactorAboveOne() {
        Duration base = Duration.ofSeconds(10);
        assertThatThrownBy(() -> TtlJitter.jitter(base, 1.01))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("factor");
    }

    @Test
    void jitterRejectsNegativeOneFactor() {
        Duration base = Duration.ofSeconds(10);
        assertThatThrownBy(() -> TtlJitter.jitter(base, -1.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jitterRejectsFactorOfTwo() {
        Duration base = Duration.ofSeconds(10);
        assertThatThrownBy(() -> TtlJitter.jitter(base, 2.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jitterRejectsNaNFactor() {
        Duration base = Duration.ofSeconds(10);
        assertThatThrownBy(() -> TtlJitter.jitter(base, Double.NaN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jitterWithRandomRejectsInvalidFactor() {
        Duration base = Duration.ofSeconds(10);
        Random random = new Random(0L);
        assertThatThrownBy(() -> TtlJitter.jitter(base, -0.5, random))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TtlJitter.jitter(base, 1.5, random))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── null rejection ─────────────────────────────────────────────────

    @Test
    void jitterRejectsNullBase() {
        assertThatThrownBy(() -> TtlJitter.jitter(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("base");
    }

    @Test
    void jitterWithFactorRejectsNullBase() {
        assertThatThrownBy(() -> TtlJitter.jitter(null, 0.1))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("base");
    }

    @Test
    void jitterWithRandomRejectsNullBase() {
        assertThatThrownBy(() -> TtlJitter.jitter(null, 0.1, new Random()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("base");
    }

    @Test
    void jitterRejectsNullRandom() {
        Duration base = Duration.ofSeconds(10);
        assertThatThrownBy(() -> TtlJitter.jitter(base, 0.1, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("random");
    }

    // ── boundaries ─────────────────────────────────────────────────────

    @Test
    void jitterOnZeroBaseReturnsZero() {
        Duration result = TtlJitter.jitter(Duration.ZERO);
        assertThat(result).isEqualTo(Duration.ZERO);
    }

    @Test
    void jitterOnZeroBaseWithMaxFactorReturnsZero() {
        Duration result = TtlJitter.jitter(Duration.ZERO, 1.0);
        assertThat(result).isEqualTo(Duration.ZERO);
    }

    @Test
    void jitterOnZeroBaseWithRandomReturnsZero() {
        Duration result = TtlJitter.jitter(Duration.ZERO, 1.0, new Random(42L));
        assertThat(result).isEqualTo(Duration.ZERO);
    }

    @Test
    void jitterOnOneNanosecondBaseStaysNonNegative() {
        Duration base = Duration.ofNanos(1);
        for (int i = 0; i < 100; i++) {
            Duration result = TtlJitter.jitter(base);
            assertThat(result).isGreaterThanOrEqualTo(base);
        }
    }

    @Test
    void jitterOnLargeBaseDoesNotOverflow() {
        // A very large base that would overflow if naively multiplied.
        // Use a base close to Long.MAX_VALUE nanos (~292 years).
        Duration base = Duration.ofNanos(Long.MAX_VALUE / 2);
        Duration result = TtlJitter.jitter(base, 0.1, new Random(0L));
        // Result must be at least base and not overflow to negative.
        assertThat(result).isGreaterThanOrEqualTo(base);
        assertThat(result.isNegative()).isFalse();
    }

    // ── reproducibility with injected Random ───────────────────────────

    @Test
    void jitterWithSeededRandomIsReproducible() {
        Duration base = Duration.ofSeconds(100);

        Duration first = TtlJitter.jitter(base, 0.1, new Random(42L));
        Duration second = TtlJitter.jitter(base, 0.1, new Random(42L));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void jitterWithDifferentSeedsYieldsDifferentResults() {
        // Two different seeds should (with overwhelming probability)
        // produce different jitter values for a non-trivial base.
        Duration base = Duration.ofSeconds(100);

        Duration a = TtlJitter.jitter(base, 0.5, new Random(1L));
        Duration b = TtlJitter.jitter(base, 0.5, new Random(2L));

        // Not a strict guarantee but vanishingly unlikely to coincide.
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void jitterWithRandomConsumesOneDoublePerCall() {
        // Two consecutive calls on the same Random should match two
        // direct nextDouble() draws from another Random with the same
        // seed, proving the implementation consumes exactly one double
        // per call.
        Duration base = Duration.ofSeconds(60);
        Random r1 = new Random(7L);
        Random r2 = new Random(7L);

        long baseNanos = base.toNanos();
        long expectedJitter1 = (long) (baseNanos * 0.1 * r1.nextDouble());
        long expectedJitter2 = (long) (baseNanos * 0.1 * r1.nextDouble());

        Duration actual1 = TtlJitter.jitter(base, 0.1, r2);
        Duration actual2 = TtlJitter.jitter(base, 0.1, r2);

        assertThat(actual1).isEqualTo(base.plusNanos(expectedJitter1));
        assertThat(actual2).isEqualTo(base.plusNanos(expectedJitter2));
    }

    // ── repeated for additional confidence ─────────────────────────────

    @RepeatedTest(50)
    void jitterStaysInRangeAcrossRepeatedInvocations() {
        Duration base = Duration.ofSeconds(30);
        Duration result = TtlJitter.jitter(base);
        assertThat(result)
            .isGreaterThanOrEqualTo(base)
            .isLessThan(base.multipliedBy(11).dividedBy(10));
    }
}
