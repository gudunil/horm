package com.holo.framework.horm.meta.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionTest {

    private static final LongField<Object> ID = LongField.of(Object.class, "id", "id");
    private static final StringField<Object> EMAIL = StringField.of(Object.class, "email", "email");

    @Test
    void eqProducesSimpleFragment() {
        Condition c = Conditions.eq(ID, 1L);
        assertThat(c.sqlFragment()).isEqualTo("id = ?");
        assertThat(c.bindings()).containsExactly(1L);
    }

    @Test
    void neProducesNotEqualsFragment() {
        Condition c = Conditions.ne(ID, 1L);
        assertThat(c.sqlFragment()).isEqualTo("id <> ?");
        assertThat(c.bindings()).containsExactly(1L);
    }

    @Test
    void gtProducesGreaterThanFragment() {
        Condition c = Conditions.gt(ID, 10L);
        assertThat(c.sqlFragment()).isEqualTo("id > ?");
        assertThat(c.bindings()).containsExactly(10L);
    }

    @Test
    void ltProducesLessThanFragment() {
        Condition c = Conditions.lt(ID, 10L);
        assertThat(c.sqlFragment()).isEqualTo("id < ?");
        assertThat(c.bindings()).containsExactly(10L);
    }

    @Test
    void geProducesGreaterOrEqualFragment() {
        Condition c = Conditions.ge(ID, 10L);
        assertThat(c.sqlFragment()).isEqualTo("id >= ?");
        assertThat(c.bindings()).containsExactly(10L);
    }

    @Test
    void leProducesLessOrEqualFragment() {
        Condition c = Conditions.le(ID, 10L);
        assertThat(c.sqlFragment()).isEqualTo("id <= ?");
        assertThat(c.bindings()).containsExactly(10L);
    }

    @Test
    void betweenProducesBetweenFragment() {
        Condition c = Conditions.between(ID, 1L, 100L);
        assertThat(c.sqlFragment()).isEqualTo("id BETWEEN ? AND ?");
        assertThat(c.bindings()).containsExactly(1L, 100L);
    }

    @Test
    void inProducesInFragment() {
        Condition c = Conditions.in(ID, List.of(1L, 2L, 3L));
        assertThat(c.sqlFragment()).isEqualTo("id IN (?, ?, ?)");
        assertThat(c.bindings()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void likeProducesLikeFragment() {
        Condition c = Conditions.like(EMAIL, "%@%");
        assertThat(c.sqlFragment()).isEqualTo("email LIKE ?");
        assertThat(c.bindings()).containsExactly("%@%");
    }

    @Test
    void isNullProducesNoBinding() {
        Condition c = Conditions.isNull(EMAIL);
        assertThat(c.sqlFragment()).isEqualTo("email IS NULL");
        assertThat(c.bindings()).isEmpty();
    }

    @Test
    void isNotNullProducesNoBinding() {
        Condition c = Conditions.isNotNull(EMAIL);
        assertThat(c.sqlFragment()).isEqualTo("email IS NOT NULL");
        assertThat(c.bindings()).isEmpty();
    }

    @Test
    void eqAllowsNullBinding() {
        Condition c = Conditions.eq(EMAIL, null);
        assertThat(c.sqlFragment()).isEqualTo("email = ?");
        assertThat(c.bindings()).hasSize(1);
        assertThat(c.bindings().get(0)).isNull();
    }

    @Test
    void andCombinesWithParentheses() {
        Condition c1 = Conditions.eq(ID, 1L);
        Condition c2 = Conditions.eq(EMAIL, "foo");
        Condition and = Condition.and(c1, c2);
        assertThat(and.sqlFragment()).isEqualTo("(id = ?) AND (email = ?)");
        assertThat(and.bindings()).containsExactly(1L, "foo");
    }

    @Test
    void orCombinesWithParentheses() {
        Condition c1 = Conditions.eq(ID, 1L);
        Condition c2 = Conditions.eq(ID, 2L);
        Condition or = Condition.or(c1, c2);
        assertThat(or.sqlFragment()).isEqualTo("(id = ?) OR (id = ?)");
        assertThat(or.bindings()).containsExactly(1L, 2L);
    }

    @Test
    void notWrapsWithNotPrefix() {
        Condition c = Conditions.eq(ID, 1L);
        Condition not = Condition.not(c);
        assertThat(not.sqlFragment()).isEqualTo("NOT (id = ?)");
        assertThat(not.bindings()).containsExactly(1L);
    }

    @Test
    void andSingleConditionReturnsSameInstance() {
        Condition c = Conditions.eq(ID, 1L);
        Condition and = Condition.and(c);
        assertThat(and).isSameAs(c);
    }

    @Test
    void orSingleConditionReturnsSameInstance() {
        Condition c = Conditions.eq(ID, 1L);
        Condition or = Condition.or(c);
        assertThat(or).isSameAs(c);
    }

    @Test
    void nestedAndOrRendersCorrectly() {
        Condition c1 = Conditions.eq(ID, 1L);
        Condition c2 = Conditions.eq(ID, 2L);
        Condition c3 = Conditions.eq(EMAIL, "foo");
        Condition nested = Condition.and(Condition.or(c1, c2), c3);
        assertThat(nested.sqlFragment()).isEqualTo("((id = ?) OR (id = ?)) AND (email = ?)");
        assertThat(nested.bindings()).containsExactly(1L, 2L, "foo");
    }

    @Test
    void notOfNestedAndRendersCorrectly() {
        Condition c1 = Conditions.eq(ID, 1L);
        Condition c2 = Conditions.eq(EMAIL, "foo");
        Condition not = Condition.not(Condition.and(c1, c2));
        assertThat(not.sqlFragment()).isEqualTo("NOT ((id = ?) AND (email = ?))");
        assertThat(not.bindings()).containsExactly(1L, "foo");
    }

    @Test
    void andEmptyThrows() {
        assertThatThrownBy(() -> Condition.and())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AND requires at least one condition");
    }

    @Test
    void orEmptyThrows() {
        assertThatThrownBy(() -> Condition.or())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("OR requires at least one condition");
    }

    @Test
    void inEmptyThrows() {
        assertThatThrownBy(() -> Conditions.in(ID, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("IN requires at least one value");
    }

    @Test
    void notNullConditionThrows() {
        assertThatThrownBy(() -> Condition.not(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NOT requires a condition");
    }

    @Test
    void bindingsAreImmutable() {
        Condition c = Conditions.eq(ID, 1L);
        assertThatThrownBy(() -> c.bindings().add(2L))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
