package com.holo.framework.horm.migration;

/**
 * 生成迁移脚本命令。
 */
public final class MakeCommand implements MigrationCommand {

    @Override
    public CommandResult execute(String[] args) {
        if (args.length == 0) {
            return new CommandResult(false, "Usage: make <migration_name>");
        }
        String name = args[0];
        String template = """
            package com.example.migration;

            import com.holo.framework.horm.migration.Migration;
            import com.holo.framework.horm.migration.Schema;

            public class %s extends Migration {
                @Override
                public void up(Schema schema) {
                    // TODO: implement migration
                }

                @Override
                public void down(Schema schema) {
                    // TODO: implement rollback (optional)
                }
            }
            """.formatted(name);
        return new CommandResult(true, template);
    }
}
