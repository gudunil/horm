package com.holo.framework.horm.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CachePolicy} and {@link CachePolicyBuilder}.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>defaults seeded by an untouched builder match the documented
 *       {@code DEFAULT_*} constants;</li>
 *   <li>every builder setter overrides its corresponding default;</li>
 *   <li>the cross-field invariant ({@code nullTtl <= ttl} when
 *       {@code nullable == true}) is enforced at {@code build()};</li>
 *   <li>{@code null} arguments to non-null fields are rejected by the
 *       setters rather than at {@code build()} time;</li>
 *   <li>{@code equals}/{@code hashCode}/{@code toString} are consistent
 *       so that {@code CachePolicy} can be used as a map key (e.g. by a
 *       future {@code CacheManager} that keys regions on policy).</li>
 * </ul>
 */
class CachePolicyTest {

    // ── defaults ────────────────────────────────────────────────────────

    @Test
    void builderSeedsDocumentedDefaults() {
        CachePolicy policy = CachePolicy.builder().build();

        assertThat(policy.ttl()).isEqualTo(CachePolicy.DEFAULT_TTL);
        assertThat(policy.evictionPolicy()).isEqualTo(CachePolicy.DEFAULT_EVICTION);
        assertThat(policy.maxEntries()).isEqualTo(CachePolicy.DEFAULT_MAX_ENTRIES);
        assertThat(policy.maxWeight()).isEqualTo(CachePolicy.DEFAULT_MAX_WEIGHT);
        assertThat(policy.writeStrategy()).isEqualTo(CachePolicy.DEFAULT_WRITE_STRATEGY);
        assertThat(policy.nullable()).isEqualTo(CachePolicy.DEFAULT_NULLABLE);
        assertThat(policy.nullTtl()).isEqualTo(CachePolicy.DEFAULT_NULL_TTL);
    }

    @Test
    void defaultTtlIsThirtyMinutes() {
        // Locked-in default: callers depend on this exact value.
        assertThat(CachePolicy.DEFAULT_TTL).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void defaultNullTtlIsOneMinute() {
        assertThat(CachePolicy.DEFAULT_NULL_TTL).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void defaultMaxEntriesIsTenThousand() {
        assertThat(CachePolicy.DEFAULT_MAX_ENTRIES).isEqualTo(10_000);
    }

    @Test
    void defaultsChooseWriteAroundAndLru() {
        // Read-through HORM scenario: DB stays the system of record.
        assertThat(CachePolicy.DEFAULT_WRITE_STRATEGY).isEqualTo(WriteStrategy.AROUND);
        assertThat(CachePolicy.DEFAULT_EVICTION).isEqualTo(EvictionPolicy.LRU);
    }

    // ── setters override defaults ───────────────────────────────────────

    @Test
    void settersOverrideEachDefault() {
        CachePolicy policy = CachePolicy.builder()
            .ttl(Duration.ofMinutes(5))
            .evictionPolicy(EvictionPolicy.W_TINY_LFU)
            .maxEntries(500)
            .maxWeight(1_000_000L)
            .writeStrategy(WriteStrategy.THROUGH)
            .nullable(false)
            .nullTtl(Duration.ofSeconds(10))
            .build();

        assertThat(policy.ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.evictionPolicy()).isEqualTo(EvictionPolicy.W_TINY_LFU);
        assertThat(policy.maxEntries()).isEqualTo(500);
        assertThat(policy.maxWeight()).isEqualTo(1_000_000L);
        assertThat(policy.writeStrategy()).isEqualTo(WriteStrategy.THROUGH);
        assertThat(policy.nullable()).isFalse();
        assertThat(policy.nullTtl()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void maxEntriesMinusOneMeansUnbounded() {
        CachePolicy policy = CachePolicy.builder().maxEntries(-1).build();
        assertThat(policy.maxEntries()).isEqualTo(-1);
    }

    @Test
    void maxWeightMinusOneMeansUnbounded() {
        CachePolicy policy = CachePolicy.builder().maxWeight(-1).build();
        assertThat(policy.maxWeight()).isEqualTo(-1L);
    }

    // ── null rejection ──────────────────────────────────────────────────

    @Test
    void ttlRejectsNull() {
        assertThatThrownBy(() -> CachePolicy.builder().ttl(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("ttl");
    }

    @Test
    void evictionPolicyRejectsNull() {
        assertThatThrownBy(() -> CachePolicy.builder().evictionPolicy(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("evictionPolicy");
    }

    @Test
    void writeStrategyRejectsNull() {
        assertThatThrownBy(() -> CachePolicy.builder().writeStrategy(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("writeStrategy");
    }

    @Test
    void nullTtlRejectsNull() {
        assertThatThrownBy(() -> CachePolicy.builder().nullTtl(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("nullTtl");
    }

    // ── bounds validation ──────────────────────────────────────────────

    @Test
    void maxEntriesRejectsZero() {
        assertThatThrownBy(() -> CachePolicy.builder().maxEntries(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxEntries");
    }

    @Test
    void maxEntriesRejectsNegative() {
        assertThatThrownBy(() -> CachePolicy.builder().maxEntries(-2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxEntries");
    }

    @Test
    void maxWeightRejectsZero() {
        assertThatThrownBy(() -> CachePolicy.builder().maxWeight(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxWeight");
    }

    @Test
    void maxWeightRejectsNegative() {
        assertThatThrownBy(() -> CachePolicy.builder().maxWeight(-2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxWeight");
    }

    // ── cross-field invariant ──────────────────────────────────────────

    @Test
    void buildRejectsNullTtlGreaterThanTtlWhenNullable() {
        assertThatThrownBy(() -> CachePolicy.builder()
            .ttl(Duration.ofMinutes(1))
            .nullTtl(Duration.ofMinutes(2))
            .nullable(true)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nullTtl")
            .hasMessageContaining("ttl");
    }

    @Test
    void buildAllowsNullTtlGreaterThanTtlWhenNullableDisabled() {
        // When nullable is false, nullTtl is moot so the cross-field
        // rule does not apply.
        CachePolicy policy = CachePolicy.builder()
            .ttl(Duration.ofMinutes(1))
            .nullTtl(Duration.ofMinutes(2))
            .nullable(false)
            .build();

        assertThat(policy.nullable()).isFalse();
        assertThat(policy.nullTtl()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void buildAllowsEqualTtlAndNullTtl() {
        CachePolicy policy = CachePolicy.builder()
            .ttl(Duration.ofMinutes(1))
            .nullTtl(Duration.ofMinutes(1))
            .build();

        assertThat(policy.ttl()).isEqualTo(policy.nullTtl());
    }

    // ── immutability ───────────────────────────────────────────────────

    @Test
    void builderMutationDoesNotAffectBuiltPolicy() {
        CachePolicyBuilder builder = CachePolicy.builder();
        CachePolicy first = builder.ttl(Duration.ofMinutes(10)).build();

        // Mutate the builder after build() — first must be unaffected.
        builder.ttl(Duration.ofMinutes(99));
        CachePolicy second = builder.build();

        assertThat(first.ttl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(second.ttl()).isEqualTo(Duration.ofMinutes(99));
    }

    // ── equals / hashCode / toString ───────────────────────────────────

    @Test
    void equalsIsTrueForSameFieldValues() {
        CachePolicy a = CachePolicy.builder().ttl(Duration.ofMinutes(5)).build();
        CachePolicy b = CachePolicy.builder().ttl(Duration.ofMinutes(5)).build();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equalsIsFalseForDifferentFieldValues() {
        CachePolicy a = CachePolicy.builder().ttl(Duration.ofMinutes(5)).build();
        CachePolicy b = CachePolicy.builder().ttl(Duration.ofMinutes(6)).build();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equalsHandlesNullAndOtherType() {
        CachePolicy policy = CachePolicy.builder().build();
        assertThat(policy).isNotEqualTo(null);
        assertThat(policy).isNotEqualTo("not a policy");
    }

    @Test
    void toStringContainsAllFieldNames() {
        String s = CachePolicy.builder().build().toString();
        assertThat(s).contains("ttl")
            .contains("evictionPolicy")
            .contains("maxEntries")
            .contains("maxWeight")
            .contains("writeStrategy")
            .contains("nullable")
            .contains("nullTtl");
    }
}
