# M6 接续提示词：缓存链（L1 + L2 组合）+ batch loading

> 你是 HORM ORM 框架的开发者。请基于以下上下文继续实现 M6 里程碑。

## 项目概览

HORM 是一个 Java ORM 框架，核心设计原则：
- **Active Record + 零运行时反射**：APT 编译期生成伴随类，运行时热路径零反射
- **CRTP 模式**：`Model<T extends Model<T>>` 泛型自引用
- **多数据源 SPI**：`DataSourceRegistry` 管理命名数据源，`@Entity(dataSource = "name")` 驱动路由（M5 已完成）
- **composable cache chain**：M6 落地，支持 L1（本地）/ L2（分布式）多级缓存链式组合

## 仓库位置

```
e:\project\Holo\holo-horm
```

## 当前分支与状态

- **分支**：`feature/m5-datasource`（M5 已完成，待 squash merge 到 main）
- **Tag**：`v1.0.0-M5`
- **构建验证命令**：`mvn -pl holo-horm-meta,holo-horm-core -am verify -Pskip-enforcer`
- **测试总数**：320（meta 148 + core 172），全部通过
- **覆盖率**：meta 模块 85%，core 模块 89%

## M5 交付物（已完成）

| Commit | 子任务 |
|--------|--------|
| `8615f04` | `DataSourceRegistry` + `HormContext` 重构 + `Horm.install(name, provider)` |
| `e39a4fa` | 修复 `installOrRegister` 异常吞噬 + 多数据源事务重载 |
| `8615f04` | `JdbcRepository` / `TransactionManager` / 查询构建器多数据源路由 |
| `8615f04` | H2 多数据源集成测试 + meta 模块测试覆盖率提升 |

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
10. **JavaPoet $T vs $L**：`$T` 生成 import + 短名，`$L` 是字面量
11. **meta 模块无 Mockito 依赖**：测试中用真实对象或匿名内部类代替 mock()
12. **Connection 资源释放**：TransactionManager / HormContext / DataSourceProvider / JdbcRepository / queries 必须在 finally 块释放

## 已有关键文件

### 元数据层（holo-horm-meta）
- `com.holo.framework.horm.meta.annotation.Entity` — `dataSource()` 属性已就位
- `com.holo.framework.horm.meta.EntityMeta` — 含 `dataSource()` 字段、`insertableColumns()`、`field(name)`/`fieldByColumn(column)` 查找
- `com.holo.framework.horm.meta.FieldMeta` — 含 `version()`、`generationStrategy()` 等字段属性
- `com.holo.framework.horm.meta.Mapper` — 接口含 `map`/`toRow`/`getId`/`setId`/`getField`/`setField` + 默认 `setRelation`/`getRelation`/`incrementVersion`
- `com.holo.framework.horm.meta.Row` — 数据源无关行抽象，含 `MapRow` 实现
- `com.holo.framework.horm.meta.processor.HormEntityProcessor` — APT 入口
- `com.holo.framework.horm.meta.processor.EntityDescriptor` / `EntityDescriptorParser` — 实体描述符与解析器
- `com.holo.framework.horm.meta.processor.MetaClassBuilder` — `XxxMeta` 代码生成
- `com.holo.framework.horm.meta.processor.MapperBuilder` — `XxxMapper` 代码生成
- `com.holo.framework.horm.meta.processor.QueryMetaBuilder` — `XxxQueryMeta` 代码生成

### 核心层（holo-horm-core）
- `com.holo.framework.horm.core.HormContext` — 持有 `DataSourceRegistry`，提供 `getDataSource(name)`/`getDataSourceForEntity(Class)`
- `com.holo.framework.horm.core.Horm` — 入口类，`install(HormContext)` / `install(DataSourceProvider)` / `install(name, provider)` + `repository(Class)` + `tx(Callable)`
- `com.holo.framework.horm.core.EntityMetaRegistry` — 元数据注册表
- `com.holo.framework.horm.core.JdbcRepository` — CRUD 实现，根据 `EntityMeta.dataSource()` 路由
- `com.holo.framework.horm.core.TransactionManager` — 按数据源名称独立事务栈
- `com.holo.framework.horm.core.Model<T>` — Active Record 基类
- `com.holo.framework.horm.core.query.Query` / `QueryImpl` — 查询构建器
- `com.holo.framework.horm.core.query.UpdateQuery` / `UpdateQueryImpl` — 批量更新
- `com.holo.framework.horm.core.query.DeleteQuery` / `DeleteQueryImpl` — 批量删除
- `com.holo.framework.horm.core.OptimisticLockException` — 乐观锁异常
- `com.holo.framework.horm.core.datasource.DataSourceRegistry` — 多数据源注册表

### 缓存层（holo-horm-cache，M6 实现）
- `holo-horm-cache/pom.xml` — 已配置依赖（caffeine、redisson、jedis 均 optional）
- **无 Java 源文件**，M6 从零实现

## M6 目标：缓存链 + batch loading

### 设计参考

完整设计文档：`docs/04-cache-chain.md`（必读，包含 SPI 定义、参考实现、一致性保证、序列化、注解驱动等）

### 需要实现的功能

1. **缓存 SPI**：`Cache` 接口（单层）、`CacheChain` 接口（多级组合）、`CachePolicy` 类、`CacheLoader`/`CacheWriter` SPI
2. **缓存事件**：`CacheEvent` record、`CacheEventListener`、`CacheStats` record
3. **L1 本地缓存**：`CaffeineCache`（基于 Caffeine）+ `NoOpCache`（无操作实现，测试用）
4. **L2 分布式缓存**：`RedisCache`（基于 Redisson，optional 依赖）+ SPI 支持其他实现
5. **DefaultCacheChain**：多级链式查询、回填、失效、批量操作
6. **缓存键设计**：`{entityType}:{partition}:{keyType}:{keyValue}:{version}` 格式
7. **读写策略**：Read-Through（默认）、Write-Through、Write-Around（失效模式）
8. **一致性保证**：单飞加载（防击穿）、空值缓存（防穿透）、TTL 抖动（防雪崩）
9. **batch loading**：`getAll(Set<K>)` 批量查询，解决 N+1 问题
10. **ORM 集成**：`JdbcRepository` / `QueryImpl` 可选接入缓存链（通过 `@Cached` 注解或配置启用）

### 建议的子任务拆分

| 子任务 | 描述 |
|--------|------|
| M6-1 | 缓存 SPI 契约：`Cache`/`CacheChain`/`CachePolicy`/`CacheLoader`/`CacheWriter`/`CacheEvent`/`CacheEventListener`/`CacheStats`/`CacheLevel`/`EvictionPolicy`/`WriteStrategy` |
| M6-2 | `DefaultCacheChain` 实现：多级链式查询、回填、失效、批量操作、事件发布 |
| M6-3 | `CaffeineCache` L1 实现（Caffeine optional 依赖，运行时检测） |
| M6-4 | `NoOpCache` 实现（测试用，不依赖外部库） |
| M6-5 | 缓存键设计：`CacheKey` 类 + `CacheKeyBuilder` + 查询哈希工具 |
| M6-6 | `@Cached` 实体注解 + APT 扩展（编译期生成缓存元数据伴随类） |
| M6-7 | `JdbcRepository` / `QueryImpl` 缓存集成（可选启用，默认关闭） |
| M6-8 | batch loading：`getAll(Set<K>)` 批量查询 + `Model.findMany(Collection<ID>)` |
| M6-9 | 单元测试 + 集成测试 + 覆盖率检查（>80%）+ PROGRESS.md + tag v1.0.0-M6 |

### 需要回答的设计决策

1. **M6 范围裁剪**：完整设计文档包含 L3、强一致、失效广播、Micrometer 监控等。M6 优先实现 L1（Caffeine）+ batch loading + ORM 集成；L2（Redis）SPI 定义但实现可简化或留 stub；失效广播/强一致/Micrometer 留到后续里程碑。
2. **缓存可选性**：缓存对业务代码透明，通过 `@Cached` 注解或 `HormContext` 配置启用，默认关闭，不影响 M1-M5 现有行为。
3. **Caffeine optional 依赖**：`holo-horm-cache` 的 caffeine 依赖是 optional，运行时通过 `Class.forName` 检测，未引入则回退到 `NoOpCache`。
4. **缓存键版本控制**：版本号来自 `EntityMeta`（APT 生成时基于 schema hash），schema 变更自动失效旧缓存。
5. **缓存与事务边界**：缓存写入在事务提交后执行（`TransactionManager.afterCommit(Runnable)` 钩子），避免脏数据回填。
6. **batch loading N+1 解决**：`Model.findMany(Collection<ID>)` 一次性查询所有 ID，配合 `CacheChain.getAll(Set<K>)` 批量回填。

## 启动步骤

1. 从 `feature/m5-datasource` 创建新分支 `feature/m6-cache`
2. 必读 `docs/04-cache-chain.md` 完整设计文档
3. 阅读 `HormContext`、`JdbcRepository`、`QueryImpl`、`EntityMeta`、`EntityMetaRegistry` 当前实现
4. 按 M6-1 → M6-9 顺序实施
5. 每个子任务完成后运行 `mvn -pl holo-horm-meta,holo-horm-core,holo-horm-cache -am verify -Pskip-enforcer` 验证
6. 完成后更新 `docs/PROGRESS.md` 并打 tag `v1.0.0-M6`

## 多 agent 并行建议

可并行的子任务：
- M6-1（SPI 契约）与 M6-5（缓存键设计）可并行
- M6-3（CaffeineCache）与 M6-4（NoOpCache）可并行
- M6-6（APT 扩展）与 M6-7（ORM 集成）有依赖关系，需顺序执行
- M6-8（batch loading）可与 M6-7 并行

覆盖率目标：cache 模块 >80%，core 模块保持 >84%，meta 模块保持 >80%。未达目标不结束。
