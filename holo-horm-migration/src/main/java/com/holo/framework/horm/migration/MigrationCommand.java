package com.holo.framework.horm.migration;

/**
 * 迁移命令接口。
 */
public interface MigrationCommand {

    /**
     * 执行命令。
     *
     * @param args 命令参数
     * @return 命令结果
     */
    CommandResult execute(String[] args);

    /**
     * 命令结果。
     */
    record CommandResult(boolean success, String message) {
    }
}
