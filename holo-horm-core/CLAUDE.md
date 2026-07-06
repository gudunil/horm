[根目录](../../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **holo-horm-core**

# Holo :: HORM :: Core (核心 API)

## 模块职责

HORM 框架运行时核心：提供 Active Record 基类 (`Model<T>`)、Repository 接口、JDBC 实现、类型安全查询 DSL、事务管理、级联操作、乐观锁、缓存集成及多数据源路由。

- **parent**: `holo-horm` (1.0.0-SNAPSHOT)
- **artifactId**: `holo-horm-core`
- **packaging**: jar

## 子包结构

```
com.holo.framework.horm.core
  ├── Horm                    进程级入口：install() / repository() / tx() / migrate()
  ├── HormContext              运行时上下文（持有 DataSourceRegistry + CacheChain）
  ├── EntityMetaRegistry       元数据注册表（启动时加载 entities.idx）
  ├── Repository<T>            CRUD 仓库接口
  ├── JdbcRepository<T>        JDBC 实现（PreparedStatement CRUD + 缓存集成）
  ├── Model<T>                 Active Record 基类（CRTP 模式）
  ├── DataSourceProvider       数据源 SPI（getConnection / releaseConnection）
  ├── SimpleDataSourceProvider  简单数据源实现
  ├── TransactionManager       事务管理器（按数据源独立事务栈）
  ├── TransactionDefinition    事务定义（Propagation / Isolation）
  ├── TransactionStatus        事务状态（含 afterCommit / afterRollback 回调）
  ├── MigrationExecutor        迁移执行 SPI（ServiceLoader 发现）
  ├── HormException            框架异常基类
  ├── OptimisticLockException  乐观锁异常
  ├── query/
  │   ├── Query<T>             查询 DSL 接口（where/and/or/orderBy/limit/offset/fetch/join/select）
  │   ├── QueryImpl            查询 DSL JDBC 实现
  │   ├── UpdateQuery          批量更新 DSL
  │   ├── UpdateQueryImpl      批量更新 JDBC 实现
  │   ├── DeleteQuery          批量删除 DSL
  │   ├── DeleteQueryImpl      批量删除 JDBC 实现
  │   └── Order                排序方向枚举
  └── datasource/
      └── DataSourceRegistry   多数据源注册表（线程安全）
```

## 对外接口

### 入口 API

| 方法 | 说明 |
|------|------|
| `Horm.install(HormContext)` | 安装运行时上下文 |
| `Horm.install(DataSourceProvider)` | 安装默认数据源 |
| `Horm.repository(Class)` | 获取实体的 Repository |
| `Horm.tx(Runnable)` | 编程式事务 |
| `Horm.tx(Callable<T>)` | 编程式事务（可返回结果） |
| `Horm.install(String, DataSourceProvider)` | 注册命名数据源 |
| `Horm.migrate()` | 执行数据库迁移 |

### Model (Active Record)

| 方法 | 说明 |
|------|------|
| `entity.save()` | 插入或更新（自动级联） |
| `entity.delete()` | 按主键删除（自动级联） |
| `entity.reload()` | 从数据源重新读取 |
| `Model.find(Class, id)` | 按主键查找 |
| `Model.findMany(Class, Collection)` | 批量查找（缓存集成） |
| `Model.all(Class)` | 查询全部 |
| `Model.count(Class)` | 计数 |
| `Model.query(Class)` | 流畅查询 DSL |
| `Model.update(Class)` | 批量更新 DSL |
| `Model.delete(Class)` | 批量删除 DSL |

### Query DSL 方法

| 方法 | 说明 |
|------|------|
| `.where(Condition...)` | 条件过滤 |
| `.and(Condition...)` | 追加条件（AND） |
| `.or(Condition...)` | 追加条件（OR） |
| `.orderBy(field, direction)` | 排序 |
| `.limit(n)` | 行数限制 |
| `.offset(n)` | 偏移 |
| `.fetch(RelationField)` | LEFT JOIN 关联预加载 |
| `.leftJoin(RelationField)` | LEFT JOIN |
| `.innerJoin(RelationField)` | INNER JOIN |
| `.select(TypedField...)` | 字段投影 |
| `.list()` | 返回 List |
| `.findFirst()` | 返回 Optional |
| `.count()` | 计数查询 |
| `.exists()` | 存在性检查 |

### Repository 接口

| 方法 | 说明 |
|------|------|
| `find(Object id)` | 按主键查找 |
| `findMany(Collection<Object>)` | 批量查找 |
| `all()` | 查询全部 |
| `count()` | 计数 |
| `exists(Object id)` | 存在性检查 |
| `save(T entity)` | 插入或更新 |
| `delete(T entity)` | 删除实体 |
| `deleteById(Object id)` | 按主键删除 |

## 关键依赖与配置

- **依赖**: `holo-horm-meta` (compile) + `holo-horm-cache` (compile) + H2 (test)
- **核心依赖**: meta 模块 APT 生成的 EntityMeta/Mapper 驱动运行时
- **缓存集成**: 通过 HormContext.cacheChain() 可选集成，默认关闭

### 事务支持

- 编程式事务：`Horm.tx(Runnable/Callable)`
- 传播级别: REQUIRED / REQUIRES_NEW
- 超时支持: 通过 TransactionDefinition 设置
- 钩子: afterCommit / afterRollback 回调保障缓存一致性

## 数据模型

### HormContext（运行时上下文）

| 字段 | 类型 | 说明 |
|------|------|------|
| connection | Connection | 遗留单连接（可选） |
| registry | DataSourceRegistry | 多数据源注册表 |
| cacheChain | CacheChain | 可选缓存链（null 表示禁用） |

## 测试与质量

- **测试数量**: 206
- **覆盖目标**: core 模块整体 > 84%
- **验证命令**: `mvn -pl holo-horm-meta,holo-horm-core,holo-horm-cache -am verify -Pskip-enforcer`
- **H2 测试库**: `jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1`
- **关键测试类**: `JdbcRepositoryTest`、`QueryImplTest`、`HormTest`、`TransactionManagerTest`、`UserCrudTest`、`FindManyCacheIntegrationTest`

## 相关文件清单

| 文件路径 | 说明 |
|---------|------|
| `holo-horm/holo-horm-core/src/main/java/com/holo/framework/horm/core/Horm.java` | 入口类 |
| `holo-horm/holo-horm-core/src/main/java/com/holo/framework/horm/core/Model.java` | Active Record 基类 |
| `holo-horm/holo-horm-core/src/main/java/com/holo/framework/horm/core/JdbcRepository.java` | JDBC CRUD 实现 |
| `holo-horm/holo-horm-core/src/main/java/com/holo/framework/horm/core/HormContext.java` | 运行时上下文 |
| `holo-horm/holo-horm-core/src/main/java/com/holo/framework/horm/core/TransactionManager.java` | 事务管理器 |
| `holo-horm/holo-horm-core/src/main/java/com/holo/framework/horm/core/query/Query.java` | 查询 DSL 接口 |

## 常见问题 (FAQ)

### 1. 提示 "HormContext not installed"
在操作之前调用 `Horm.install(provider)` 安装上下文。

### 2. JdbcRepository CRUD 报 SQLException
检查实体 `@Entity(table = ...)` 对应的表是否在目标数据源中存在。

### 3. 乐观锁失败抛出 OptimisticLockException
并发更新导致版本号冲突，重试或提示用户。

### 4. 缓存不生效
确保实体标注了 `@Cached` 且在 HormContext 构造时传入了非空 CacheChain。

## 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-07-06 | 初始文档生成 | 架构扫描 |