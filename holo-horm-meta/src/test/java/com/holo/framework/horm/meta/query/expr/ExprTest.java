package com.holo.framework.horm.meta.query.expr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExprTest {

    @Test
    void defaultAliasIsNull() {
        Expr<String> expr = new Expr<>() {
            @Override public String sqlFragment() { return "UPPER(t0.name)"; }
            @Override public java.util.List<Object> bindings() { return java.util.List.of(); }
            @Override public Class<String> javaType() { return String.class; }
        };
        assertThat(expr.alias()).isNull();
    }

    @Test
    void customAliasOverridesDefault() {
        Expr<String> expr = new Expr<>() {
            @Override public String sqlFragment() { return "UPPER(t0.name)"; }
            @Override public java.util.List<Object> bindings() { return java.util.List.of(); }
            @Override public Class<String> javaType() { return String.class; }
            @Override public String alias() { return "upper_name"; }
        };
        assertThat(expr.alias()).isEqualTo("upper_name");
    }
}
