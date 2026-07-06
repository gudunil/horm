package com.holo.framework.horm.migration;

/**
 * 迁移运行器 SPI。
 */
public interface MigrationRunner {

    /**
     * 执行迁移，返回已应用的迁移数量。
     *
     * @return 已应用的迁移数量
     */
    int migrate();

    /**
     * 获取当前迁移状态。
     *
     * @return 迁移状态
     */
    MigrationStatus status();
}
