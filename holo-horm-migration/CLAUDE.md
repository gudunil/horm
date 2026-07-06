[根目录](../../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **holo-horm-migration**

# Holo :: HORM :: Migration (数据库迁移工具)

## 模块职责

数据库迁移工具，集成 Flyway 引擎实现版本化 schema 管理，同时提供 Rails 风格的 Java DSL 迁移脚本（`Migration` + `Schema` 接口），支持 H2/MySQL 双方言渲染、多数据源迁移、校验和验证、命令行操作。

- **parent**: `holo-horm` (1.0.0-SNAPSHOT)
- **artifactId**: `holo-horm-migration`
- **packaging**: jar

## 子包结构

```
com.holo.framework.horm.migration
  ├── Migration               迁移基类（up/down 方法）
  ├── Schema                  Schema DSL 接口（createTable/alterTable/dropTable/createIndex/dropIndex/renameTable/renameColumn）
  ├── TableBuilder            DDL 表构建器（链式列定义）
  ├── ColumnBuilder           列定义接口
  ├── MigrationCommand        迁移命令接口
  ├── MigrationCommands       命令注册表
  ├── MigrateCommand          migrate 命令
  ├── RollbackCommand         rollback 命令
  ├── StatusCommand           status 命令
  ├── MakeCommand             make 命令（生成迁移文件）
  ├── MigrationRunner         迁移运行器接口
  ├── MigrationStatus         迁移状态（PENDING/APPLIED/FAILED）
  ├── MigrationException      迁移异常
  ├── MigrationChecksum       校验和验证（SHA-256）
  ├── MigrationChecksumException 校验和异常
  ├── FlywayMigrationConfig   Flyway 配置
  ├── FlywayMigrationRunner   Flyway 引擎封装
  ├── MultiDataSourceMigrationRunner  多数据源迁移运行器
  └── internal/
      ├── DdlSchema           Schema DDL 收集实现
      ├── SchemaRenderer      方言渲染器接口
      ├── H2SchemaRenderer    H2 方言 DDL 渲染
      ├── MySQLSchemaRenderer MySQL 方言 DDL 渲染
      ├── DefaultTableBuilder  默认表构建器
      ├── DefaultColumnBuilder 默认列构建器
      ├── TableDefinition      表定义模型
      ├── ColumnDefinition     列定义模型
      ├── ForeignKeyDefinition 外键定义模型
      ├── ConnectionDataSource 连接包装为数据源
      └── NonCloseableConnection 非关闭连接包装
```

## 对外接口

### Migration 基类

```java
public abstract class Migration {
    public abstract void up(Schema schema);    // 正向迁移
    public void down(Schema schema) { ... }    // 回滚（默认抛 Unsupported）
}
```

### Schema DSL

| 方法 | 说明 |
|------|------|
| `createTable(name, Consumer<TableBuilder>)` | 创建表 |
| `alterTable(name, Consumer<TableBuilder>)` | 修改表 |
| `dropTable(name)` | 删除表 |
| `createIndex(name, table, columns...)` | 创建索引 |
| `dropIndex(name, table)` | 删除索引 |
| `renameTable(old, new)` | 重命名表 |
| `renameColumn(table, old, new)` | 重命名列 |

### TableBuilder 列定义

| 方法 | 说明 |
|------|------|
| `bigIncrements(name)` | 自增主键 |
| `string(name, length)` | 字符串 |
| `integer(name)` | 整数 |
| `bigInteger(name)` | 大整数 |
| `boolean(name)` | 布尔 |
| `decimal(name, precision, scale)` | 小数 |
| `text(name)` | 文本 |
| `binary(name)` | 二进制 |
| `json(name)` | JSON 列 |
| `timestamp(name)` | 时间戳 |
| `timestamps()` | 自动 created_at/updated_at |
| `softDeletes()` | 软删除 deleted_at |
| `notNull()` | 非空约束 |
| `unique()` | 唯一约束 |
| `defaultVal(value)` | 默认值 |
| `references(table, column)` | 外键引用 |

### 对外运行接口

| 类 | 方法 | 说明 |
|----|------|------|
| `FlywayMigrationRunner` | `migrate()` | Flyway 执行迁移 |
| `MultiDataSourceMigrationRunner` | `migrateAll()` | 多数据源迁移 |
| `MigrationChecksum` | `verify()` | SHA-256 校验 |

## 关键依赖与配置

- **核心依赖**: `flyway-core` 9.22.3（迁移引擎）
- **依赖模块**: `holo-horm-core`（获取 DataSourceRegistry）
- **方言支持**: H2 (MODE=MySQL) + MySQL
- **H2 测试库**: `jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1`

### 设计决策

1. **Flyway 作为引擎**: 复用 Flyway 的版本管理、checksum、baseline 能力
2. **DSL 包装**: Migration + Schema 封装在 Flyway JavaMigration 之上
3. **多数据源隔离**: 每个数据源独立 Flyway 实例 + schema_migrations 表
4. **rollback 限制**: Flyway 社区版不支持 undo，down() 仅在测试中使用

## 数据模型

### DDL 内部模型

| 类 | 字段 | 说明 |
|----|------|------|
| TableDefinition | tableName, columns, foreignKeys | 表定义 |
| ColumnDefinition | name, type, nullable, unique, defaultValue, etc. | 列定义 |
| ForeignKeyDefinition | column, referencesTable, referencesColumn | 外键定义 |
| DdlSchema | statements (List<String>) | 收集的 DDL 语句列表 |

## 测试与质量

- **测试数量**: 约 30+（含集成测试）
- **关键测试**: `DdlSchemaTest`、`H2SchemaRendererTest`、`MySQLSchemaRendererTest`、`MigrationChecksumTest`、`MigrationCommandsTest`、`MigrationDslIntegrationTest`、`HormMigrationExecutorTest`
- **覆盖目标**: migration 模块 > 80%

## 相关文件清单

| 文件路径 | 说明 |
|---------|------|
| `holo-horm/holo-horm-migration/src/main/java/com/holo/framework/horm/migration/Migration.java` | 迁移基类 |
| `holo-horm/holo-horm-migration/src/main/java/com/holo/framework/horm/migration/Schema.java` | Schema DSL 接口 |
| `holo-horm/holo-horm-migration/src/main/java/com/holo/framework/horm/migration/FlywayMigrationRunner.java` | Flyway 引擎封装 |
| `holo-horm/holo-horm-migration/src/main/java/com/holo/framework/horm/migration/internal/DdlSchema.java` | DDL 收集实现 |
| `holo-horm/holo-horm-migration/src/main/java/com/holo/framework/horm/migration/internal/H2SchemaRenderer.java` | H2 方言渲染 |
| `holo-horm/holo-horm-migration/src/main/java/com/holo/framework/horm/migration/internal/MySQLSchemaRenderer.java` | MySQL 方言渲染 |

## 常见问题 (FAQ)

### 1. migration 执行失败
检查 DDL 是否与目标数据库兼容，Flyway 位置配置是否正确。

### 2. 校验和不通过
已应用的迁移脚本被修改过，需手动修复或重置基线。

### 3. 多数据源迁移
每个数据源会独立创建 schema_migrations 表，迁移按数据源分组执行。

## 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-07-06 | 初始文档生成 | 架构扫描 |