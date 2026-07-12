package com.holo.framework.horm.migration;

/**
 * 数据库迁移基类。
 *
 * <p>子类必须实现 {@link #up(Schema)} 方法定义正向迁移逻辑，可选实现
 * {@link #down(Schema)} 方法定义回滚逻辑（仅用于测试，生产环境 Flyway 社区版不支持 undo）。
 *
 * <p>示例：
 * <pre>{@code
 * public class V1__CreateUsersTable extends Migration {
 *     @Override
 *     public void up(Schema schema) {
 *         schema.createTable("users", t -> {
 *             t.bigIncrements("id");
 *             t.string("email", 128).notNull().unique();
 *             t.timestamps();
 *         });
 *     }
 *
 *     @Override
 *     public void down(Schema schema) {
 *         schema.dropTable("users");
 *     }
 * }
 * }</pre>
 */
public abstract class Migration {

    /**
     * 执行正向迁移（升级）。
     *
     * @param schema Schema DSL 接口
     */
    public abstract void up(Schema schema);

    /**
     * 执行回滚迁移（降级）。
     *
     * <p><strong>注意</strong>：Flyway 社区版不支持 undo migration，此方法仅在测试中使用。
     * 生产环境回滚需 Flyway Pro/Enterprise 或手动 SQL。
     *
     * @param schema Schema DSL 接口
     */
    public void down(Schema schema) {
        throw new UnsupportedOperationException(
            "down() is not supported in Flyway community edition. " +
            "Use Flyway Pro/Enterprise or manual SQL for rollback in production.");
    }
}
