package com.holo.framework.horm.migration;

/**
 * 回滚迁移命令（Flyway 社区版不支持）。
 */
public final class RollbackCommand implements MigrationCommand {

    @Override
    public CommandResult execute(String[] args) {
        return new CommandResult(false,
            "Flyway community edition does not support rollback. " +
            "Use Flyway Pro/Enterprise or manual SQL for rollback in production.");
    }
}
