# M7 收尾 + M8 启动接续提示词

> 你是 HORM ORM 框架的开发者。M7 主体已实现并提交，本提示词覆盖 M7 收尾工作与 M8 启动准备。

## 项目概览

HORM 是一个 Java ORM 框架，核心设计原则：
- **Active Record + 零运行时反射**：APT 编译期生成伴随类，运行时热路径零反射
- **CRTP 模式**：`Model<T extends Model<T>>` 泛型自引用
- **多数据源 SPI**：`DataSourceRegistry` 管理命名数据源，`@Entity(dataSource = "name")` 驱动路由（M5 已完成）
- **composable cache chain**：M6 已落地，支持 L1（Caffeine）/ L2（Redis stub）多级缓存链式组合 + batch loading
- **数据库迁移**：M7 主体已完成，集成 Flyway 实现版本化 schema 管理 + DSL 迁移脚本

## 仓库位置

```
e:\project\Holo\holo-horm
```

## 当前分支与状态

- **分支**：`feature/m7-migration`
- **最近提交**：`a743e8b feat(migration): implement M7 migration module with Flyway integration`
- **已落地**：52 文件 / +4058 行；迁移模块 99 个测试通过，指令覆盖率 85.6%
- **构建验证命令**：`mvn -pl holo-horm-migration -am verify -Pskip-enforcer`
- **全量验证命令**：`mvn -pl holo-horm-meta,holo-horm-core,holo-horm-cache,holo-horm-migration -am verify -Pskip-enforcer`
- **未完成项**：`docs/PROGRESS.md` 未更新；`v1.0.0-M7` tag 未打；`feature/m7-migration` 未 squash merge 到 main

## M7 已交付物

| Commit | 子任务 |
|--------|--------|
| `a743e8b` | `MigrationExecutor` SPI（core 模块）+ `Horm.migrate()` / `Horm.migrate(String)` 公共 API + `DataSourceRegistry.entries()` |
| `a743e8b` | `holo-horm-migration` 全模块：`FlywayMigrationRunner` / `FlywayMigrationConfig`（Builder）/ `MultiDataSourceMigrationRunner` |
| `a743e8b` | Rails 风格 Schema DSL：`Schema` / `TableBuilder` / `ColumnBuilder` / `Migration` 抽象类 |
| `a743e8b` | 双方言 DDL 渲染：`H2SchemaRenderer` / `MySQLSchemaRenderer` + `SchemaRenderer` SPI |
| `a743e8b` | CLI 命令 stub：`MigrateCommand` / `StatusCommand` / `RollbackCommand` / `MakeCommand` + `MigrationCommands` 注册表 |
| `a743e8b` | `MigrationChecksum`（CRC32，与 Flyway 一致）+ `MigrationChecksumException` |
| `a743e8b` | SPI 注册文件 `META-INF/services/com.holo.framework.horm.core.MigrationExecutor` |
| `a743e8b` | 99 个测试（单元 + H2 集成），migration 模块指令覆盖率 85.6% |

### M7 代码审查修复点（已包含在 `a743e8b`）

- 🐛 `MigrateCommand` / `StatusCommand` 运行时崩溃修复（原 `Connection.unwrap(DataSource.class)` 必抛 `SQLException`）
- 🐛 `HormMigrationExecutor` 资源缓存（避免每次调用重建 `FlywayMigrationConfig` / `MultiDataSourceMigrationRunner`）
- 🐛 `Horm.migrate(String)` 检查顺序优化（先校验上下文，再校验数据源名）
- ♻️ `NonCloseableConnection` 提取为共享类（消除 `internal` 包与测试中的重复定义）
- ✨ 公共配置注入：`Horm.configureMigration(FlywayMigrationConfig)` 支持自定义表名、迁移位置、Java Migration、基线版本

## M7 剩余收尾工作（M7-9 子任务）

> 以下三项必须在声明 M7 完成前做完。

### 1. 更新 `docs/PROGRESS.md`

- 将 M7 行的状态从「2026 Q4」更新为「已完成（2026-07-06）」
- 在「接续点」章节移除 M7 启动说明，替换为 M8 启动说明（见本文末「启动步骤」）
- 新增 M7 交付摘要段落（参考上文「M7 已交付物」表格）
- 记录迁移模块的测试总数与覆盖率（99 测试 / 85.6%）

### 2. 打 tag `v1.0.0-M7`

```bash
git -C e:\project\Holo\holo-horm tag -a v1.0.0-M7 -m "M7: Flyway migration integration"
```

### 3. squash merge 到 main（可选，遵循团队约定）

```bash
git -C e:\project\Holo\holo-horm checkout main
git -C e:\project\Holo\holo-horm merge --squash feature/m7-migration
git -C e:\project\Holo\holo-horm commit -m "feat(m7): squash merge Flyway migration integration"
```

## 关键架构约束

1. **APT 生成类位置**：`<实体包>.generated`
2. **entities.idx 格式**：每行一个 `XxxMeta` 全限定类名，`#` 开头为注释
3. **EntityMetaRegistry** 通过 `ClassLoader.getResources("META-INF/horm/entities.idx")` 加载
4. **APT 合并语义**：写 `entities.idx` 前先读取已存在内容并去重
5. **EntityMetaRegistry.reload()** 必须为 public（测试钩子）
6. **H2 测试库**：`jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1`（Testcontainers Docker 禁用）
7. **JaCoCo 覆盖率**：processor 包 >80%，core 模块 >84%，cache 模块 >80%，migration 模块 >80%（实测 85.6%）
8. **Conventional Commits**：`<type>(<scope>): <subject>`，scope 如 meta/core/datasource/cache/codegen/migration/starter/examples/benchmark/bom
9. **Raw type + @SuppressWarnings({"unchecked", "rawtypes"})**：CRTP 泛型变通模式
10. **JavaPoet $T vs $L**：`$T` 生成 import + 短名，`$L` 是字面量；长整型字面量用 `$LL` 加 L 后缀
11. **meta 模块无 Mockito 依赖**：测试中用真实对象或匿名内部类代替 mock()
12. **Connection 资源释放**：TransactionManager / HormContext / DataSourceProvider / JdbcRepository / queries / MultiDataSourceMigrationRunner 必须在 finally 块释放
13. **APT 嵌套注解解析**：使用 `AnnotationMirror` 手动遍历元素值，不可直接调用注解方法
14. **缓存默认关闭**：未标 `@Cached` 时 `cached()=false`，`HormContext.cacheChain()` 默认 null
15. **缓存写入在事务提交后执行**：`TransactionManager.afterCommit` 钩子确保一致性
16. **Migration SPI 解耦**：core 模块仅定义 `MigrationExecutor` 接口，迁移实现通过 `ServiceLoader` 发现；core 不依赖 migration 模块
17. **Flyway 连接适配**：`ConnectionDataSource` 包装单 `Connection` 为 `DataSource`，`NonCloseableConnection` 防止 Flyway 关闭调用方持有的连接
18. **多数据源迁移隔离**：每个数据源独立 Flyway 实例 + 独立 `flyway_schema_history` 表

## 已有关键文件

### 元数据层（holo-horm-meta）
- `com.holo.framework.horm.meta.annotation.Entity` — `dataSource()` / `table()` 属性
- `com.holo.framework.horm.meta.annotation.Cached` / `CachePolicy` / `CacheLevel` / `EvictionPolicy` / `WriteStrategy` — 缓存注解（M6）
- `com.holo.framework.horm.meta.CachePolicy` — meta 模块独立缓存策略类（避免 meta→cache 循环依赖）
- `com.holo.framework.horm.meta.EntityMeta` — 含 `dataSource()`/`cached()`/`cachePolicy()`/`cacheLevels()` 字段
- `com.holo.framework.horm.meta.processor.HormEntityProcessor` — APT 入口

### 核心层（holo-horm-core）
- `com.holo.framework.horm.core.HormContext` — 持有 `DataSourceRegistry` + 可选 `CacheChain`
- `com.holo.framework.horm.core.Horm` — 入口类：`install(...)` / `repository(Class)` / `tx(...)` / `migrate()` / `migrate(String)` / `configureMigration(FlywayMigrationConfig)`
- `com.holo.framework.horm.core.MigrationExecutor` — 迁移 SPI 接口（ServiceLoader 发现）
- `com.holo.framework.horm.core.EntityMetaRegistry` — 元数据注册表
- `com.holo.framework.horm.core.JdbcRepository` — CRUD 实现 + 缓存集成 + findMany 批量加载
- `com.holo.framework.horm.core.TransactionManager` — 按数据源独立事务栈 + afterCommit/afterRollback 钩子
- `com.holo.framework.horm.core.datasource.DataSourceRegistry` — 多数据源注册表（含 `entries()` 迭代器）

### 缓存层（holo-horm-cache，M6 已实现）
- `Cache` / `CacheChain` / `DefaultCacheChain` / `CaffeineCache` / `NoOpCache` / `RedisCache` stub
- `CachePolicy` / `CachePolicyBuilder` / `SingleFlightLoader` / `TtlJitter` / `NullMarker`
- `CacheKey` / `CacheKeyBuilder` / `QueryHash` / `SensitiveHash`

### 迁移层（holo-horm-migration，M7 已实现）
- `Migration` 抽象类 — `up(Schema)` / `down(Schema)`（down 仅测试用，Flyway 社区版不支持 undo）
- `Schema` / `TableBuilder` / `ColumnBuilder` — Rails 风格 DSL 接口
- `FlywayMigrationRunner` — Flyway 引擎封装（`migrate()` / `status()`）
- `FlywayMigrationConfig` — Builder 配置（表名 / 位置 / Java Migration / baseline）
- `MultiDataSourceMigrationRunner` — 按数据源分组执行迁移
- `HormMigrationExecutor` — `MigrationExecutor` SPI 实现（ServiceLoader 注册）
- `MigrationCommands` / `MigrateCommand` / `StatusCommand` / `RollbackCommand` / `MakeCommand` — CLI stub
- `MigrationChecksum` — CRC32 校验和（与 Flyway 默认一致）
- `internal.DdlSchema` — DSL 语句收集器
- `internal.H2SchemaRenderer` / `MySQLSchemaRenderer` — 双方言 DDL 渲染
- `internal.ConnectionDataSource` / `NonCloseableConnection` — Flyway 连接适配
- `internal.DefaultTableBuilder` / `DefaultColumnBuilder` / `ColumnDefinition` / `TableDefinition` / `ForeignKeyDefinition`

## M8 预览：Spring Boot Starter + @Transactional AOP

> 待 M7 收尾完成后启动。以下为预研提示，正式提示词在 M8 启动时细化。

### 目标

1. `holo-horm-spring-boot-starter`：Spring Boot 自动装配
   - `HormAutoConfiguration`：从 `spring.datasource.*` 自动构造 `DataSourceProvider` + `HormContext`
   - `@ConditionalOnClass` / `@ConditionalOnMissingBean` 保证可选依赖
   - `@EnableHorm` 注解作为开关
   - 自动扫描 `@Entity` 类并触发 APT 元数据加载
2. `@Transactional` 运行时 AOP 代理织入
   - 替代 M4 的编译期 `TransactionAdvisor`（APT 生成）方案？还是两者共存？需设计决策
   - 基于 Spring AOP / AspectJ
   - 支持传播行为、隔离级别、只读、超时
3. `@Cached` 运行时织入（可选）：M6 的缓存注解在 Spring 环境下通过 AOP 拦截
4. Flyway 自动迁移：Spring Boot 启动时自动调用 `Horm.migrate()`（通过 `ApplicationRunner` 或 `CommandLineRunner`）
5. 多数据源自动配置：`spring.datasource.primary` + `spring.datasource.<name>.*` 映射到 `DataSourceRegistry`

### 需要回答的设计决策

1. **APT vs 运行时 AOP**：M4 的 `@Transactional` 通过 APT 生成代理子类；M8 是否改为 Spring AOP？需保证 M1-M7 不回归
2. **Starter 依赖边界**：starter 依赖 core / cache / migration，但不依赖 meta（meta 是编译期工具）
3. **自动迁移时机**：启动时自动 migrate vs 显式调用？是否提供 `holo.horm.auto-migrate` 开关
4. **条件装配**：当 classpath 无 Flyway 时跳过迁移自动配置
5. **与 Spring 事务管理器集成**：是否暴露 `PlatformTransactionManager` 包装 HORM 的 `TransactionManager`

## 启动步骤

1. **先完成 M7 收尾**：
   - 更新 `docs/PROGRESS.md`（M7 状态改为已完成，接续点改为 M8）
   - 打 tag `v1.0.0-M7`
   - 可选：squash merge `feature/m7-migration` 到 main
2. **启动 M8**：
   - 从 `main`（或 `feature/m7-migration` 合并后的 main）创建新分支 `feature/m8-starter`
   - 必读 `docs/06-extension-features.md` 中 Spring Boot Starter 相关章节
   - 阅读 `HormContext` / `Horm` / `TransactionManager` / `TransactionDefinition` 当前实现
   - 创建 `holo-horm-spring-boot-starter` 模块骨架（POM 已存在）
3. **验证**：每个子任务完成后运行 `mvn -pl holo-horm-spring-boot-starter -am verify -Pskip-enforcer`
4. **完成**：更新 `docs/PROGRESS.md` 并打 tag `v1.0.0-M8`

## 多 agent 并行建议（M8）

可并行的子任务（待拆分细化）：
- Starter 自动配置与 AOP 织入可并行（不同包）
- 多数据源自动配置与 Flyway 自动迁移可并行
- 条件装配测试与端到端集成测试在主体完成后并行

覆盖率目标：starter 模块 >80%，core/cache/migration/meta 模块保持已有水平。未达目标不结束。
