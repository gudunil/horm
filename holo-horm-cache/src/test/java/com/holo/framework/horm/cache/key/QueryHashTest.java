package com.holo.framework.horm.cache.key;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link QueryHash}.
 */
class QueryHashTest {

    @Test
    void hashStringPartsIsDeterministic() {
        String a = QueryHash.hash("WHERE email = ?", "alice@holo.dev");
        String b = QueryHash.hash("WHERE email = ?", "alice@holo.dev");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void hashStringPartsIsTruncatedTo16Chars() {
        String hash = QueryHash.hash("any", "input", "will", "do");

        assertThat(hash).hasSize(16);
        assertThat(hash).matches("[0-9a-f]{16}");
    }

    @Test
    void differentInputsProduceDifferentHashes() {
        String a = QueryHash.hash("WHERE email = ?", "alice@holo.dev");
        String b = QueryHash.hash("WHERE email = ?", "bob@holo.dev");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashStringPartsRejectsNullArray() {
        assertThatThrownBy(() -> QueryHash.hash((String[]) null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parts");
    }

    @Test
    void hashStringPartsRejectsNullElement() {
        assertThatThrownBy(() -> QueryHash.hash("ok", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parts[1]");
    }

    @Test
    void hashConditionsOrdersOffsetLimitIsDeterministic() {
        String a = QueryHash.hash("WHERE x = ?", "ORDER BY id", 0L, 10L);
        String b = QueryHash.hash("WHERE x = ?", "ORDER BY id", 0L, 10L);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(16);
    }

    @Test
    void hashConditionsOrdersOffsetLimitDifferentiatesByOffset() {
        String page1 = QueryHash.hash("WHERE x = ?", "", 0L, 10L);
        String page2 = QueryHash.hash("WHERE x = ?", "", 10L, 10L);

        assertThat(page1).isNotEqualTo(page2);
    }

    @Test
    void hashConditionsOrdersOffsetLimitDifferentiatesByLimit() {
        String small = QueryHash.hash("WHERE x = ?", "", 0L, 10L);
        String large = QueryHash.hash("WHERE x = ?", "", 0L, 100L);

        assertThat(small).isNotEqualTo(large);
    }

    @Test
    void hashConditionsOrdersOffsetLimitDifferentiatesByConditions() {
        String withCond = QueryHash.hash("WHERE x = ?", "", 0L, 10L);
        String emptyCond = QueryHash.hash("", "", 0L, 10L);

        assertThat(withCond).isNotEqualTo(emptyCond);
    }

    @Test
    void hashConditionsOrdersOffsetLimitDifferentiatesByOrders() {
        String asc = QueryHash.hash("WHERE x = ?", "ORDER BY id ASC", 0L, 10L);
        String desc = QueryHash.hash("WHERE x = ?", "ORDER BY id DESC", 0L, 10L);

        assertThat(asc).isNotEqualTo(desc);
    }

    @Test
    void hashConditionsRejectsNullConditions() {
        assertThatThrownBy(() -> QueryHash.hash(null, "", 0L, 10L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("conditions");
    }

    @Test
    void hashConditionsRejectsNullOrders() {
        assertThatThrownBy(() -> QueryHash.hash("", null, 0L, 10L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("orders");
    }

    @Test
    void differentOverloadsProduceDifferentHashesForSameLogicalQuery() {
        // The string-parts overload and the (conditions, orders, offset, limit)
        // overload concatenate parts differently, so even with the same
        // logical content they should not be expected to collide. This test
        // documents that fact so future refactors don't silently break the
        // contract by making them accidentally equal.
        String fromParts = QueryHash.hash("WHERE x = ?", "ORDER BY id", "0", "10");
        String fromExplicit = QueryHash.hash("WHERE x = ?", "ORDER BY id", 0L, 10L);

        assertThat(fromParts).isNotEqualTo(fromExplicit);
    }
}