package com.holo.framework.horm.meta.query.expr;

import com.holo.framework.horm.meta.query.Condition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComparableExprTest {

    private static ComparableExpr<Integer> mockExpr(String fragment, List<Object> bindings) {
        return new ComparableExpr<>() {
            @Override public String sqlFragment() { return fragment; }
            @Override public List<Object> bindings() { return bindings; }
            @Override public Class<Integer> javaType() { return Integer.class; }
        };
    }

    @Test
    void gtAppendsBinding() {
        ComparableExpr<Integer> expr = mockExpr("YEAR(t0.created_at)", List.of());
        Condition c = expr.gt(2026);
        assertThat(c.sqlFragment()).isEqualTo("YEAR(t0.created_at) > ?");
        assertThat(c.bindings()).containsExactly(2026);
    }

    @Test
    void ltAppendsBinding() {
        ComparableExpr<Integer> expr = mockExpr("AGE", List.of());
        Condition c = expr.lt(65);
        assertThat(c.sqlFragment()).isEqualTo("AGE < ?");
        assertThat(c.bindings()).containsExactly(65);
    }

    @Test
    void geAppendsBinding() {
        ComparableExpr<Integer> expr = mockExpr("AGE", List.of());
        Condition c = expr.ge(18);
        assertThat(c.sqlFragment()).isEqualTo("AGE >= ?");
        assertThat(c.bindings()).containsExactly(18);
    }

    @Test
    void leAppendsBinding() {
        ComparableExpr<Integer> expr = mockExpr("AGE", List.of());
        Condition c = expr.le(65);
        assertThat(c.sqlFragment()).isEqualTo("AGE <= ?");
        assertThat(c.bindings()).containsExactly(65);
    }

    @Test
    void betweenAppendsTwoBindings() {
        ComparableExpr<Integer> expr = mockExpr("AGE", List.of());
        Condition c = expr.between(18, 65);
        assertThat(c.sqlFragment()).isEqualTo("AGE BETWEEN ? AND ?");
        assertThat(c.bindings()).containsExactly(18, 65);
    }

    @Test
    void eqAppendsBinding() {
        ComparableExpr<Integer> expr = mockExpr("YEAR(t0.created_at)", List.of());
        Condition c = expr.eq(2026);
        assertThat(c.sqlFragment()).isEqualTo("YEAR(t0.created_at) = ?");
        assertThat(c.bindings()).containsExactly(2026);
    }

    @Test
    void neAppendsBinding() {
        ComparableExpr<Integer> expr = mockExpr("status_code", List.of());
        Condition c = expr.ne(404);
        assertThat(c.sqlFragment()).isEqualTo("status_code <> ?");
        assertThat(c.bindings()).containsExactly(404);
    }

    @Test
    void isNullNoBindings() {
        ComparableExpr<Integer> expr = mockExpr("AGE", List.of());
        Condition c = expr.isNull();
        assertThat(c.sqlFragment()).isEqualTo("AGE IS NULL");
        assertThat(c.bindings()).isEmpty();
    }

    @Test
    void isNotNullNoBindings() {
        ComparableExpr<Integer> expr = mockExpr("AGE", List.of());
        Condition c = expr.isNotNull();
        assertThat(c.sqlFragment()).isEqualTo("AGE IS NOT NULL");
        assertThat(c.bindings()).isEmpty();
    }

    @Test
    void leadingBindingsArePrepended() {
        ComparableExpr<Integer> expr = mockExpr("DATE_FORMAT(t0.created_at, ?)", List.of("%Y"));
        Condition c = expr.gt(2024);
        assertThat(c.sqlFragment()).isEqualTo("DATE_FORMAT(t0.created_at, ?) > ?");
        assertThat(c.bindings()).containsExactly("%Y", 2024);
    }

    @Test
    void betweenWithLeadingBindings() {
        ComparableExpr<Integer> expr = mockExpr("DATE_FORMAT(t0.created_at, ?)", List.of("%Y"));
        Condition c = expr.between(2024, 2026);
        assertThat(c.sqlFragment()).isEqualTo("DATE_FORMAT(t0.created_at, ?) BETWEEN ? AND ?");
        assertThat(c.bindings()).containsExactly("%Y", 2024, 2026);
    }
}
