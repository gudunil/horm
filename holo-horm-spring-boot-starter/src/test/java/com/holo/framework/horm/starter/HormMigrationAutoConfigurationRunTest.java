package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

/**
 * Tests for {@link HormMigrationAutoConfiguration#run(String...)}.
 */
class HormMigrationAutoConfigurationRunTest {

    private HormMigrationAutoConfiguration config;
    private HormProperties properties;

    @BeforeEach
    void setUp() {
        properties = new HormProperties();
        config = new HormMigrationAutoConfiguration(properties);
    }

    @AfterEach
    void tearDown() {
        if (HormContext.isInstalled()) {
            HormContext ctx = HormContext.current();
            ctx.close();
            HormContext.install(null);
        }
    }

    @Test
    void runWithAutoOnStartupDisabled() throws Exception {
        properties.getMigration().setAutoOnStartup(false);
        config.run();
        // Should not throw, just return early
    }

    @Test
    void runWithEmptyMigrationResult() throws Exception {
        properties.getMigration().setAutoOnStartup(true);

        try (var mockedHorm = mockStatic(Horm.class)) {
            Map<String, Integer> emptyResult = new HashMap<>();
            mockedHorm.when(Horm::migrate).thenReturn(emptyResult);

            config.run();
            // Should log "No migrations executed"
        }
    }

    @Test
    void runWithSuccessfulMigrations() throws Exception {
        properties.getMigration().setAutoOnStartup(true);

        try (var mockedHorm = mockStatic(Horm.class)) {
            Map<String, Integer> result = new HashMap<>();
            result.put("primary", 2);
            result.put("secondary", 1);
            mockedHorm.when(Horm::migrate).thenReturn(result);

            config.run();
            // Should log migration counts and total
        }
    }

    @Test
    void runWithZeroMigrationsOnDatasource() throws Exception {
        properties.getMigration().setAutoOnStartup(true);

        try (var mockedHorm = mockStatic(Horm.class)) {
            Map<String, Integer> result = new HashMap<>();
            result.put("primary", 0);
            mockedHorm.when(Horm::migrate).thenReturn(result);

            config.run();
            // Should log "No migrations executed on datasource"
        }
    }

    @Test
    void runWithMigrationException() {
        properties.getMigration().setAutoOnStartup(true);

        try (var mockedHorm = mockStatic(Horm.class)) {
            mockedHorm.when(Horm::migrate).thenThrow(new RuntimeException("Migration failed"));

            assertThatThrownBy(() -> config.run())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Migration failed");
        }
    }
}
