package com.holo.framework.horm.migration;

import org.flywaydb.core.api.migration.JavaMigration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Flyway 迁移配置。
 *
 * <p>支持配置 Java 迁移和 SQL 迁移位置。
 */
public final class FlywayMigrationConfig {

    private final List<JavaMigration> javaMigrations;
    private final List<String> locations;
    private final boolean baselineOnMigrate;
    private final String baselineVersion;
    private final String table;

    private FlywayMigrationConfig(Builder builder) {
        this.javaMigrations = Collections.unmodifiableList(new ArrayList<>(builder.javaMigrations));
        this.locations = Collections.unmodifiableList(new ArrayList<>(builder.locations));
        this.baselineOnMigrate = builder.baselineOnMigrate;
        this.baselineVersion = builder.baselineVersion;
        this.table = builder.table;
    }

    public List<JavaMigration> getJavaMigrations() {
        return javaMigrations;
    }

    public List<String> getLocations() {
        return locations;
    }

    public boolean isBaselineOnMigrate() {
        return baselineOnMigrate;
    }

    public String getBaselineVersion() {
        return baselineVersion;
    }

    public String getTable() {
        return table;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<JavaMigration> javaMigrations = new ArrayList<>();
        private final List<String> locations = new ArrayList<>();
        private boolean baselineOnMigrate = false;
        private String baselineVersion = "1";
        private String table = "flyway_schema_history";

        private Builder() {
        }

        public Builder addJavaMigration(JavaMigration migration) {
            javaMigrations.add(migration);
            return this;
        }

        public Builder addLocation(String location) {
            locations.add(location);
            return this;
        }

        public Builder baselineOnMigrate(boolean baselineOnMigrate) {
            this.baselineOnMigrate = baselineOnMigrate;
            return this;
        }

        public Builder baselineVersion(String baselineVersion) {
            this.baselineVersion = baselineVersion;
            return this;
        }

        public Builder table(String table) {
            this.table = table;
            return this;
        }

        public FlywayMigrationConfig build() {
            return new FlywayMigrationConfig(this);
        }
    }
}
