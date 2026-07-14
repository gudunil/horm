package com.holo.framework.horm.meta.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionsRawTest {

    @Test
    void rawWithSingleBinding() {
        Condition c = Conditions.raw("DATE(created_at) = ?", "2026-01-01");
        assertThat(c.sqlFragment()).isEqualTo("DATE(created_at) = ?");
        assertThat(c.bindings()).containsExactly("2026-01-01");
    }

    @Test
    void rawWithMultipleBindings() {
        Condition c = Conditions.raw("a BETWEEN ? AND ?", 1, 100);
        assertThat(c.sqlFragment()).isEqualTo("a BETWEEN ? AND ?");
        assertThat(c.bindings()).containsExactly(1, 100);
    }

    @Test
    void rawWithNoBindings() {
        Condition c = Conditions.raw("1 = 1");
        assertThat(c.sqlFragment()).isEqualTo("1 = 1");
        assertThat(c.bindings()).isEmpty();
    }

    @Test
    void rawWithNullBinding() {
        Condition c = Conditions.raw("name = ?", (Object) null);
        assertThat(c.sqlFragment()).isEqualTo("name = ?");
        assertThat(c.bindings()).containsExactly((Object) null);
    }

    @Test
    void rawWithLeadingPrependsBindings() {
        Condition c = Conditions.rawWithLeading("UPPER(t0.name) = ?", List.of("leading"), "trailing");
        assertThat(c.sqlFragment()).isEqualTo("UPPER(t0.name) = ?");
        assertThat(c.bindings()).containsExactly("leading", "trailing");
    }

    @Test
    void rawWithLeadingEmptyLeadingBindings() {
        Condition c = Conditions.rawWithLeading("AGE > ?", List.of(), 18);
        assertThat(c.sqlFragment()).isEqualTo("AGE > ?");
        assertThat(c.bindings()).containsExactly(18);
    }

    @Test
    void rawWithLeadingMultipleTrailingBindings() {
        Condition c = Conditions.rawWithLeading("x BETWEEN ? AND ?", List.of("a"), 1, 2);
        assertThat(c.sqlFragment()).isEqualTo("x BETWEEN ? AND ?");
        assertThat(c.bindings()).containsExactly("a", 1, 2);
    }
}
