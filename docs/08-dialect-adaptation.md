# M8.5: 数据库方言适配设计

> 本文档详述 HORM 对 MySQL、PostgreSQL、H2 等关系型数据库方言差异的适配方案，包括 Dialect SPI 设计、核心改造点、实现计划与测试策略。

> **前置里程碑**：M8（Spring Boot Starter）已完成
> **后续里程碑**：M9（性能基准与 GA 发布）

---

## 一、背景与问题

M1-M8 全部使用 H2（MODE=MySQL）进行开发和测试，SQL 生成硬编码 MySQL 方言语法，存在以下方言差异问题：

| 差异点 | MySQL | PostgreSQL | H2 (MySQL 模式) | Oracle |
|--------|-------|-----------|-----------------|--------|
| 分页 | `LIMIT ? OFFSET ?` | `LIMIT ? OFFSET ?` | `LIMIT ? OFFSET ?` | `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` |
| 自增主键 | `AUTO_INCREMENT` | `SERIAL` / `GENERATED ALWAYS AS IDENTITY` | `AUTO_INCREMENT` | `GENERATED ALWAYS AS IDENTITY` |
| 主键获取 | `RETURN_GENERATED_KEYS` | `RETURNING id` | `RETURN_GENERATED_KEYS` | `RETURN_GENERATED_KEYS` |
| UPSERT | `INSERT ... ON DUPLICATE KEY UPDATE` | `INSERT ... ON CONFLICT DO UPDATE` | `INSERT ... ON DUPLICATE KEY UPDATE` | `MERGE INTO` |
| 布尔类型 | `TINYINT(1)` | `BOOLEAN` | `TINYINT(1)` | `NUMBER(1)` |
| 保留字 | 反引号 `` ` `` | 双引号 `"` | 反引号 `` ` `` | 双引号 `"` |
| 批量 INSERT | `INSERT INTO ... VALUES (...), (...)` | 同左 | 同左 | `INSERT ALL ... SELECT * FROM DUAL` |
| DROP INDEX | `DROP INDEX idx ON table` | `DROP INDEX idx` | `DROP INDEX idx ON table` | `DROP INDEX idx` |
| RENAME TABLE | `RENAME TABLE old TO new` | `ALTER TABLE old RENAME TO new` | `RENAME TABLE` | `ALTER TABLE old RENAME TO new` |
| 类型映射 | `VARCHAR(n)`/`BIGINT`/`DATETIME` | `VARCHAR(n)`/`BIGINT`/`TIMESTAMP` | `VARCHAR(n)`/`BIGINT`/`TIMESTAMP` | `VARCHAR2(n)`/`NUMBER(19)`/`TIMESTAMP` |

### 当前硬编码问题清单

| 位置 | 问题 | 影响 |
|------|------|------|
| `QueryImpl.appendLimitOffset()` | 硬编码 `LIMIT ? OFFSET ?` | PostgreSQL 兼容（语法相同），Oracle 不兼容 |
| `QueryImpl.findFirst()` | 硬编码 `LIMIT 1` | 同上 |
| `JdbcRepository.exists()` | 硬编码 `LIMIT 1` | 同上 |
| `JdbcRepository.save()` INSERT | 使用 `RETURN_GENERATED_KEYS` | PostgreSQL 推荐 `RETURNING` |
| `MySQLSchemaRenderer` | 仅 MySQL 方言 DDL | 需要 PostgreSQL 渲染器 |
| `H2SchemaRenderer` | H2 MySQL 模式特化 | 需要 H2 PostgreSQL 模式渲染器 |

---

## 二、设计目标

1. **Dialect SPI**：定义统一的数据库方言抽象接口，核心模块和迁移模块共用
2. **零侵入改造**：JdbcRepository/QueryImpl 通过 Dialect 接口生成 SQL，不硬编码任何方言
3. **自动检测**：从 JDBC URL 自动识别数据库类型，自动选择对应 Dialect
4. **M7 复用**：将 migration 模块的 `SchemaRenderer` 纳入 Dialect 体系，统一方言管理
5. **向后兼容**：默认 Dialect 为 MySQL（与现有 H2 MODE=MySQL 测试行为一致）

---

## 三、Dialect SPI 设计

### 3.1 核心接口

```java
package com.holo.framework.horm.core.dialect;

/**
 * 数据库方言 SPI，抽象不同关系型数据库的 SQL 差异。
 */
public interface Dialect {

    /** 方言唯一标识，如 "mysql"、"postgresql"、"h2"、"oracle" */
    String name();

    /** 分页 SQL 片段，附加在查询 SQL 末尾 */
    String paginate(String sql, long offset, long limit);

    /** 主键列定义片段（用于 DDL 生成） */
    String identityColumn();

    /** 自增主键生成策略 */
    IdentityStrategy identityStrategy();

    /** 标识符引用符号（MySQL: `, PostgreSQL: "） */
    char identifierQuoteChar();

    /** 引用标识符（如果需要） */
    default String quoteIdentifier(String identifier) {
        char c = identifierQuoteChar();
        return c + identifier + c;
    }

    /** Java 类型到 SQL 类型的映射 */
    String sqlType(Class<?> javaType);

    /** 是否支持 INSERT ... ON DUPLICATE KEY UPDATE（MySQL UPSERT） */
    boolean supportsUpsert();

    /** 生成 UPSERT SQL */
    String upsert(String table, String[] columns, String[] uniqueColumns, String[] updateColumns);

    /** 是否支持批量 INSERT 多值 */
    boolean supportsBatchInsertValues();

    /** DROP INDEX 语法（MySQL 需要 ON table，其他不需要） */
    String dropIndex(String indexName, String tableName);

    /** RENAME TABLE 语法 */
    String renameTable(String oldName, String newName);

    /** 布尔类型的 SQL 类型表示 */
    String booleanType();

    /** 时间戳类型的 SQL 类型表示 */
    String timestampType();

    /** 批量 INSERT 语法（Oracle 用 INSERT ALL ... SELECT FROM DUAL） */
    default BatchInsertSyntax batchInsertSyntax() {
        return BatchInsertSyntax.VALUES_LIST;
    }
}
```

### 3.2 辅助枚举与类型

```java
/** 主键生成策略 */
public enum IdentityStrategy {
    AUTO_INCREMENT,    // MySQL: AUTO_INCREMENT
    SERIAL,            // PostgreSQL: SERIAL / BIGSERIAL
    GENERATED_ALWAYS,  // Oracle/PG: GENERATED ALWAYS AS IDENTITY
    IDENTITY           // H2: IDENTITY
}

/** 批量 INSERT 语法 */
public enum BatchInsertSyntax {
    VALUES_LIST,      // INSERT INTO t (...) VALUES (...), (...)
    INSERT_ALL_SELECT // Oracle: INSERT ALL INTO t ... SELECT * FROM DUAL
}
```

### 3.3 Dialect 自动检测

```java
package com.holo.framework.horm.core.dialect;

/**
 * 从 JDBC URL 自动检测数据库类型并返回对应 Dialect。
 */
public final class DialectDetector {

    /** 根据 JDBC URL 检测并返回 Dialect 实例 */
    public static Dialect detect(String jdbcUrl) {
        if (jdbcUrl.startsWith("jdbc:mysql")) return new MySqlDialect();
        if (jdbcUrl.startsWith("jdbc:postgresql")) return new PostgresDialect();
        if (jdbcUrl.startsWith("jdbc:h2")) return detectH2Mode(jdbcUrl);
        if (jdbcUrl.startsWith("jdbc:oracle")) return new OracleDialect();
        if (jdbcUrl.startsWith("jdbc:sqlite")) return new SqliteDialect();
        // 兜底 MySQL
        return new MySqlDialect();
    }

    private static Dialect detectH2Mode(String jdbcUrl) {
        if (jdbcUrl.contains("MODE=PostgreSQL")) return new H2Dialect("postgresql");
        return new H2Dialect("mysql"); // 默认 MySQL 兼容模式
    }
}
```

### 3.4 Dialect 注册到 HormContext

在 `HormContext` 中新增 `dialects` 映射，每个数据源绑定一个 Dialect：

```java
public final class HormContext {
    private final DataSourceRegistry registry;
    private final CacheChain cacheChain;
    private final Map<String, Dialect> dialects; // 新增：数据源 → 方言映射

    /** 获取指定数据源的方言 */
    public Dialect dialect(String dataSourceName) {
        return dialects.getOrDefault(dataSourceName, DialectDetector.detectFromProvider(...));
    }
}
```

Spring Boot Starter 自动配置时，从 `spring.datasource.<name>.jdbc-url` 自动检测 Dialect。

---

## 四、核心改造计划

### 4.1 holo-horm-core 改造

| 子任务 | 描述 | 改造范围 |
|--------|------|----------|
| D1 | `Dialect` 接口 + `IdentityStrategy` + `BatchInsertSyntax` 枚举 | 新增 `core.dialect` 包 |
| D2 | `MySqlDialect` 实现 | 新增 |
| D3 | `PostgresDialect` 实现 | 新增 |
| D4 | `H2Dialect` 实现（支持 MySQL/PostgreSQL 兼容模式） | 新增 |
| D5 | `DialectDetector` URL 自动检测 | 新增 |
| D6 | `HormContext` 增加 Dialect 映射 | 改造 |
| D7 | `QueryImpl` — 分页/limit 改用 `Dialect.paginate()` | 改造 |
| D8 | `JdbcRepository` — exists/save 改用 `Dialect` | 改造 |
| D9 | `UpdateQueryImpl`/`DeleteQueryImpl` — 分页改用 `Dialect` | 改造 |

### 4.2 holo-horm-migration 改造

| 子任务 | 描述 | 改造范围 |
|--------|------|----------|
| D10 | `SchemaRenderer` 对齐 `Dialect` 接口（或依赖 Dialect 获取类型映射） | 改造 |
| D11 | `PostgresSchemaRenderer` 实现 | 新增 |
| D12 | `H2SchemaRenderer` 支持 PostgreSQL 模式 | 改造 |

### 4.3 holo-horm-spring-boot-starter 改造

| 子任务 | 描述 | 改造范围 |
|--------|------|----------|
| D13 | `HormMultiDataSourceAutoConfiguration` 自动检测 Dialect 并注册 | 改造 |
| D14 | `HormAutoConfiguration` 默认数据源 Dialect 自动检测 | 改造 |

### 4.4 测试

| 子任务 | 描述 | 改造范围 |
|--------|------|----------|
| D15 | `DialectTest` — 各 Dialect 方法单元测试 | 新增 |
| D16 | `DialectDetectorTest` — URL 检测测试 | 新增 |
| D17 | PostgreSQL 集成测试（H2 MODE=PostgreSQL） | 新增 |
| D18 | 现有测试回归验证（确保 MySQL/H2 模式不受影响） | 验证 |

---

## 五、详细实现方案

### 5.1 D1: Dialect 接口定义

位置：`com.holo.framework.horm.core.dialect.Dialect`

设计原则：
- 接口方法覆盖 M1-M8 中所有硬编码方言差异
- 默认方法提供 MySQL 兼容实现，减少实现类代码量
- 与 migration 模块的 `SchemaRenderer` 共享类型映射（Dialect 提供 `sqlType()`，SchemaRenderer 消费）

### 5.2 D7: QueryImpl 分页改造

**改造前**：
```java
private void appendLimitOffset(StringBuilder sql, List<Object> bindings) {
    if (limit != null) {
        sql.append(" LIMIT ?");
        bindings.add(limit);
    }
    if (offset != null) {
        sql.append(" OFFSET ?");
        bindings.add(offset);
    }
}
```

**改造后**：
```java
private void appendLimitOffset(StringBuilder sql, List<Object> bindings) {
    Dialect dialect = ctx.dialect(dataSourceName);
    if (limit != null || offset != null) {
        long lim = limit != null ? limit : Long.MAX_VALUE;
        long off = offset != null ? offset : 0L;
        dialect.paginate(sql, bindings, off, lim);
    }
}
```

`Dialect.paginate()` 签名改为：
```java
void paginate(StringBuilder sql, List<Object> bindings, long offset, long limit);
```

MySQL/PG/H2 实现：
```java
default void paginate(StringBuilder sql, List<Object> bindings, long offset, long limit) {
    sql.append(" LIMIT ?");
    bindings.add(limit);
    if (offset > 0) {
        sql.append(" OFFSET ?");
        bindings.add(offset);
    }
}
```

Oracle 实现：
```java
@Override
public void paginate(StringBuilder sql, List<Object> bindings, long offset, long limit) {
    sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
    bindings.add(offset);
    bindings.add(limit);
}
```

### 5.3 D8: JdbcRepository 改造

`exists()` 方法改造：
```java
// 改造前
String sql = "SELECT 1 FROM " + qualifiedTable()
    + " WHERE " + idField.column() + " = ? LIMIT 1";

// 改造后
String sql = "SELECT 1 FROM " + qualifiedTable()
    + " WHERE " + idField.column() + " = ?";
sql = ctx.dialect(dataSourceName).paginate(sql, List.of(), 0, 1);
```

`save()` INSERT 路径改造 — PostgreSQL 可选使用 `RETURNING id` 替代 `RETURN_GENERATED_KEYS`（保持 `RETURN_GENERATED_KEYS` 兼容性，作为备选优化）。

### 5.4 D5: DialectDetector

支持从以下来源检测 Dialect：
1. **JDBC URL**（首选）：解析 `jdbc:mysql://`、`jdbc:postgresql://`、`jdbc:h2:`
2. **DataSourceProvider**：新增 `Dialect` 属性，由 Spring Boot 配置时注入
3. **显式配置**：`spring.datasource.<name>.dialect=postgresql` 手动指定

### 5.5 D11: PostgresSchemaRenderer

参考 `MySQLSchemaRenderer` 实现，关键差异：

| 方法 | MySQL | PostgreSQL |
|------|-------|-----------|
| `renderCreateTable()` | `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` | 无后缀 |
| `renderDropIndex()` | `DROP INDEX idx ON table` | `DROP INDEX idx` |
| `renderRenameTable()` | `RENAME TABLE old TO new` | `ALTER TABLE old RENAME TO new` |
| `renderRenameColumn()` | `CHANGE COLUMN` | `RENAME COLUMN` |
| `renderModifyColumn()` | `MODIFY COLUMN` | `ALTER COLUMN ... TYPE ...` |
| `renderColumnDefinition()` | `AUTO_INCREMENT` | `GENERATED ALWAYS AS IDENTITY` 或 `SERIAL` |
| `renderColumnType()` | `TINYINT(1)` for boolean | `BOOLEAN` |
| `renderColumnType()` | `DATETIME` | `TIMESTAMP` |
| `renderColumnDefinition()` | `COMMENT '...'` | 无 COMMENT（需用 `COMMENT ON` 单独语句） |

---

## 六、分批实施计划

### 批次 A：Dialect SPI + MySQL/H2（D1-D5, D6 部分）

**目标**：建立 Dialect 体系，现有功能零回归

| 任务 | 描述 | 预计 |
|------|------|------|
| D1 | `Dialect` 接口 + `IdentityStrategy` + `BatchInsertSyntax` | 核心接口 |
| D2 | `MySqlDialect` 实现 | 默认方言 |
| D4 | `H2Dialect` 实现 | 测试方言 |
| D5 | `DialectDetector` | URL 自动检测 |
| D16 | `DialectDetectorTest` | 检测测试 |
| D18 | 回归验证 | 全量 mvn verify |

### 批次 B：核心模块改造 + PostgreSQL 方言（D6-D9, D3）

**目标**：JdbcRepository/QueryImpl 通过 Dialect 生成 SQL，新增 PG 支持

| 任务 | 描述 | 预计 |
|------|------|------|
| D6 | `HormContext` 增加 Dialect 映射 | 上下文改造 |
| D7 | `QueryImpl` 分页改用 Dialect | 查询改造 |
| D8 | `JdbcRepository` exists/save 改用 Dialect | CRUD 改造 |
| D9 | `UpdateQueryImpl`/`DeleteQueryImpl` 分页改用 Dialect | 批量改造 |
| D3 | `PostgresDialect` 实现 | PG 方言 |
| D15 | `DialectTest` 各方言方法测试 | 方言测试 |
| D17 | H2 MODE=PostgreSQL 集成测试 | PG 兼容测试 |

### 批次 C：迁移模块 + Starter 集成（D10-D14）

**目标**：迁移渲染器统一到 Dialect 体系，Spring Boot 自动检测

| 任务 | 描述 | 预计 |
|------|------|------|
| D10 | `SchemaRenderer` 对齐 Dialect | 迁移改造 |
| D11 | `PostgresSchemaRenderer` | PG DDL |
| D12 | `H2Dialect` PostgreSQL 模式 | H2 PG |
| D13 | Starter 多数据源 Dialect 自动检测 | SB 配置 |
| D14 | Starter 默认数据源 Dialect | SB 配置 |

---

## 七、测试策略

### 7.1 单元测试

| 测试类 | 覆盖范围 |
|--------|----------|
| `MySqlDialectTest` | MySQL 方言各方法输出断言 |
| `PostgresDialectTest` | PostgreSQL 方言各方法输出断言 |
| `H2DialectTest` | H2 两种模式输出断言 |
| `DialectDetectorTest` | JDBC URL 检测逻辑 |
| `DialectTest` | 接口默认方法测试 |

### 7.2 集成测试

| 测试类 | 覆盖范围 |
|--------|----------|
| `PostgresDialectIntegrationTest` | H2 MODE=PostgreSQL 下 CRUD、分页、事务 |
| `MysqlDialectIntegrationTest` | 现有 H2 MODE=MySQL 测试回归 |
| `MigrationPostgresIntegrationTest` | PostgreSQL 模式下 DDL 渲染与迁移 |

### 7.3 回归验证

```bash
# 全模块回归
mvn -f holo-horm/pom.xml clean verify -Pskip-enforcer

# 单模块验证
mvn -pl holo-horm-meta,holo-horm-core,holo-horm-cache,holo-horm-migration -am verify -Pskip-enforcer
```

---

## 八、不在范围内

以下特性不在 M8.5 范围内，属于 M9 或后续版本：

- Oracle/SQLite/SQL Server 方言实现（仅定义接口，不提供实现）
- NoSQL 数据源适配（MongoDB/Redis/ES — 属于 `holo-horm-datasource` 模块规划）
- `DataSource` + `Session` + `QueryTranslator` + `Capabilities` 新版 SPI
- UPSERT 实现（仅定义接口方法，不实现具体逻辑）
- 运行时动态切换 Dialect
- 数据库连接池配置
