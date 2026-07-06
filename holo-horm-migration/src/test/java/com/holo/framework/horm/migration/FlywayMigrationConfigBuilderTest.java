package com.holo.framework.horm.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FlywayMigrationConfig.Builder} 单元测试。
 */
class FlywayMigrationConfigBuilderTest {

    @Test
    void defaultValues() {
        FlywayMigrationConfig cfg = FlywayMigrationConfig.builder().build();

        assertThat(cfg.getTable()).isEqualTo("flyway_schema_history");
        assertThat(cfg.getBaselineVersion()).isEqualTo("1");
        assertThat(cfg.isBaselineOnMigrate()).isFalse();
        assertThat(cfg.getJavaMigrations()).isEmpty();
        assertThat(cfg.getLocations()).isEmpty();
    }

    @Test
    void customTable() {
        FlywayMigrationConfig cfg = FlywayMigrationConfig.builder()
            .table("my_history")
            .build();

        assertThat(cfg.getTable()).isEqualTo("my_history");
    }

    @Test
    void baselineSettings() {
        FlywayMigrationConfig cfg = FlywayMigrationConfig.builder()
            .baselineOnMigrate(true)
            .baselineVersion("2.5")
            .build();

        assertThat(cfg.isBaselineOnMigrate()).isTrue();
        assertThat(cfg.getBaselineVersion()).isEqualTo("2.5");
    }

    @Test
    void addJavaMigration() {
        FlywayMigrationConfig cfg = FlywayMigrationConfig.builder()
            .addJavaMigration(new TestMigration())
            .build();

        assertThat(cfg.getJavaMigrations()).hasSize(1);
    }

    @Test
    void addLocation() {
        FlywayMigrationConfig cfg = FlywayMigrationConfig.builder()
            .addLocation("classpath:db/migrations")
            .addLocation("filesystem:/tmp/migrations")
            .build();

        assertThat(cfg.getLocations()).hasSize(2);
        assertThat(cfg.getLocations()).containsExactly(
            "classpath:db/migrations", "filesystem:/tmp/migrations");
    }

    @Test
    void unmodifiableJavaMigrationsList() {
        FlywayMigrationConfig cfg = FlywayMigrationConfig.builder()
            .addJavaMigration(new TestMigration())
            .build();

        assertThatThrownBy(() -> cfg.getJavaMigrations().add(new TestMigration()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unmodifiableLocationsList() {
        FlywayMigrationConfig cfg = FlywayMigrationConfig.builder()
            .addLocation("classpath:db")
            .build();

        assertThatThrownBy(() -> cfg.getLocations().add("filesystem:/tmp"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    /** Minimal JavaMigration for testing. */
    private static class TestMigration implements org.flywaydb.core.api.migration.JavaMigration {
        @Override
        public void migrate(org.flywaydb.core.api.migration.Context context) {
        }
        @Override
        public Integer getChecksum() { return null; }
        @Override
        public boolean canExecuteInTransaction() { return true; }
        @Override
        public String getDescription() { return "test"; }
        @Override
        public org.flywaydb.core.api.MigrationVersion getVersion() {
            return org.flywaydb.core.api.MigrationVersion.fromVersion("999");
        }
    }
}