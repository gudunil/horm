package com.holo.framework.horm.meta.query.expr;

import com.holo.framework.horm.meta.query.BigDecimalField;
import com.holo.framework.horm.meta.query.InstantField;
import com.holo.framework.horm.meta.query.IntegerField;
import com.holo.framework.horm.meta.query.LongField;
import com.holo.framework.horm.meta.query.StringField;
import com.holo.framework.horm.meta.query.TypedField;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AggExprTest {

    private static final LongField<Object> ID = LongField.of(Object.class, "id", "id");
    private static final IntegerField<Object> AGE = IntegerField.of(Object.class, "age", "age");
    private static final BigDecimalField<Object> SALARY = BigDecimalField.of(Object.class, "salary", "salary");
    private static final StringField<Object> NAME = StringField.of(Object.class, "name", "name");

    @Test
    void countStarRendersCountAll() {
        AggExpr<Long> c = Aggregates.count();
        assertThat(c.sqlFragment()).isEqualTo("COUNT(*)");
        assertThat(c.bindings()).isEmpty();
        assertThat(c.javaType()).isEqualTo(Long.class);
        assertThat(c.functionType()).isEqualTo(FunctionType.COUNT);
        assertThat(c.target()).isNull();
    }

    @Test
    void countFieldRendersCountColumn() {
        AggExpr<Long> c = Aggregates.count(ID);
        assertThat(c.sqlFragment()).isEqualTo("COUNT(id)");
        assertThat(c.target()).isEqualTo(ID);
    }

    @Test
    void countDistinctRendersDistinct() {
        AggExpr<Long> c = Aggregates.countDistinct(NAME);
        assertThat(c.sqlFragment()).isEqualTo("COUNT(DISTINCT name)");
    }

    @Test
    void sumReturnsBigDecimal() {
        AggExpr<BigDecimal> s = Aggregates.sum(AGE);
        assertThat(s.sqlFragment()).isEqualTo("SUM(age)");
        assertThat(s.javaType()).isEqualTo(BigDecimal.class);
        assertThat(s.functionType()).isEqualTo(FunctionType.SUM);
    }

    @Test
    void avgReturnsBigDecimal() {
        AggExpr<BigDecimal> a = Aggregates.avg(SALARY);
        assertThat(a.sqlFragment()).isEqualTo("AVG(salary)");
        assertThat(a.javaType()).isEqualTo(BigDecimal.class);
    }

    @Test
    void maxReturnsFieldType() {
        AggExpr<Integer> m = Aggregates.max(AGE);
        assertThat(m.sqlFragment()).isEqualTo("MAX(age)");
        assertThat(m.javaType()).isEqualTo(Integer.class);
    }

    @Test
    void minReturnsFieldType() {
        AggExpr<Integer> m = Aggregates.min(AGE);
        assertThat(m.sqlFragment()).isEqualTo("MIN(age)");
        assertThat(m.javaType()).isEqualTo(Integer.class);
    }

    @Test
    void aliasReturnsNewInstanceWithAlias() {
        AggExpr<Long> original = Aggregates.count();
        assertThat(original.alias()).isNull();

        AggExpr<Long> aliased = Aggregates.alias(original, "headcount");
        assertThat(aliased.alias()).isEqualTo("headcount");
        assertThat(aliased.sqlFragment()).isEqualTo("COUNT(*)");
        // Original is unchanged
        assertThat(original.alias()).isNull();
    }

    @Test
    void aliasOnDifferentAggregate() {
        AggExpr<BigDecimal> sumAge = Aggregates.sum(AGE);
        AggExpr<BigDecimal> aliased = Aggregates.alias(sumAge, "total_age");
        assertThat(aliased.alias()).isEqualTo("total_age");
        assertThat(aliased.sqlFragment()).isEqualTo("SUM(age)");
    }

    @Test
    void aggExprCanBeUsedAsExpr() {
        Expr<Long> expr = Aggregates.count();
        assertThat(expr.sqlFragment()).isEqualTo("COUNT(*)");
        assertThat(expr.bindings()).isEmpty();
        assertThat(expr.javaType()).isEqualTo(Long.class);
    }
}
