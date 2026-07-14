package com.holo.framework.horm.meta;

import com.holo.framework.horm.meta.query.BigDecimalField;
import com.holo.framework.horm.meta.query.IntegerField;
import com.holo.framework.horm.meta.query.LongField;
import com.holo.framework.horm.meta.query.StringField;
import com.holo.framework.horm.meta.query.expr.AggExpr;
import com.holo.framework.horm.meta.query.expr.Aggregates;
import com.holo.framework.horm.meta.query.expr.Expr;
import com.holo.framework.horm.meta.query.expr.Functions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowExprAccessorTest {

    private static final LongField<Object> ID = LongField.of(Object.class, "id", "id");
    private static final StringField<Object> NAME = StringField.of(Object.class, "name", "name");
    private static final IntegerField<Object> AGE = IntegerField.of(Object.class, "age", "age");
    private static final BigDecimalField<Object> SALARY = BigDecimalField.of(Object.class, "salary", "salary");

    private Row row;

    @BeforeEach
    void setUp() {
        row = Row.create("users");
        row.setLong("id", 1L);
        row.setString("name", "Alice");
        row.set("age", 30);
        row.set("salary", new BigDecimal("50000.00"));
        row.set("count_age", 42);
    }

    @Test
    void getTypedFieldReturnsValue() {
        Long id = row.get(ID);
        assertThat(id).isEqualTo(1L);
    }

    @Test
    void getStringFieldReturnsValue() {
        String name = row.get(NAME);
        assertThat(name).isEqualTo("Alice");
    }

    @Test
    void getIntegerFieldReturnsValue() {
        Integer age = row.get(AGE);
        assertThat(age).isEqualTo(30);
    }

    @Test
    void getBigDecimalFieldReturnsValue() {
        BigDecimal salary = row.get(SALARY);
        assertThat(salary).isEqualByComparingTo(new BigDecimal("50000.00"));
    }

    @Test
    void getAggExprUsesDerivedColumnName() {
        AggExpr<Integer> maxAge = Aggregates.max(AGE);
        row.set("max_age", 65);
        Integer result = row.get(maxAge);
        assertThat(result).isEqualTo(65);
    }

    @Test
    void getCountStarUsesCountColumnName() {
        row.set("count", 100L);
        Long result = row.get(Aggregates.count());
        assertThat(result).isEqualTo(100L);
    }

    @Test
    void getAliasedAggExprUsesAlias() {
        AggExpr<Long> aliased = Aggregates.alias(Aggregates.count(), "headcount");
        row.set("headcount", 42L);
        Long result = row.get(aliased);
        assertThat(result).isEqualTo(42L);
    }

    @Test
    void getMissingColumnThrows() {
        Expr<String> missing = Functions.raw("nonexistent", String.class);
        assertThatThrownBy(() -> row.get(missing))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not present in row");
    }

    @Test
    void containsDistinguishesAbsentFromNull() {
        row.set("nullable_col", null);
        assertThat(row.has("nullable_col")).isFalse();
        assertThat(row.contains("nullable_col")).isTrue();
        assertThat(row.contains("nonexistent")).isFalse();
    }

    @Test
    void getExprWithNullValueReturnsNull() {
        row.set("nullable_col", null);
        Expr<String> expr = new Expr<>() {
            @Override public String sqlFragment() { return "nullable_col"; }
            @Override public java.util.List<Object> bindings() { return java.util.List.of(); }
            @Override public Class<String> javaType() { return String.class; }
        };
        // This should not throw — the column exists, value is null
        String result = row.get(expr);
        assertThat(result).isNull();
    }
}
