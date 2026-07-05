package com.holo.framework.horm.meta.query;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TypedFieldTest {

    private static final LongField<Object> ID = LongField.of(Object.class, "id", "id");
    private static final StringField<Object> EMAIL = StringField.of(Object.class, "email", "email");
    private static final IntegerField<Object> AGE = IntegerField.of(Object.class, "age", "age");
    private static final InstantField<Object> CREATED_AT = InstantField.of(Object.class, "createdAt", "created_at");
    private static final BooleanField<Object> ACTIVE = BooleanField.of(Object.class, "active", "active");

    @Test
    void longFieldEqDelegatesToConditions() {
        Condition c = ID.eq(1L);
        assertThat(c.sqlFragment()).isEqualTo("id = ?");
        assertThat(c.bindings()).containsExactly(1L);
    }

    @Test
    void longFieldNeDelegatesToConditions() {
        Condition c = ID.ne(1L);
        assertThat(c.sqlFragment()).isEqualTo("id <> ?");
        assertThat(c.bindings()).containsExactly(1L);
    }

    @Test
    void longFieldGtProducesGreaterThan() {
        Condition c = ID.gt(10L);
        assertThat(c.sqlFragment()).isEqualTo("id > ?");
        assertThat(c.bindings()).containsExactly(10L);
    }

    @Test
    void longFieldLtProducesLessThan() {
        Condition c = ID.lt(10L);
        assertThat(c.sqlFragment()).isEqualTo("id < ?");
        assertThat(c.bindings()).containsExactly(10L);
    }

    @Test
    void longFieldGeProducesGreaterOrEqual() {
        Condition c = ID.ge(10L);
        assertThat(c.sqlFragment()).isEqualTo("id >= ?");
        assertThat(c.bindings()).containsExactly(10L);
    }

    @Test
    void longFieldLeProducesLessOrEqual() {
        Condition c = ID.le(10L);
        assertThat(c.sqlFragment()).isEqualTo("id <= ?");
        assertThat(c.bindings()).containsExactly(10L);
    }

    @Test
    void longFieldBetweenProducesRange() {
        Condition c = ID.between(1L, 100L);
        assertThat(c.sqlFragment()).isEqualTo("id BETWEEN ? AND ?");
        assertThat(c.bindings()).containsExactly(1L, 100L);
    }

    @Test
    void integerFieldGtWorks() {
        Condition c = AGE.gt(18);
        assertThat(c.sqlFragment()).isEqualTo("age > ?");
        assertThat(c.bindings()).containsExactly(18);
    }

    @Test
    void instantFieldBetweenWorks() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-12-31T23:59:59Z");
        Condition c = CREATED_AT.between(t1, t2);
        assertThat(c.sqlFragment()).isEqualTo("created_at BETWEEN ? AND ?");
        assertThat(c.bindings()).containsExactly(t1, t2);
    }

    @Test
    void stringFieldLikeProducesLikeFragment() {
        Condition c = EMAIL.like("%@%");
        assertThat(c.sqlFragment()).isEqualTo("email LIKE ?");
        assertThat(c.bindings()).containsExactly("%@%");
    }

    @Test
    void stringFieldAlsoHasEqFromTypedField() {
        Condition c = EMAIL.eq("foo@bar.com");
        assertThat(c.sqlFragment()).isEqualTo("email = ?");
        assertThat(c.bindings()).containsExactly("foo@bar.com");
    }

    @Test
    void isNullProducesNoBinding() {
        Condition c = EMAIL.isNull();
        assertThat(c.sqlFragment()).isEqualTo("email IS NULL");
        assertThat(c.bindings()).isEmpty();
    }

    @Test
    void isNotNullProducesNoBinding() {
        Condition c = EMAIL.isNotNull();
        assertThat(c.sqlFragment()).isEqualTo("email IS NOT NULL");
        assertThat(c.bindings()).isEmpty();
    }

    @Test
    void inDelegatesToConditions() {
        Condition c = ID.in(List.of(1L, 2L, 3L));
        assertThat(c.sqlFragment()).isEqualTo("id IN (?, ?, ?)");
        assertThat(c.bindings()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void booleanFieldHasEqButNotGt() {
        // BooleanField implements TypedField, not ComparableField, so gt/lt are not visible.
        Condition c = ACTIVE.eq(true);
        assertThat(c.sqlFragment()).isEqualTo("active = ?");
        assertThat(c.bindings()).containsExactly(true);
    }

    @Test
    void conditionsComposeWithAndOr() {
        Condition c = Condition.and(
            ID.gt(0L),
            Condition.or(EMAIL.eq("a"), EMAIL.eq("b"))
        );
        assertThat(c.sqlFragment()).isEqualTo("(id > ?) AND ((email = ?) OR (email = ?))");
        assertThat(c.bindings()).containsExactly(0L, "a", "b");
    }
}
