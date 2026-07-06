# M7 接续提示词：数据库迁移（Flyway 集成）

> 你是 HORM ORM 框架的开发者。请基于以下上下文继续实现 M7 里程碑。

## 项目概览

HORM 是一个 Java ORM 框架，核心设计原则：
- **Active Record + 零运行时反射**：APT 编译期生成伴随类，运行时热路径零反射
- **CRTP 模式**：`Model<T extends Model<T>>` 泛型自引用
- **多数据源 SPI**：`DataSourceRegistry` 管理命名数据源，`@Entity(dataSource = "name")` 驱动路由（M5 已完成）
- **composable cache chain**：M6 已落地，支持 L1（Caffeine）/ L2（Redis stub）多级缓存链式组合 + batch loading
- **数据库迁移**：M7 目标，集成 Flyway 实现版本化 schema 管理 + DSL 迁移脚本

## 仓库位置

```
e:\project\Holo\holo-horm
```

## 当前分支与状态

- **分支**：`feature/m6-cache`（M6 已完成，待 squash merge 到 main）
- **Tag**：`v1.0.0-M6`
- **构建验证命令**：`mvn -pl holo-horm-meta,holo-horm-core,holo-horm-cache -am verify -Pskip-enforcer`
- **测试总数**：708（meta 156 + cache 346 + core 206），全部通过
- **覆盖率**：meta 模块 84%，cache 模块 87%，core 模块 86%

## M6 交付物（已完成）

| Commit | 子任务 |
|--------|--------|
| `6e94270` | holo-horm-cache 全模块（Cache/CacheChain SPI、DefaultCacheChain、CaffeineCache、NoOpCache、RedisCache stub、Serializer、SingleFlightLoader、TtlJitter、缓存键） |
| `6e94270` | @Cached/@CachePolicy 注解 + APT 扩展（EntityDescriptor/EntityValidator/EntityMeta/MetaClassBuilder）+ R12 校验 |
| `6e94270` | ORM 缓存集成（HormContext 可选 CacheChain、TransactionManager afterCommit/afterRollback 钩子、Repository/Model.findMany、JdbcRepository 缓存路径、QueryImpl 查询缓存） |
| `6e94270` | H2 集成测试（FindManyCacheIntegrationTest、JdbcRepositoryCacheTest）+ PROGRESS.md + tag v1.0.0-M6 |

## 关键架构约束

1. **APT 生成类位置**：`<实体包>.generated`
2. **entities.idx 格式**：每行一个 `XxxMeta` 全限定类名，`#` 开头为注释
3. **EntityMetaRegistry** 通过 `ClassLoader.getResources("META-INF/horm/entities.idx")` 加载
4. **APT 合并语义**：写 `entities.idx` 前先读取已存在内容并去重
5. **EntityMetaRegistry.reload()** 必须为 public（测试钩子）
6. **H2 测试库**：`jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1`（Testcontainers Docker 禁用）
7. **JaCoCo 覆盖率**：processor 包 >80%，core 模块 >84%，cache 模块 >80%
8. **Conventional Commits**：`<type>(<scope>): <subject>`，scope 如 meta/core/datasource/cache/codegen/migration/starter/examples/benchmark/bom
9. **Raw type + @SuppressWarnings({"unchecked", "rawtypes"})**：CRTP 泛型变通模式
10. **JavaPoet $T vs $L**：`$T` 生成 import + 短名，`$L` 是字面量；长整型字面量用 `$LL` 加 L 后缀
11. **meta 模块无 Mockito 依赖**：测试中用真实对象或匿名内部类代替 mock()
12. **Connection 资源释放**：TransactionManager / HormContext / DataSourceProvider / JdbcRepository / queries 必须在 finally 块释放
13. **APT 嵌套注解解析**：使用 `AnnotationMirror` 手动遍历元素值，不可直接调用注解方法（会导致 "Incorrectly typed data found"）
14. **缓存默认关闭**：未标 `@Cached` 时 `cached()=false`，`HormContext.cacheChain()` 默认 null，M1-M5 行为零回归
15. **缓存写入在事务提交后执行**：`TransactionManager.afterCommit` 钩子确保一致性

## 已有关键文件

### 元数据层（holo-horm-meta）
- `com.holo.framework.horm.meta.annotation.Entity` — `dataSource()` / `table()` 属性
- `com.holo.framework.horm.meta.annotation.Cached` / `CachePolicy` / `CacheLevel` / `EvictionPolicy` / `WriteStrategy` — 缓存注解（M6）
- `com.holo.framework.horm.meta.CachePolicy` — meta 模块独立缓存策略类（避免 meta→cache 循环依赖）
- `com.holo.framework.horm.meta.EntityMeta` — 含 `dataSource()`/`cached()`/`cachePolicy()`/`cacheLevels()` 字段、`insertableColumns()`、`field(name)`/`fieldByColumn(column)` 查找
- `com.holo.framework.horm.meta.FieldMeta` — 含 `version()`/`generationStrategy()` 等字段属性
- `com.holo.framework.horm.meta.Mapper` — 接口含 `map`/`toRow`/`getId`/`setId`/`getField`/`setField` + 默认 `setRelation`/`getRelation`/`incrementVersion`
- `com.holo.framework.horm.meta.Row` — 数据源无关行抽象，含 `MapRow` 实现
- `com.holo.framework.horm.meta.processor.HormEntityProcessor` — APT 入口
- `com.holo.framework.horm.meta.processor.EntityDescriptor` / `EntityDescriptorParser` — 实体描述符与解析器（含缓存注解解析）
- `com.holo.framework.horm.meta.processor.EntityValidator` — R1-R12 编译期校验
- `com.holo.framework.horm.meta.processor.MetaClassBuilder` — `XxxMeta` 代码生成（含 CACHED/CACHE_POLICY/CACHE_LEVELS 常量）

### 核心层（holo-horm-core）
- `com.holo.framework.horm.core.HormContext` — 持有 `DataSourceRegistry` + 可选 `CacheChain`，提供 `getDataSource(name)`/`getDataSourceForEntity(Class)`/`cacheChain()`
- `com.holo.framework.horm.core.Horm` — 入口类，`install(HormContext)` / `install(DataSourceProvider)` / `install(name, provider)` + `repository(Class)` + `tx(Callable)`
- `com.holo.framework.horm.core.EntityMetaRegistry` — 元数据注册表
- `com.holo.framework.horm.core.JdbcRepository` — CRUD 实现 + 缓存集成 + findMany 批量加载
- `com.holo.framework.horm.core.TransactionManager` — 按数据源独立事务栈 + afterCommit/afterRollback 钩子
- `com.holo.framework.horm.core.TransactionStatus` — 事务状态 + afterCommitCallbacks/afterRollbackCallbacks + transferCallbacksTo（嵌套事务回调传播）
- `com.holo.framework.horm.core.Model<T>` — Active Record 基类 + `findMany(Class, Collection)` 静态方法
- `com.holo.framework.horm.core.Repository<T>` — 接口 + `findMany(Collection<ID>)` 默认方法
- `com.holo.framework.horm.core.query.Query` / `QueryImpl` — 查询构建器 + 可选查询缓存（仅 THROUGH 模式）
- `com.holo.framework.horm.core.datasource.DataSourceRegistry` — 多数据源注册表

### 缓存层（holo-horm-cache，M6 已实现）
- `Cache` / `CacheChain` — 缓存 SPI 接口
- `CachePolicy` / `CachePolicyBuilder` — 缓存策略配置
- `DefaultCacheChain` — 多级链式实现（逐层查找、上层回填、批量加载、事件发布、NullMarker 空值缓存）
- `CaffeineCache` — L1 本地缓存（零序列化、TTL/size 淘汰、removalListener 事件转发）
- `NoOpCache` — 无操作兜底实现
- `RedisCache` — L2 Redis stub（Redisson optional）
- `Serializer` / `JdkSerializer` — 序列化 SPI
- `SingleFlightLoader` — 单飞加载（防击穿）
- `TtlJitter` — TTL 抖动（防雪崩）
- `NullMarker` — 空值缓存标记
- `CacheKey` / `CacheKeyBuilder` / `QueryHash` / `SensitiveHash` — 缓存键设计
- `CacheEvent` / `CacheEventListener` / `CacheEventType` / `CacheStats` / `TypeReference` — 事件与统计
- `CacheLoadException` / `CacheException` — 异常类

## M7 目标：数据库迁移（Flyway 集成）

### 设计参考

完整设计文档：`docs/06-extension-features.md` 第三章「数据库迁移工具」（必读，包含 Migration DSL、SQL 文件迁移、命令行、自动迁移、多数据源迁移、校验和等）

### 需要实现的功能

1. **Migration 基类**：`Migration` 抽象类 + `up(Schema)` / `down(Schema)` 方法
2. **Schema DSL**：`Schema` 接口提供 `createTable`/`alterTable`/`dropTable`/`createIndex`/`dropIndex` 等 Rails 风格 DSL
3. **TableBuilder**：链式列定义（`bigIncrements`/`string`/`integer`/`timestamps`/`notNull`/`unique`/`defaultVal`/`references` 等）
4. **Flyway 集成**：`FlywayMigrationRunner` 封装 Flyway 引擎，支持 `migrate()`/`rollback()`/`status()`
5. **多数据源迁移**：每个数据源独立 `schema_migrations` 表，按 `DataSourceRegistry` 分组执行
6. **SQL 文件迁移**：支持 `V{n}__{description}.sql` / `V{n}__{description}.down.sql` 直写 SQL
7. **校验和验证**：SHA-256 checksum 校验已应用迁移脚本未被修改
8. **Horm 集成**：`Horm.migrate()` / `Horm.migrate(String dataSourceName)` 入口
9. **命令行工具**：`migrate`/`rollback`/`status`/`make` 命令（留 CLI 入口 stub，M8 Spring Boot Starter 完整集成）
10. **H2 测试**：迁移脚本在 H2 上验证 up/down 幂等性

### 建议的子任务拆分

| 子任务 | 描述 |
|--------|------|
| M7-1 | 创建 `holo-horm-migration` 模块 + pom.xml（Flyway core 依赖） |
| M7-2 | 定义 Migration SPI：`Migration` 抽象类 + `Schema` 接口 + `TableBuilder` + `ColumnBuilder` DSL |
| M7-3 | 实现 `Schema` 渲染器：`H2SchemaRenderer` / `MySQLSchemaRenderer` 将 DSL 转为 DDL SQL |
| M7-4 | 集成 Flyway：`FlywayMigrationRunner` 封装 Flyway 引擎，支持 Java + SQL 双格式迁移 |
| M7-5 | 多数据源迁移：`MigrationRunner` 接口 + `DataSourceRegistry` 分组执行 |
| M7-6 | 校验和验证：`MigrationChecksum` SHA-256 校验 + `MigrationChecksumException` |
| M7-7 | Horm 集成：`Horm.migrate()` / `Horm.migrate(String)` 入口 + `HormContext` 扩展 |
| M7-8 | 命令行 stub：`MigrationCommand` 接口 + `MigrateCommand`/`RollbackCommand`/`StatusCommand` |
| M7-9 | H2 集成测试 + 覆盖率检查 + PROGRESS.md + tag v1.0.0-M7 |

### 需要回答的设计决策

1. **Flyway vs 自研**：M7 采用 Flyway 作为迁移引擎核心（成熟、生产级、Spring Boot 原生集成），在其上层提供 HORM 风格的 DSL 包装。不重新实现迁移版本管理、checksum、baseline 等 Flyway 已有的能力。
2. **Migration DSL vs 纯 SQL**：优先支持 Flyway 原生的 Java/SQL 迁移格式；HORM 的 `Migration` + `Schema` DSL 作为便捷 API 封装在 Flyway `JavaMigration` 之上，生成 SQL 交由 Flyway 执行。
3. **模块边界**：`holo-horm-migration` 为新模块，依赖 `holo-horm-core`（获取 DataSourceRegistry）+ `flyway-core`（迁移引擎）。不依赖 cache/meta 模块。
4. **H2 兼容性**：Migration DSL 生成的 DDL 需同时兼容 H2（MODE=MySQL）和真实 MySQL；类型映射由 `SchemaRenderer` 处理。
5. **baseline 支持**：首次在已有数据库上启用迁移时，Flyway baseline 避免重复执行历史迁移。
6. **多数据源隔离**：每个数据源独立的 Flyway 实例 + `schema_migrations` 表；默认数据源无需指定名称。
7. **rollback 范围**：Flyway 社区版不支持 undo migration；M7 的 `down()` 仅在测试中使用，生产环境 rollback 需 Flyway Pro/Enterprise 或手动 SQL。需在文档中明确此限制。

## 启动步骤

1. 从 `feature/m6-cache` 创建新分支 `feature/m7-migration`
2. 必读 `docs/06-extension-features.md` 第三章「数据库迁移工具」
3. 阅读 `HormContext`、`Horm`、`DataSourceRegistry` 当前实现（迁移需要访问数据源）
4. 按 M7-1 → M7-9 顺序实施
5. 每个子任务完成后运行 `mvn -pl holo-horm-meta,holo-horm-core,holo-horm-cache,holo-horm-migration -am verify -Pskip-enforcer` 验证
6. 完成后更新 `docs/PROGRESS.md` 并打 tag `v1.0.0-M7`

## 多 agent 并行建议

可并行的子任务：
- M7-2（Migration SPI）与 M7-3（Schema 渲染器）可并行
- M7-4（Flyway 集成）与 M7-8（命令行 stub）可并行（均依赖 M7-2）
- M7-5（多数据源）依赖 M7-4，M7-6（校验和）可独立
- M7-7（Horm 集成）依赖 M7-5，M7-9（测试+文档）依赖所有前置

覆盖率目标：migration 模块 >80%，core 模块保持 >84%，cache 模块保持 >80%，meta 模块保持 >80%。未达目标不结束。
