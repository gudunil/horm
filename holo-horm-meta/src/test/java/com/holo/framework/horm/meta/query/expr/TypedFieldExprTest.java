package com.holo.framework.horm.meta.query.expr;

import com.holo.framework.horm.meta.query.BooleanField;
import com.holo.framework.horm.meta.query.ComparableField;
import com.holo.framework.horm.meta.query.InstantField;
import com.holo.framework.horm.meta.query.IntegerField;
import com.holo.framework.horm.meta.query.LongField;
import com.holo.framework.horm.meta.query.StringField;
import com.holo.framework.horm.meta.query.TypedField;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TypedFieldExprTest {

    private static final LongField<Object> ID = LongField.of(Object.class, "id", "id");
    private static final StringField<Object> NAME = StringField.of(Object.class, "name", "name");
    private static final IntegerField<Object> AGE = IntegerField.of(Object.class, "age", "age");
    private static final InstantField<Object> CREATED = InstantField.of(Object.class, "createdAt", "created_at");
    private static final BooleanField<Object> ACTIVE = BooleanField.of(Object.class, "active", "active");

    @Test
    void sqlFragmentReturnsColumn() {
        assertThat(ID.sqlFragment()).isEqualTo("id");
        assertThat(NAME.sqlFragment()).isEqualTo("name");
        assertThat(AGE.sqlFragment()).isEqualTo("age");
        assertThat(CREATED.sqlFragment()).isEqualTo("created_at");
        assertThat(ACTIVE.sqlFragment()).isEqualTo("active");
    }

    @Test
    void bindingsReturnsEmptyList() {
        assertThat(ID.bindings()).isEmpty();
        assertThat(NAME.bindings()).isEmpty();
    }

    @Test
    void javaTypeReturnsType() {
        assertThat(ID.javaType()).isEqualTo(Long.class);
        assertThat(NAME.javaType()).isEqualTo(String.class);
        assertThat(AGE.javaType()).isEqualTo(Integer.class);
        assertThat(CREATED.javaType()).isEqualTo(Instant.class);
        assertThat(ACTIVE.javaType()).isEqualTo(Boolean.class);
    }

    @Test
    void defaultAliasIsNull() {
        assertThat(ID.alias()).isNull();
        assertThat(NAME.alias()).isNull();
    }

    @Test
    void typedFieldIsExpr() {
        Expr<?> expr = ID;
        assertThat(expr.sqlFragment()).isEqualTo("id");
        assertThat(expr.bindings()).isEmpty();
        assertThat(expr.javaType()).isEqualTo(Long.class);
    }

    @Test
    void comparableFieldIsExpr() {
        Expr<?> expr = NAME;
        assertThat(expr.sqlFragment()).isEqualTo("name");
        assertThat(expr.javaType()).isEqualTo(String.class);
    }

    @Test
    void typedFieldCanBePassedToExprApi() {
        Expr<?>[] exprs = { ID, NAME, AGE };
        for (Expr<?> e : exprs) {
            assertThat(e.sqlFragment()).isNotNull();
            assertThat(e.javaType()).isNotNull();
        }
    }
}
