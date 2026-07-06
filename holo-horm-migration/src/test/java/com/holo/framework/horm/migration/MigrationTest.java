package com.holo.framework.horm.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Migration} 抽象基类单元测试。
 */
class MigrationTest {

    @Test
    void downThrowsByDefault() {
        Migration migration = new Migration() {
            @Override
            public void up(Schema schema) {
            }
        };

        assertThat(migration).isNotNull();
    }

    @Test
    void migrationSubclassCanOverrideDown() {
        Migration migration = new Migration() {
            @Override
            public void up(Schema schema) {
            }

            @Override
            public void down(Schema schema) {
                // no-op override is valid
            }
        };

        assertThat(migration).isNotNull();
    }
}