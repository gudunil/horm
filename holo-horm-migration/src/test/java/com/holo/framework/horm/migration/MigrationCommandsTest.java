package com.holo.framework.horm.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移命令单元测试。
 */
class MigrationCommandsTest {

    @Test
    void findExistingCommand() {
        assertThat(MigrationCommands.find("migrate")).isPresent();
        assertThat(MigrationCommands.find("status")).isPresent();
        assertThat(MigrationCommands.find("make")).isPresent();
        assertThat(MigrationCommands.find("rollback")).isPresent();
    }

    @Test
    void findNonExistentCommand() {
        assertThat(MigrationCommands.find("unknown")).isEmpty();
    }

    @Test
    void allCommandsRegistered() {
        assertThat(MigrationCommands.all()).hasSize(4);
    }

    @Test
    void helpContainsAllCommands() {
        String help = MigrationCommands.help();
        assertThat(help).contains("migrate");
        assertThat(help).contains("status");
        assertThat(help).contains("make");
        assertThat(help).contains("rollback");
    }

    @Test
    void rollbackCommandReturnsFailure() {
        RollbackCommand cmd = new RollbackCommand();
        MigrationCommand.CommandResult result = cmd.execute(new String[0]);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Flyway community edition does not support rollback");
    }

    @Test
    void makeCommandRequiresArgument() {
        MakeCommand cmd = new MakeCommand();
        MigrationCommand.CommandResult result = cmd.execute(new String[0]);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Usage: make <migration_name>");
    }
}
