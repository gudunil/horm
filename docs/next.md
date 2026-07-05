# M5 接续提示词：多数据源 SPI 与路由

> 你是 HORM ORM 框架的开发者。请基于以下上下文继续实现 M5 里程碑。

## 项目概览

HORM 是一个 Java ORM 框架，核心设计原则：
- **Active Record + 零运行时反射**：APT 编译期生成伴随类，运行时热路径零反射
- **CRTP 模式**：`Model<T extends Model<T>>` 泛型自引用
- **多数据源 SPI**：`DataSourceProvider` 接口已就位，M5 扩展为多数据源路由
- **composable cache chain**：留待 M6

## 仓库位置

```
e:\project\Holo\holo-horm
```

## 当前分支与状态

- **分支**：`feature/m4-transactions`（M4 已完成，待 squash merge 到 main）
- **Tag**：`v1.0.0-M4`
- **构建验证命令**：`mvn -pl holo-horm-meta,holo-horm-core -am verify -Pskip-enforcer`
- **测试总数**：145（meta 66 + core 79），全部通过

## M4 交付物（已完成）

| Commit | 子任务 |
|--------|--------|
| `9f9e92a` | DataSourceProvider SPI + HormContext 重构 |
| `5a87281` | TransactionManager + @Transactional APT AOP（REQUIRED/REQUIRES_NEW 传播） |
| `fbe7208` | 批量 UPDATE/DELETE + Cascade 级联（CascadeType + saveWith/deleteWith） |
| `841b899` | @Version 乐观锁（OptimisticLockException + JdbcRepository WHERE version=?） |

## 关键架构约束

1. **APT 生成类位置**：`<实体包>.generated`
2. **entities.idx 格式**：每行一个 `XxxMeta` 全限定类名，`#` 开头为注释
3. **EntityMetaRegistry** 通过 `ClassLoader.getResources("META-INF/horm/entities.idx")` 加载
4. **APT 合并语义**：写 `entities.idx` 前先读取已存在内容并去重
5. **EntityMetaRegistry.reload()** 必须为 public（测试钩子）
6. **H2 测试库**：`jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1`（Testcontainers Docker 禁用）
7. **JaCoCo 覆盖率**：processor 包 >80%，core 模块 >84%
8. **Conventional Commits**：`<type>(<scope>): <subject>`，scope 如 meta/core/datasource
9. **Raw type + @SuppressWarnings({"unchecked", "rawtypes"})**：CRTP 泛型变通模式
10. **JavaPoet $T vs $L**：`$T` 生成 import + 短名，`$L` 是字面量

## 已有关键文件

### 元数据层（holo-horm-meta）
- `com.holo.framework.horm.meta.annotation.Entity` — `dataSource()` 属性已预留
- `com.holo.framework.horm.meta.EntityMeta` — `dataSource()` 字段已存在
- `com.holo.framework.horm.meta.processor.EntityDescriptor` / `EntityDescriptorParser` — 已解析 `dataSource`
- `com.holo.framework.horm.meta.processor.MetaClassBuilder` — 已生成 `.dataSource()` 调用
- `com.holo.framework.horm.meta.processor.HormEntityProcessor` — APT 入口
- `com.holo.framework.horm.meta.processor.EntityValidator` — R1-R11 校验规则
- `com.holo.framework.horm.meta.processor.TypeMapper` — 类型映射表
- `com.holo.framework.horm.meta.processor.MapperBuilder` — Mapper 代码生成
- `com.holo.framework.horm.meta.processor.QueryMetaBuilder` — QueryMeta 代码生成

### 核心层（holo-horm-core）
- `com.holo.framework.horm.core.DataSourceProvider` — 当前仅 `Connection getConnection()` 接口
- `com.holo.framework.horm.core.SimpleDataSourceProvider` — 简单实现（单 Connection）
- `com.holo.framework.horm.core.HormContext` — 当前持有一个 DataSourceProvider
- `com.holo.framework.horm.core.Horm` — 入口类，`install(HormContext)` + `repository(Class)` + `tx(Callable)`
- `com.holo.framework.horm.core.EntityMetaRegistry` — 元数据注册表
- `com.holo.framework.horm.core.JdbcRepository` — CRUD 实现，使用 `TransactionManager.currentConnection(ctx)`
- `com.holo.framework.horm.core.TransactionManager` — ThreadLocal Deque 事务栈
- `com.holo.framework.horm.core.Model<T>` — Active Record 基类
- `com.holo.framework.horm.core.query.Query` / `QueryImpl` — 查询构建器
- `com.holo.framework.horm.core.query.UpdateQuery` / `UpdateQueryImpl` — 批量更新
- `com.holo.framework.horm.core.query.DeleteQuery` / `DeleteQueryImpl` — 批量删除
- `com.holo.framework.horm.core.OptimisticLockException` — 乐观锁异常

## M5 目标：多数据源 SPI 与路由

### 需要实现的功能

1. **多数据源注册**：`Horm.install(String name, DataSourceProvider)` 注册命名数据源
2. **默认数据源**：`Horm.install(DataSourceProvider)` 注册默认数据源（名称为 `"default"`）
3. **实体→数据源路由**：
   - `@Entity(dataSource = "secondary")` 指定实体使用非默认数据源
   - `EntityMeta.dataSource()` 已有此字段，M5 只需运行时路由
4. **HormContext 多数据源**：
   - `HormContext` 改为持有多数据源映射（`Map<String, DataSourceProvider>`）
   - `HormContext.current()` 返回当前上下文
   - `HormContext.getDataSource(String name)` 按名称获取 DataSourceProvider
   - `HormContext.getDataSourceForEntity(Class<?>)` 根据实体 @Entity.dataSource 自动路由
5. **JdbcRepository 路由**：
   - `JdbcRepository` 构造时根据 `EntityMeta.dataSource()` 选择对应的 DataSourceProvider
   - `TransactionManager` 事务栈需区分数据源（每个数据源独立事务栈）
6. **TransactionManager 多数据源**：
   - `TransactionManager.currentConnection(ctx, String dataSourceName)` 按数据源获取连接
   - 每个数据源有独立的 ThreadLocal Deque 事务栈
7. **查询构建器路由**：
   - `QueryImpl` / `UpdateQueryImpl` / `DeleteQueryImpl` 使用实体对应的数据源

### 建议的子任务拆分

| 子任务 | 描述 |
|--------|------|
| M5-1 | `DataSourceRegistry` — 多数据源注册表，替代 `HormContext` 中的单一 DataSourceProvider |
| M5-2 | `HormContext` 重构 — 持有 `DataSourceRegistry`，提供按名称/按实体类型获取 DataSourceProvider |
| M5-3 | `Horm` 入口扩展 — `install(String, DataSourceProvider)` + `install(DataSourceProvider)` |
| M5-4 | `JdbcRepository` 路由 — 根据 EntityMeta.dataSource() 选择 DataSourceProvider |
| M5-5 | `TransactionManager` 多数据源 — 按数据源名称独立事务栈 |
| M5-6 | 查询构建器路由 — QueryImpl/UpdateQueryImpl/DeleteQueryImpl 使用实体数据源 |
| M5-7 | H2 多数据源集成测试 — 两个 H2 内存库，验证跨数据源操作 |
| M5-8 | 覆盖率检查 + PROGRESS.md 更新 + tag v1.0.0-M5 |

### 需要回答的设计决策

1. **DataSourceRegistry 独立类 vs 内嵌 HormContext**：建议独立 `DataSourceRegistry` 类，HormContext 持有引用
2. **事务传播跨数据源**：REQUIRED 在同一数据源内传播；跨数据源操作各自独立事务（不支持 XA）
3. **未指定 dataSource 的实体**：路由到 `"default"` 数据源
4. **运行时切换数据源**：M5 不支持运行时动态切换，仅在启动时注册

## 启动步骤

1. 从 `feature/m4-transactions` 创建新分支 `feature/m5-datasource`
2. 阅读 `DataSourceProvider`、`HormContext`、`Horm`、`JdbcRepository`、`TransactionManager` 的当前实现
3. 按 M5-1 → M5-8 顺序实施
4. 每个子任务完成后运行 `mvn -pl holo-horm-meta,holo-horm-core -am verify -Pskip-enforcer` 验证
5. 完成后更新 `docs/PROGRESS.md` 并打 tag `v1.0.0-M5`
