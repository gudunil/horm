package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    @BeforeEach
    void setUp() {
        config = new HormMigrationAutoConfiguration();
    }

    @AfterEach
    void tearDown() {
        if (HormContext.isInstalled()) {
            HormContext.current().close();
        }
    }

    @Test
    void runWithAutoOnStartupDisabled() throws Exception {
        ReflectionTestUtils.setField(config, "autoOnStartup", false);
        config.run();
        // Should not throw, just return early
    }

    @Test
    void runWithEmptyMigrationResult() throws Exception {
        ReflectionTestUtils.setField(config, "autoOnStartup", true);

        try (var mockedHorm = mockStatic(Horm.class)) {
            Map<String, Integer> emptyResult = new HashMap<>();
            mockedHorm.when(Horm::migrate).thenReturn(emptyResult);

            config.run();
            // Should log "No migrations executed"
        }
    }

    @Test
    void runWithSuccessfulMigrations() throws Exception {
        ReflectionTestUtils.setField(config, "autoOnStartup", true);

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
        ReflectionTestUtils.setField(config, "autoOnStartup", true);

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
        ReflectionTestUtils.setField(config, "autoOnStartup", true);

        try (var mockedHorm = mockStatic(Horm.class)) {
            mockedHorm.when(Horm::migrate).thenThrow(new RuntimeException("Migration failed"));

            assertThatThrownBy(() -> config.run())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Migration failed");
        }
    }
}
