package com.holo.framework.horm.migration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 迁移命令注册表。
 */
public final class MigrationCommands {

    private static final Map<String, MigrationCommand> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put("migrate", new MigrateCommand());
        COMMANDS.put("status", new StatusCommand());
        COMMANDS.put("rollback", new RollbackCommand());
        COMMANDS.put("make", new MakeCommand());
    }

    private MigrationCommands() {
    }

    /**
     * 查找命令。
     *
     * @param name 命令名称
     * @return 命令（如果存在）
     */
    public static Optional<MigrationCommand> find(String name) {
        return Optional.ofNullable(COMMANDS.get(name));
    }

    /**
     * 返回所有已注册命令。
     */
    public static Map<String, MigrationCommand> all() {
        return COMMANDS;
    }

    /**
     * 生成帮助信息。
     */
    public static String help() {
        StringBuilder sb = new StringBuilder("Available commands:\n");
        for (String name : COMMANDS.keySet()) {
            sb.append("  ").append(name).append("\n");
        }
        return sb.toString();
    }
}
