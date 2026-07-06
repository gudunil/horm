package com.holo.framework.horm.starter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HormProperties}.
 */
class HormPropertiesTest {

    @Test
    void defaultValues() {
        HormProperties properties = new HormProperties();
        assertThat(properties.getMigration()).isNotNull();
        assertThat(properties.getMigration().isEnabled()).isTrue();
        assertThat(properties.getMigration().isAutoOnStartup()).isFalse();
    }

    @Test
    void setMigrationProperties() {
        HormProperties properties = new HormProperties();
        properties.getMigration().setEnabled(false);
        properties.getMigration().setAutoOnStartup(true);

        assertThat(properties.getMigration().isEnabled()).isFalse();
        assertThat(properties.getMigration().isAutoOnStartup()).isTrue();
    }

    @Test
    void setMigrationObject() {
        HormProperties properties = new HormProperties();
        HormProperties.Migration migration = new HormProperties.Migration();
        migration.setEnabled(false);
        migration.setAutoOnStartup(true);

        properties.setMigration(migration);

        assertThat(properties.getMigration()).isSameAs(migration);
        assertThat(properties.getMigration().isEnabled()).isFalse();
        assertThat(properties.getMigration().isAutoOnStartup()).isTrue();
    }
}
