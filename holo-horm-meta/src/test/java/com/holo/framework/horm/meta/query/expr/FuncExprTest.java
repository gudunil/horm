package com.holo.framework.horm.meta.query.expr;

import com.holo.framework.horm.meta.query.Condition;
import com.holo.framework.horm.meta.query.InstantField;
import com.holo.framework.horm.meta.query.IntegerField;
import com.holo.framework.horm.meta.query.LongField;
import com.holo.framework.horm.meta.query.StringField;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FuncExprTest {

    private static final StringField<Object> NAME = StringField.of(Object.class, "name", "name");
    private static final StringField<Object> EMAIL = StringField.of(Object.class, "email", "email");
    private static final IntegerField<Object> AGE = IntegerField.of(Object.class, "age", "age");
    private static final InstantField<Object> CREATED = InstantField.of(Object.class, "createdAt", "created_at");
    private static final LongField<Object> ID = LongField.of(Object.class, "id", "id");

    @Test
    void upperRendersUpperCase() {
        ComparableExpr<String> expr = Functions.upper(NAME);
        assertThat(expr.sqlFragment()).isEqualTo("UPPER(name)");
        assertThat(expr.bindings()).isEmpty();
        assertThat(expr.javaType()).isEqualTo(String.class);
    }

    @Test
    void lowerRendersLowerCase() {
        ComparableExpr<String> expr = Functions.lower(NAME);
        assertThat(expr.sqlFragment()).isEqualTo("LOWER(name)");
    }

    @Test
    void trimRendersTrim() {
        ComparableExpr<String> expr = Functions.trim(NAME);
        assertThat(expr.sqlFragment()).isEqualTo("TRIM(name)");
    }

    @Test
    void substringRendersWithStartAndLength() {
        ComparableExpr<String> expr = Functions.substring(NAME, 1, 10);
        assertThat(expr.sqlFragment()).isEqualTo("SUBSTRING(name, 1, 10)");
    }

    @Test
    void concatWithTypedFields() {
        ComparableExpr<String> expr = Functions.concat(NAME, EMAIL);
        assertThat(expr.sqlFragment()).isEqualTo("CONCAT(name, email)");
        assertThat(expr.bindings()).isEmpty();
    }

    @Test
    void concatWithMixedExprs() {
        ComparableExpr<String> upper = Functions.upper(NAME);
        ComparableExpr<String> expr = Functions.concat(upper, EMAIL);
        assertThat(expr.sqlFragment()).isEqualTo("CONCAT(UPPER(name), email)");
    }

    @Test
    void lengthRendersLength() {
        ComparableExpr<Integer> expr = Functions.length(NAME);
        assertThat(expr.sqlFragment()).isEqualTo("LENGTH(name)");
        assertThat(expr.javaType()).isEqualTo(Integer.class);
    }

    @Test
    void absRendersAbs() {
        ComparableExpr<Integer> expr = Functions.abs(AGE);
        assertThat(expr.sqlFragment()).isEqualTo("ABS(age)");
    }

    @Test
    void roundRendersWithScale() {
        ComparableExpr<Integer> expr = Functions.round(AGE, 2);
        assertThat(expr.sqlFragment()).isEqualTo("ROUND(age, 2)");
    }

    @Test
    void floorRendersFloor() {
        ComparableExpr<Integer> expr = Functions.floor(AGE);
        assertThat(expr.sqlFragment()).isEqualTo("FLOOR(age)");
    }

    @Test
    void ceilRendersCeil() {
        ComparableExpr<Integer> expr = Functions.ceil(AGE);
        assertThat(expr.sqlFragment()).isEqualTo("CEIL(age)");
    }

    @Test
    void nowRendersNow() {
        ComparableExpr<Instant> expr = Functions.now();
        assertThat(expr.sqlFragment()).isEqualTo("NOW()");
        assertThat(expr.javaType()).isEqualTo(Instant.class);
    }

    @Test
    void dateFormatRendersWithBinding() {
        ComparableExpr<String> expr = Functions.dateFormat(CREATED, "yyyy-MM-dd");
        assertThat(expr.sqlFragment()).isEqualTo("DATE_FORMAT(created_at, ?)");
        assertThat(expr.bindings()).containsExactly("yyyy-MM-dd");
        assertThat(expr.javaType()).isEqualTo(String.class);
    }

    @Test
    void yearRendersYear() {
        ComparableExpr<Integer> expr = Functions.year(CREATED);
        assertThat(expr.sqlFragment()).isEqualTo("YEAR(created_at)");
        assertThat(expr.javaType()).isEqualTo(Integer.class);
    }

    @Test
    void monthRendersMonth() {
        ComparableExpr<Integer> expr = Functions.month(CREATED);
        assertThat(expr.sqlFragment()).isEqualTo("MONTH(created_at)");
    }

    @Test
    void dayRendersDay() {
        ComparableExpr<Integer> expr = Functions.day(CREATED);
        assertThat(expr.sqlFragment()).isEqualTo("DAY(created_at)");
    }

    @Test
    void coalesceRendersCoalesce() {
        Expr<String> expr = Functions.coalesce(NAME, EMAIL);
        assertThat(expr.sqlFragment()).isEqualTo("COALESCE(name, email)");
        assertThat(expr.javaType()).isEqualTo(String.class);
    }

    @Test
    void nullifRendersNullif() {
        Expr<String> expr = Functions.nullif(NAME, EMAIL);
        assertThat(expr.sqlFragment()).isEqualTo("NULLIF(name, email)");
    }

    @Test
    void rawRendersRawFragment() {
        Expr<Long> expr = Functions.raw("COUNT(*) FILTER (WHERE active = ?)", Long.class, true);
        assertThat(expr.sqlFragment()).isEqualTo("COUNT(*) FILTER (WHERE active = ?)");
        assertThat(expr.bindings()).containsExactly(true);
        assertThat(expr.javaType()).isEqualTo(Long.class);
    }

    @Test
    void yearChainedWithGt() {
        Condition c = Functions.year(CREATED).gt(2026);
        assertThat(c.sqlFragment()).isEqualTo("YEAR(created_at) > ?");
        assertThat(c.bindings()).containsExactly(2026);
    }

    @Test
    void dateFormatChainedWithEq() {
        Condition c = Functions.dateFormat(CREATED, "yyyy-MM-dd").eq("2026-01-01");
        assertThat(c.sqlFragment()).isEqualTo("DATE_FORMAT(created_at, ?) = ?");
        assertThat(c.bindings()).containsExactly("yyyy-MM-dd", "2026-01-01");
    }

    @Test
    void upperChainedWithEq() {
        Condition c = Functions.upper(NAME).eq("JOHN");
        assertThat(c.sqlFragment()).isEqualTo("UPPER(name) = ?");
        assertThat(c.bindings()).containsExactly("JOHN");
    }
}
