package com.holo.framework.horm.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MigrationChecksum 单元测试。
 */
class MigrationChecksumTest {

    @Test
    void computeFromByteArray() {
        byte[] content = "CREATE TABLE users (id BIGINT PRIMARY KEY)".getBytes();
        int checksum = MigrationChecksum.compute(content);

        assertThat(checksum).isNotZero();
    }

    @Test
    void computeFromInputStream() throws Exception {
        byte[] content = "CREATE TABLE users (id BIGINT PRIMARY KEY)".getBytes();
        ByteArrayInputStream stream = new ByteArrayInputStream(content);
        int checksum = MigrationChecksum.compute(stream);

        assertThat(checksum).isNotZero();
    }

    @Test
    void computeFromFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("migration.sql");
        Files.writeString(file, "CREATE TABLE users (id BIGINT PRIMARY KEY)");

        int checksum = MigrationChecksum.compute(file);

        assertThat(checksum).isNotZero();
    }

    @Test
    void sameContentProducesSameChecksum() {
        byte[] content = "CREATE TABLE users (id BIGINT PRIMARY KEY)".getBytes();
        int checksum1 = MigrationChecksum.compute(content);
        int checksum2 = MigrationChecksum.compute(content);

        assertThat(checksum1).isEqualTo(checksum2);
    }

    @Test
    void differentContentProducesDifferentChecksum() {
        byte[] content1 = "CREATE TABLE users (id BIGINT PRIMARY KEY)".getBytes();
        byte[] content2 = "CREATE TABLE users (id INT PRIMARY KEY)".getBytes();

        int checksum1 = MigrationChecksum.compute(content1);
        int checksum2 = MigrationChecksum.compute(content2);

        assertThat(checksum1).isNotEqualTo(checksum2);
    }
}
