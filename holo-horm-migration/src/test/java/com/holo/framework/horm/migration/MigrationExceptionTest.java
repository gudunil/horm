package com.holo.framework.horm.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MigrationException} 和 {@link MigrationChecksumException} 单元测试。
 */
class MigrationExceptionTest {

    @Test
    void migrationExceptionWithMessage() {
        MigrationException ex = new MigrationException("test error");
        assertThat(ex.getMessage()).isEqualTo("test error");
    }

    @Test
    void migrationExceptionWithCause() {
        Throwable cause = new RuntimeException("root");
        MigrationException ex = new MigrationException("wrapped", cause);
        assertThat(ex.getMessage()).isEqualTo("wrapped");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void checksumExceptionStoresFields() {
        MigrationChecksumException ex = new MigrationChecksumException("V1", 12345, 67890);

        assertThat(ex.getVersion()).isEqualTo("V1");
        assertThat(ex.getExpectedChecksum()).isEqualTo(12345);
        assertThat(ex.getActualChecksum()).isEqualTo(67890);
        assertThat(ex.getMessage()).contains("V1");
        assertThat(ex.getMessage()).contains("12345");
        assertThat(ex.getMessage()).contains("67890");
    }

    @Test
    void checksumExceptionIsMigrationException() {
        MigrationChecksumException ex = new MigrationChecksumException("V2", 100, 200);
        assertThat(ex).isInstanceOf(MigrationException.class);
    }
}