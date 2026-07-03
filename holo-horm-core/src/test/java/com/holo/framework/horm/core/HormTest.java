package com.holo.framework.horm.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link Horm}.
 *
 * <p>Validates that {@link Horm#install(HormContext)} / {@link Horm#context()}
 * delegate to {@link HormContext}, and that {@link Horm#repository(Class)}
 * surfaces a clear {@link UnsupportedOperationException} until M1-7 wires in
 * {@code JdbcRepository}.
 */
class HormTest {

    @AfterEach
    void resetContext() {
        HormContext.install(null);
    }

    @Test
    void installDelegatesToHormContext() {
        HormContext ctx = new HormContext(mock(Connection.class));
        Horm.install(ctx);
        assertThat(HormContext.current()).isSameAs(ctx);
    }

    @Test
    void contextReturnsInstalledContext() {
        HormContext ctx = new HormContext(mock(Connection.class));
        Horm.install(ctx);
        assertThat(Horm.context()).isSameAs(ctx);
    }

    @Test
    void contextThrowsWhenNotInstalled() {
        HormContext.install(null);
        assertThatThrownBy(Horm::context)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not installed");
    }

    @Test
    void repositoryThrowsUnsupportedOperationBeforeM1_7() {
        assertThatThrownBy(() -> Horm.repository(Object.class))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("M1-7");
    }
}
