package com.holo.framework.horm.migration;

/**
 * 迁移状态信息。
 *
 * @param appliedCount 已应用的迁移数量
 * @param pendingCount 待应用的迁移数量
 */
public record MigrationStatus(int appliedCount, int pendingCount) {
}
