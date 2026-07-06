package com.holo.framework.horm.migration;

/**
 * 迁移脚本校验和不匹配异常。
 *
 * <p>当已应用的迁移脚本被修改（与记录的 SHA-256 校验和不一致）时抛出。
 */
public class MigrationChecksumException extends MigrationException {

    private static final long serialVersionUID = 1L;

    private final String version;
    private final int expectedChecksum;
    private final int actualChecksum;

    public MigrationChecksumException(String version, int expectedChecksum, int actualChecksum) {
        super(String.format(
            "Migration %s checksum mismatch! Expected: %d, Actual: %d. " +
            "Migration file has been modified after applied. Please create a new migration.",
            version, expectedChecksum, actualChecksum));
        this.version = version;
        this.expectedChecksum = expectedChecksum;
        this.actualChecksum = actualChecksum;
    }

    public String getVersion() {
        return version;
    }

    public int getExpectedChecksum() {
        return expectedChecksum;
    }

    public int getActualChecksum() {
        return actualChecksum;
    }
}
