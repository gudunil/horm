# HORM 开发进度

> 接续点文档：记录各里程碑完成状态、关键决策、下一步计划。
> 维护人：Holo Framework Team · 更新时间：2026-07-06

---

## 当前状态

| 里程碑 | 状态 | 完成时间       | Tag        |
|--------|------|----------------|------------|
| M1     | ✅ 完成 | 2026-07-04     | v1.0.0-M1  |
| M2     | ✅ 完成 | 2026-07-04     | v1.0.0-M2  |
| M3     | ✅ 完成 | 2026-07-05     | v1.0.0-M3  |
| M4     | ✅ 完成 | 2026-07-05     | v1.0.0-M4  |
| M5     | ✅ 完成 | 2026-07-05     | v1.0.0-M5  |
| M6     | ✅ 完成 | 2026-07-06     | v1.0.0-M6  |
| M7     | ✅ 完成 | 2026-07-06     | v1.0.0-M7  |
| M8     | ✅ 完成 | 2026-07-06     | v1.0.0-M8  |
| M8.5   | ✅ 完成 | 2026-07-11     | —          |
| M8.7   | 📋 规划中 | —              | —          |
| M9     | 📋 规划中 | —              | —          |

**当前分支**：`feature/m8.5-dialect`（M8.5 已完成，待 squash merge 到 `main`）

---

## M1: 元数据 SPI 与 APT 流水线（已完成）

### 交付清单

| 子任务 | 描述 | Commit |
|--------|------|--------|
| M1-0 | Git/JaCoCo 基础设施 | `ebc256d` |
| M1-1 | 实体注解与 SPI 契约（`@Entity`/`@Id`/`@Column`/`EntityMeta`/`Mapper`/`Row`） | `45aeb16` |
| M1-2 | `EntityDescriptor` IR 用于 APT 解析 | `8bdbad0` |
| M1-3 | `HormEntityProcessor` + `EntityDescriptorParser` | `583422f` |
| M1-4 | JavaPoet 生成 `XxxMeta`/`XxxMapper`/`XxxQueryMeta` + `entities.idx` | `212b3e6` |
| M1-5 | `EntityValidator` 编译期校验（@Id 必备、Model 继承、字段非 final、类型映射） | `ff7a7fd` |
| M1-6 | `Model<T>` 基类 + `EntityMetaRegistry` + `Horm` 入口 | `cb90a22` |
| M1-7 | `JdbcRepository` PreparedStatement CRUD | `83b8de4` |
| M1-8 | H2 CRUD 集成测试 | `465a680` |

### 测试与覆盖率

- **测试总数**：50（meta 模块 13 + core 模块 37，含 6 个 H2 集成测试）
- **JaCoCo 覆盖率**：
  - meta 模块 processor 包：~93%（> 80% 目标 ✅）
  - core 模块整体：~84%（> 80% 目标 ✅）
- **验证命令**：`mvn -pl holo-horm-meta,holo-horm-core -am verify -Pskip-enforcer`

### 关键设计决策

1. **Active Record + 零运行时反射**
   - APT 编译期生成 `XxxMeta`/`XxxMapper` 伴随类
   - `EntityMetaRegistry` 启动期一次性反射（`Class.forName` + `entityMeta()`），运行时热路径零反射

2. **CRTP 模式**：`Model<T extends Model<T>>` 泛型自引用，让 `find`/`all`/`count` 静态方法返回具体子类型

3. **EntityMetaRegistry 加载机制**
   - `static { loadIndex(); }` 启动期通过 `ClassLoader.getResources("META-INF/horm/entities.idx")` 枚举所有索引文件
   - `ConcurrentHashMap` 缓存，`lookup` O(1)
   - `reload()` 测试钩子：集成测试在 `@AfterEach clear()` 后可重新加载

4. **IndexWriter 合并语义**：APT 写 `entities.idx` 前先读取已存在内容并去重，避免覆盖 `src/{main,test}/resources` 中手动维护的索引文件

5. **JdbcRepository SQL 注入防护**：所有用户值通过 `?` 占位符绑定，仅 APT 冻结的列标识符做字符串拼接

6. **INSERT 主键回填**：`prepareStatement(sql, RETURN_GENERATED_KEYS)` + `getGeneratedKeys()` → `mapper.setId(entity, generatedId)`

7. **测试隔离**：H2 内存库 `jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1`，`@TestInstance(PER_CLASS)` 共享 connection，数据累积策略用 delta/lower bound 断言

### 已知限制（M1 范围内）

- 不解析关联注解（`@OneToMany`/`@ManyToOne` 等）—— 留待 M3
- `JdbcRepository` 仅支持 IDENTITY 主键策略 —— 其他策略留待 M2/M4
- 无事务管理、无查询构建器、无缓存层 —— 分别属于 M4/M5/M6
- `HormException` 覆盖率为 0%（异常类，未被任何测试抛出）—— M2 补充错误路径测试

---

## M2: 查询构建器与条件查询（已完成）

### 交付清单

| 子任务 | 描述 | Commit |
|--------|------|--------|
| C1 | meta 层 `Condition`/`Conditions`/`CompositeCondition` + AND/OR/NOT 组合 | `57cf64d` |
| C2 | `TypedField` default 方法 + `ComparableField` 子接口 + 5 个子类改 implements | `a08a57f` |
| C3 | core 层 `Query<T>`/`QueryImpl`/`Order` + `Model.query()` 入口 | `909ac7c` |
| C4 | `QueryImpl` 错误路径测试（跨实体 orderBy、负数 limit/offset、SQLException 包装） | `2fe6408` |
| C5 | `HormException` 覆盖率提升（JdbcRepository 错误路径 + 无 @Id 元数据） | `02ec424` |
| C6 | H2 集成测试（`UserQueryBuilderTest` 13 用例）+ PROGRESS.md 更新 | `<本 commit>` |

### 测试与覆盖率

- **测试总数**：79（meta 模块 13 + core 模块 66，含 13 个 Query H2 集成测试 + 6 个 M1 CRUD 集成测试）
- **JaCoCo 覆盖率**：
  - meta 模块 processor 包：~93%（> 80% 目标 ✅）
  - core 模块整体：~93%（> 80% 目标 ✅）
  - `HormException`：100%（从 M1 的 0% 提升至 100% ✅）
  - `QueryImpl`：97% 行覆盖
  - `JdbcRepository`：86% 行覆盖
- **验证命令**：`mvn -pl holo-horm-meta,holo-horm-core -am verify -Pskip-enforcer`

### 关键设计决策

1. **jOOQ 风格类型安全 DSL**：基于 APT 生成的 `XxxQueryMeta` TypedField 常量做强类型列引用，`field.eq(value)` 链式构造条件，编译期防止类型不匹配。
2. **`Condition` 落 meta 模块**：`TypedField` 的 default 方法返回 `Condition`，而 core 依赖 meta（反向不可），所以 `Condition/Conditions/CompositeCondition` 全部放 `holo-horm-meta` 的 `query` 包，core 层 `QueryImpl` 只做 SQL 拼装。
3. **`ComparableField` 类型层次**：`StringField/LongField/IntegerField/BigDecimalField/InstantField` 实现 `ComparableField`（含 `gt/lt/ge/le/between`），`BooleanField/EnumField` 保持 `TypedField`（无序语义），编译期类型安全。
4. **`findFirst` 强制 `LIMIT 1`**：尊重 `offset`，忽略用户设的 `limit`，返回 `Optional<T>`，语义最清晰。
5. **`select` 投影 defer 到 M3**：M2 保持 `SELECT *`，单表投影对 Active Record 完整实体映射无价值。
6. **M2 不做子查询/JOIN**：留到 M3 与 `@OneToMany`/`@ManyToOne` 关联一起设计。
7. **`orderBy(field, ASC/DESC) + limit/offset`**：不引入 `Page` 对象，与 SQL 1:1 对应。
8. **参数校验异常分层**：`limit/offset < 0` 抛 `IllegalArgumentException`；`HormException` 保留给 `SQLException` 包装 + 元数据缺失 + 跨实体 orderBy 校验。
9. **SQL 注入防护**：所有用户值（条件值、like 模式、in 列表、limit/offset）一律 `?` 占位符；列名仅来自 APT 冻结的 `TypedField.column()` 拼接。

### 已知限制（M2 范围内）

- 不支持子查询、JOIN、关联查询 —— 留待 M3
- 不支持 `select` 投影列子集 —— 留待 M3
- 不支持 `GROUP BY`/`HAVING`/聚合 —— 留待 M5 或后续
- 不引入 `Page<T>` 对象 —— 用户自行组合 `limit/offset/count`
- `Query<T>` 是 mutable builder，不可重用：每次查询需新建 `Model.query(type)`
- 不支持 `UPDATE ... WHERE` / `DELETE ... WHERE` —— 留待 M4 事务管理

---

## M3: 关联关系映射（已完成）

### 交付清单

| 子任务 | 描述 | Commit |
|--------|------|--------|
| A1-A2 | 5 个 Eloquent 风格关联注解（`@BelongsTo`/`@HasOne`/`@HasMany`/`@HasAndBelongsToMany`/`@HasManyThrough`） + `RelationField` 类型 + `RelationType` 枚举 | `2d19e4d` |
| A3-A5 | `EntityDescriptor`/`EntityDescriptorParser`/`EntityValidator` 扩展（关联解析 + R5-R9 校验规则） | `2d19e4d` |
| A6-A9 | APT 代码生成：`MetaClassBuilder` 输出 `ALL_RELATIONS` + `RelationField` 常量、`QueryMetaBuilder` 输出关联字段常量、`MapperBuilder` 输出 `setRelation` dispatch、`Mapper` 接口加 `setRelation` default 方法 | `52b0024` |
| B1-B2 | `Query` 接口加 `fetch`/`leftJoin`/`innerJoin`/`join`/`select` 方法 + `QueryImpl` 双路径实现（默认 `SELECT *` 路径 + JOIN/投影路径 with `buildAliasedSql`/`splitPrefixedRow`/`listWithFetch`） | `35740e2` |
| C1 | `HormEntityProcessorTest` +8 compile-testing 测试（5 种关联 happy path + R5-R9 拒绝用例） | `bbdcca4` |
| C2 | `UserRelationsTest` +8 H2 集成测试 + 5 个独立实体 fixtures（`Profile`/`Order`/`Tag`/`Product`/`UserWithRelations`） | `bbdcca4` |
| C3 | `QueryImplTest` +7 单元测试（fetch/leftJoin/innerJoin SQL 渲染 + select 投影 + orderBy on join target） | `bbdcca4` |
| C4 | `RelationFieldTest` +3 单元测试（of() 工厂矩阵 + null optionals + toString） | `bbdcca4` |
| Bug fix | `QueryImpl.splitPrefixedRow` 改为 4 参数支持 select 投影 + `appendWhere` JOIN 路径列名加 `t0.` 前缀修复 H2 歧义列名错误 | `bbdcca4` |

### 测试与覆盖率

- **测试总数**：158（meta 模块 64 + core 模块 94，含 8 个 M3 H2 关联集成测试 + 7 个 M3 单元测试 + 3 个 RelationField 单元测试 + 8 个 APT compile-testing 测试）
- **JaCoCo 覆盖率**：
  - meta 模块 processor 包：93.7%（> 80% 目标 ✅）
  - core 模块整体：93.3%（> 84% 目标 ✅）
  - `HormException`：100%（M2 已达，M3 保持 ✅）
  - `QueryImpl`：94.9% 指令覆盖（含 `listWithFetch`/`buildAliasedSql`/`splitPrefixedRow` 全部分支）
  - `MapperBuilder`：100%（`setRelation` dispatch 生成覆盖）
- **验证命令**：`mvn -pl holo-horm-meta,holo-horm-core -am verify -Pskip-enforcer`

### 关键设计决策

1. **Eloquent 风格注解命名**：采用 Laravel Eloquent 的 `@BelongsTo`/`@HasOne`/`@HasMany`/`@HasAndBelongsToMany`/`@HasManyThrough` 而非 JPA 的 `@ManyToOne`/`@OneToMany` 等，与 Active Record 风格一致。
2. **显式 fetch 加载策略**：默认不加载关联，需显式调用 `query.fetch(ORDERS)` 触发 eager JOIN。无 lazy proxy（留待 M6 batch loading）。
3. **JOIN API 设计**：`fetch` 是 `leftJoin` 的便捷别名；`join` 默认 `innerJoin`（jOOQ 风格）；显式 `leftJoin`/`innerJoin` 控制 JOIN 类型。
4. **纯查询关联（无 cascade）**：M3 只支持关联查询，不支持 cascade persist/merge/remove（留待 M4 事务管理）。
5. **统一 List 语义（R5）**：所有关联字段（含 `@BelongsTo`/`@HasOne`）必须为 `List<...>`。`@BelongsTo` 返回"同父兄弟实体列表"而非传统"单个父实体"，简化类型系统与 APT 代码生成。
6. **select 投影必须含 id**：`select(ID, EMAIL)` 返回完整实体（非投影列为 null），但必须包含 id 字段以支持实体图去重，否则抛 `HormException`。
7. **N+1 优化仅 eager JOIN**：M3 只提供 eager JOIN 模式解决 N+1，batch loading 留待 M6。
8. **不管理外键 DDL**：关联注解的 `foreignKey` 仅用于拼 JOIN ON 条件，不生成外键约束（应用层校验留待后续）。
9. **双路径 QueryImpl 设计**：默认路径（无 join/select）保持 `SELECT * FROM <table>` 不变，保护 M2 的 25 个 SQL 断言；JOIN/投影路径用别名 `t0__col` + `splitPrefixedRow` 隔离前缀，避免修改 `Row`/`Mapper`。
10. **@BelongsTo 放在子端**：`@BelongsTo` 标注在持 FK 的子实体上（如 `Order.user_id` 指向 `User.id`），JOIN 渲染 `tN.id = t0.<fk>`。父实体不持 FK 列，所以不能在父端放 `@BelongsTo`。
11. **HABTM/THROUGH 双 JOIN 别名**：HABTM 用 `jtM + tN`，THROUGH 用 `thM + tN`，target alias 始终连续（t1, t2, ...），middle alias 独立计数器。
12. **Mapper.setRelation 用 default 方法**：避免破坏 M1/M2 手写 mock Mapper，APT 生成 mapper 覆盖该方法。default 实现抛 `UnsupportedOperationException`。
13. **JOIN 路径 WHERE 列名前缀**：JOIN 路径下 `appendWhere` 用正则给列名加 `t0.` 前缀，避免 H2 歧义列名错误。默认路径保持裸列名不变。

### 已知限制（M3 范围内）

- 不支持 lazy proxy 加载 —— 仅 eager JOIN，N+1 仅靠 `fetch` 显式调用缓解
- 不支持 cascade 级联（persist/merge/remove）—— 留待 M4 事务管理
- 不支持 batch loading —— 留待 M6 缓存链
- `@BelongsTo` 返回"同父兄弟列表"而非单个父实体 —— R5 统一 List 语义的有意决策
- `select` 投影返回完整实体，非投影列为 null —— 不返回投影 DTO
- 不管理外键 DDL —— 仅用注解 `foreignKey` 列名拼 JOIN ON
- `Query<T>` 仍是 mutable builder，不可重用 —— 每次查询需新建 `Model.query(type)`
- 不支持 `UPDATE ... WHERE` / `DELETE ... WHERE` —— 留待 M4

---

## M4: 事务管理 + 级联 + 批量操作 + 乐观锁（已完成）

### 交付清单

| 子任务 | 描述 | Commit |
|--------|------|--------|
| M4-1 | `DataSourceProvider` SPI + `SimpleDataSourceProvider` + `HormContext` 重构 | `9f9e92a` |
| M4-2 | `TransactionManager`/`TransactionDefinition`/`TransactionStatus`/`TransactionException` + `@Transactional` 注解 + `Propagation`/`Isolation` 枚举 + 编程式 `Horm.tx()` | `5a87281` |
| M4-3 | `TransactionMethodMeta` + `TransactionAdvisorBuilder`（编译期扫描 `@Transactional` 并生成事务元数据伴随类） + `IndexWriter` 扩展 | `5a87281` |
| M4-4 | `UpdateQuery`/`UpdateQueryImpl` + `DeleteQuery`/`DeleteQueryImpl` + `Model.update()`/`Model.delete()` 静态入口 | `fbe7208` |
| M4-5 | `CascadeType` 枚举 + 5 个关联注解 `cascade()` 属性 + `RelationMeta`/`EntityDescriptor`/`EntityDescriptorParser`/`MetaClassBuilder`/`MapperBuilder` 扩展 + `Model.save()`/`saveWith()`/`delete()`/`deleteWith()` cascade 辅助 | `fbe7208` |
| M4-6 | `@Version` 注解 + `FieldMeta.version`/`EntityMeta.versionField`/`Mapper.incrementVersion` + `OptimisticLockException` + `JdbcRepository` 乐观锁 UPDATE/DELETE + APT `EntityValidator` R10/R11 校验 | `841b899` |

### 测试与覆盖率

- **测试总数**：145（meta 模块 66 + core 模块 79）
- 新增测试：
  - `TransactionManagerTest`（22 测试）+ `TransactionIntegrationTest`（7 H2 集成测试）
  - `UpdateQueryImplTest`（6 测试）+ `DeleteQueryImplTest`（4 测试）+ `BatchOperationIntegrationTest`（7 H2 集成测试）
  - `OptimisticLockIntegrationTest`（5 H2 集成测试）
  - `HormEntityProcessorTest` +2 APT compile-testing（@Version happy path + R10 错误路径）
- **验证命令**：`mvn -pl holo-horm-meta,holo-horm-core -am verify -Pskip-enforcer`

### 关键设计决策

1. **APT 编译期事务元数据扫描**：`TransactionAdvisorBuilder` 在编译期为 `@Transactional` 方法/类生成 `XxxTransactionAdvisor` 元数据伴随类并写入 `transactions.idx`，为 M8 运行时 AOP 织入做准备；M4 实际事务边界通过编程式 `Horm.tx()` 或手动 `TransactionManager` API 控制。
2. **编程式事务 + 声明式元数据**：M4 通过 `Horm.tx()` / `TransactionManager` 提供完整编程式事务；`@Transactional` 注解在 M4 仅完成编译期元数据扫描，运行时方法拦截将在 M8 Spring Boot Starter 中实现。
3. **REQUIRED + REQUIRES_NEW 传播**：REQUIRED 加入现有事务或创建新事务；REQUIRES_NEW 始终创建新事务并挂起现有事务。
4. **注解驱动 + 显式 cascade 双模式**：`save()`/`delete()` 自动级联带 `CascadeType.PERSIST`/`REMOVE`/`ALL` 的关联；`saveWith()`/`deleteWith()` 显式指定级联关联名。
5. **Query 风格 + Model 静态批量 API**：`Model.update(Class)`/`Model.delete(Class)` 返回 `UpdateQuery`/`DeleteQuery` 流畅 API。
6. **@Version 乐观锁**：仅支持 int/Integer/long/Long；版本字段 `insertable=false, updatable=false`（框架管理）；UPDATE/DELETE 添加 `WHERE version = ?` + 受影响行数检测 + `OptimisticLockException`。
7. **单数据源 + SPI 钩子**：M4 仅单数据源，`DataSourceProvider` SPI 为 M5 多数据源预留扩展点。

### 已知限制（M4 范围内）

- 不支持 `NESTED`/`SUPPORTS`/`NOT_SUPPORTED`/`MANDATORY`/`NEVER` 传播级别 —— 仅 REQUIRED + REQUIRES_NEW
- 不支持 `MERGE`/`DETACH`/`REFRESH` cascade 操作 —— 仅 `PERSIST`/`REMOVE`/`ALL` 实际生效
- `@Transactional` 在 M4 仅完成编译期元数据扫描，运行时方法拦截/AOP 代理将在 M8 Spring Boot Starter 中实现
- `@Transactional` 仅支持类级别和方法级别，不支持接口继承
- 乐观锁仅在 `JdbcRepository.update` 和 `delete(entity)` 中生效，`deleteById` 不检查版本
- 不支持 `INSERT ... ON DUPLICATE KEY UPDATE` —— 留待 M5
- 不支持批量 `IN` 参数上限校验 —— 留待后续

---

## M5: 多数据源 SPI 与路由（已完成）

### 交付清单

| 子任务 | 描述 | 状态 |
|--------|------|------|
| M5-1 | `DataSourceRegistry` — 多数据源注册表，管理命名数据源 | ✅ |
| M5-2 | `HormContext` 重构 — 持有 `DataSourceRegistry`，提供按名称/按实体类型获取 DataSourceProvider | ✅ |
| M5-3 | `Horm` 入口扩展 — `install(String, DataSourceProvider)` + `install(DataSourceProvider)` | ✅ |
| M5-4 | `JdbcRepository` 路由 — 根据 EntityMeta.dataSource() 选择 DataSourceProvider | ✅ |
| M5-5 | `TransactionManager` 多数据源 — 按数据源名称独立事务栈 | ✅ |
| M5-6 | 查询构建器路由 — QueryImpl/UpdateQueryImpl/DeleteQueryImpl 使用实体数据源 | ✅ |
| M5-7 | H2 多数据源集成测试 — 两个 H2 内存库，验证跨数据源操作 | ✅ |
| M5-8 | 覆盖率检查 + PROGRESS.md 更新 + tag v1.0.0-M5 | ✅ |

### 测试与覆盖率

- **测试总数**：320（meta 模块 148 + core 模块 172）
- 新增测试：
  - `DataSourceRegistryTest`（13 测试）— 数据源注册、查找、默认数据源
  - `MultiDatasourceIntegrationTest`（4 H2 集成测试）— 跨数据源 CRUD、事务隔离
  - `HormMultiDatasourceTest`（6 测试）— Horm 入口多数据源安装
  - `RowTest`（51 测试）— Row.MapRow 类型转换全覆盖
  - `EntityMetaTest`（16 测试）— EntityMeta Builder + 字段查找
  - `FieldAccessorTest`（5 测试）— 读写/只读访问器
  - `MapperTest`（3 测试）— Mapper 默认方法
  - `TransactionMethodMetaTest`（7 测试）— 事务元数据
- **JaCoCo 覆盖率**：
  - meta 模块整体：85%（> 80% 目标 ✅）
  - core 模块整体：89%（> 80% 目标 ✅）
  - `com.holo.framework.horm.meta` 包：90%（从 3% 提升至 90%）
  - `com.holo.framework.horm.core.datasource` 包：100%
- **验证命令**：`mvn -pl holo-horm-meta,holo-horm-core -am verify -Pskip-enforcer`

### 关键设计决策

1. **DataSourceRegistry 独立类**：`DataSourceRegistry` 作为独立类管理命名数据源，`HormContext` 持有引用，职责清晰。
2. **默认数据源名称为 "default"**：未指定 `dataSource` 的实体自动路由到 `"default"` 数据源。
3. **每个数据源独立事务栈**：`TransactionManager` 使用 `Map<String, Deque<TransactionStatus>>` 为每个数据源维护独立的 ThreadLocal 事务栈，跨数据源操作各自独立事务（不支持 XA）。
4. **EntityMeta.dataSource() 驱动路由**：`JdbcRepository` 构造时根据 `EntityMeta.dataSource()` 选择对应的 DataSourceProvider，查询构建器同理。
5. **Horm.install() 双模式**：`install(DataSourceProvider)` 注册默认数据源，`install(String, DataSourceProvider)` 注册命名数据源。
6. **运行时静态路由**：M5 不支持运行时动态切换数据源，仅在启动时注册，实体与数据源映射在编译期由 APT 生成。

### 已知限制（M5 范围内）

- 不支持运行时动态切换数据源 — 仅启动时注册
- 不支持 XA 分布式事务 — 跨数据源操作各自独立事务
- 不支持数据源连接池配置 — 仅 SPI 接口，具体实现由用户提供
- 不支持读写分离路由 — 留待 M6 缓存链
- `@Entity(dataSource = "...")` 仅支持字符串字面量，不支持 SpEL 或配置引用

---

## M6: 缓存链（L1 + L2 组合）+ batch loading（已完成）

### 交付清单

| 子任务 | 描述 | Commit |
|--------|------|--------|
| M6-1 | 缓存 SPI 契约：`Cache`/`CacheChain`/`CachePolicy`/`CacheLevel`/`WriteStrategy`/`EvictionPolicy`/`CacheEvent`/`TypeReference` | `<本 commit>` |
| M6-2 | `DefaultCacheChain` 多级链实现（逐层查找、上层回填、批量加载、事件发布） | `<本 commit>` |
| M6-3 | `CaffeineCache` L1 实现（零序列化、TTL/size 淘汰、事件转发） | `<本 commit>` |
| M6-4 | `NoOpCache` 兜底实现 | `<本 commit>` |
| M6-5 | 缓存键设计：`CacheKey`/`CacheKeyBuilder`/`QueryHash`/`SensitiveHash` | `<本 commit>` |
| M6-额外 | `RedisCache` L2 stub + `Serializer`/`JdkSerializer` + `SingleFlightLoader` + `TtlJitter` | `<本 commit>` |
| M6-6 | `@Cached`/`@CachePolicy` 注解 + APT 扩展（`EntityDescriptor`/`EntityValidator`/`EntityMeta`/`MetaClassBuilder`） | `<本 commit>` |
| M6-7 | ORM 集成：`HormContext` 可选 `CacheChain`、`TransactionManager.afterCommit/afterRollback` 钩子、`Repository.findMany`/`Model.findMany` | `<本 commit>` |
| M6-7 | `JdbcRepository` 缓存路径 + `QueryImpl` 可选查询缓存（仅 THROUGH 模式） | `<本 commit>` |
| M6-9 | H2 集成测试（`FindManyCacheIntegrationTest`）、`JdbcRepositoryCacheTest`、全量验证与 JaCoCo 覆盖率检查 | `<本 commit>` |

### 测试与覆盖率

- **测试总数**：708（meta 模块 156 + cache 模块 346 + core 模块 206）
- 新增测试：
  - `CachedAnnotationProcessorTest`（8 测试）— `@Cached` APT 生成与 R12 校验
  - `HormContextTest` 扩展 — cache chain 构造与访问器
  - `TransactionManagerTest` 扩展 — afterCommit/afterRollback 钩子、嵌套事务回调传播
  - `JdbcRepositoryCacheTest`（12 测试）— 缓存命中/未命中、write-through、afterCommit 失效
  - `FindManyCacheIntegrationTest`（5 H2 集成测试）— 部分命中、全未命中、批量回填、Repository.findMany、absent id
- **JaCoCo 覆盖率**：
  - meta 模块整体：84%（> 80% 目标 ✅）
  - cache 模块整体：87%（> 80% 目标 ✅）
  - core 模块整体：86%（> 84% 目标 ✅）
- **验证命令**：`mvn -pl holo-horm-meta,holo-horm-core,holo-horm-cache -am verify -Pskip-enforcer`

### 关键设计决策

1. **模块依赖解耦**：`holo-horm-meta` 不依赖 `holo-horm-cache`；缓存相关枚举与 `CachePolicy` 在 meta 模块独立定义（`com.holo.framework.horm.meta.annotation`），避免 APT 阶段类加载问题。
2. **APT 嵌套注解解析**：`EntityDescriptorParser.parseCached()` 使用 `AnnotationMirror` 手动遍历 `@Cached`/`@CachePolicy` 元素值，绕过 JVM 反射强制类型转换导致的 "Incorrectly typed data found" 错误。
3. **JavaPoet 长整型字面量**：`MetaClassBuilder` 生成 `Duration.ofNanos(...)` 时使用 `$LL` 占位符，避免大整数被当作 int 编译失败。
4. **缓存默认关闭**：未标注 `@Cached` 时 `EntityMeta.cached()=false`、`HormContext.cacheChain()` 默认 null，M1-M5 行为零回归。
5. **事务边界写入**：所有缓存写入/失效通过 `TransactionManager.afterCommit()` 延迟到事务提交后执行；rollback 时执行 afterRollback 回调，保障数据一致性。
6. **WriteStrategy 语义**：`THROUGH` 在提交后 put 并允许查询缓存；`AROUND`（默认）不写缓存、不缓存查询结果；`BEHIND` 已预留但未在 M6 实现。
7. **批量加载**：`JdbcRepository.findMany(Collection<ID>)` 构造 `Set<CacheKey>` 调用 `CacheChain.getAll`，缺失 key 通过 `SELECT * FROM t WHERE id IN (...)` 批量回填。
8. **查询缓存条件**：仅当 `@Cached` + `WriteStrategy.THROUGH` + 无 JOIN + 无 select 投影时启用，避免 AROUND 模式缓存污染。
9. **循环依赖修复**：`holo-horm-cache` 原依赖 `holo-horm-core` compile scope 导致循环；将 cache→core 改为 test scope，core→cache 改为 compile scope，并移除 cache 中的 `QueryHash.hash(Query)` 方法。
10. **并发测试确定性**：`SingleFlightLoaderTest` 使用 `AtomicInteger` 计数 + 主线程轮询替代 `Thread.sleep`，消除多核 CPU 调度导致的 flaky。

### 已知限制（M6 范围内）

- `RedisCache` 为 stub 实现，未启动真实 Redis 实例验证；L2 集群失效广播 Pub/Sub 未实现
- `BEHIND` 写策略已定义但 M6 未实现异步写behind逻辑
- 查询缓存仅支持单表、无 JOIN、无投影、THROUGH 模式
- `CaffeineCache` 不支持 per-entry TTL，仅使用构造时全局 TTL
- `RedisCache` 统计信息为零（Redisson 无原生 stats，M6 不实现）
- 缓存未与数据库 schema 迁移工具集成；DDL 仍需手动维护

---

## M7: 数据库迁移（Flyway 集成）（已完成）

### 交付清单

| 子任务 | 描述 | Commit |
|--------|------|--------|
| M7-1 | 创建 `holo-horm-migration` 模块 + pom.xml（Flyway core 依赖） | `<本 commit>` |
| M7-2 | 定义 Migration SPI：`Migration` 抽象类 + `Schema` 接口 + `TableBuilder` + `ColumnBuilder` DSL | `<本 commit>` |
| M7-3 | 实现 `Schema` 渲染器：`H2SchemaRenderer` / `MySQLSchemaRenderer` 将 DSL 转为 DDL SQL | `<本 commit>` |
| M7-4 | 集成 Flyway：`FlywayMigrationRunner` 封装 Flyway 引擎，支持 Java + SQL 双格式迁移 | `<本 commit>` |
| M7-5 | 多数据源迁移：`MultiDataSourceMigrationRunner` + `DataSourceRegistry` 分组执行 | `<本 commit>` |
| M7-6 | 校验和验证：`MigrationChecksum` CRC32 校验 + `MigrationChecksumException` | `<本 commit>` |
| M7-7 | Horm 集成：`Horm.migrate()` / `Horm.migrate(String)` 入口 + `MigrationExecutor` SPI | `<本 commit>` |
| M7-8 | 命令行 stub：`MigrationCommand` 接口 + `MigrateCommand`/`RollbackCommand`/`StatusCommand`/`MakeCommand` | `<本 commit>` |
| M7-9 | H2 集成测试 + 覆盖率检查 + PROGRESS.md + tag v1.0.0-M7 | `<本 commit>` |

### 测试与覆盖率

- **测试总数**：744（meta 模块 156 + cache 模块 346 + core 模块 206 + migration 模块 36）
- 新增测试：
  - `MigrationDslIntegrationTest`（4 H2 集成测试）— DSL 创建表、多语句迁移、状态查询、校验和
  - `MigrationChecksumTest`（5 测试）— CRC32 校验和计算
  - `MigrationCommandsTest`（5 测试）— 命令查找与执行
  - `H2SchemaRendererTest`（10 测试）— H2 DDL 渲染
  - `MySQLSchemaRendererTest`（6 测试）— MySQL DDL 渲染
  - `DdlSchemaTest`（6 测试）— Schema DSL 语句收集
- **JaCoCo 覆盖率**：
  - meta 模块整体：84%（> 80% 目标 ✅）
  - cache 模块整体：87%（> 80% 目标 ✅）
  - core 模块整体：86%（> 84% 目标 ✅）
  - migration 模块整体：82%（> 80% 目标 ✅）
- **验证命令**：`mvn -pl holo-horm-meta,holo-horm-core,holo-horm-cache,holo-horm-migration -am verify -Pskip-enforcer`

### 关键设计决策

1. **Flyway 作为迁移引擎**：M7 采用 Flyway 作为迁移引擎核心（成熟、生产级、Spring Boot 原生集成），在其上层提供 HORM 风格的 DSL 包装。不重新实现迁移版本管理、checksum、baseline 等 Flyway 已有的能力。
2. **Migration DSL vs 纯 SQL**：优先支持 Flyway 原生的 Java/SQL 迁移格式；HORM 的 `Migration` + `Schema` DSL 作为便捷 API 封装在 Flyway `JavaMigration` 之上，生成 SQL 交由 Flyway 执行。
3. **模块边界**：`holo-horm-migration` 为新模块，依赖 `holo-horm-core`（获取 DataSourceRegistry）+ `flyway-core`（迁移引擎）。不依赖 cache/meta 模块。
4. **H2 兼容性**：Migration DSL 生成的 DDL 需同时兼容 H2（MODE=MySQL）和真实 MySQL；类型映射由 `SchemaRenderer` 处理。
5. **baseline 支持**：首次在已有数据库上启用迁移时，Flyway baseline 避免重复执行历史迁移。
6. **多数据源隔离**：每个数据源独立的 Flyway 实例 + `flyway_schema_history` 表；默认数据源无需指定名称。
7. **rollback 范围**：Flyway 社区版不支持 undo migration；M7 的 `down()` 仅在测试中使用，生产环境 rollback 需 Flyway Pro/Enterprise 或手动 SQL。
8. **MigrationExecutor SPI**：`Horm.migrate()` 通过 `ServiceLoader` 发现 `MigrationExecutor` 实现，避免 core 模块直接依赖 migration 模块，保持模块解耦。
9. **NonCloseableConnection 包装**：`ConnectionDataSource` 返回不可关闭的连接包装器，防止 Flyway 关闭底层连接后影响后续操作。

### 已知限制（M7 范围内）

- `down()` 方法仅在测试中使用，生产环境 rollback 需 Flyway Pro/Enterprise 或手动 SQL
- 不支持运行时动态添加迁移脚本 — 仅启动时扫描 classpath
- 不支持迁移脚本热重载 — 需重启应用
- 命令行工具为 stub 实现，M8 Spring Boot Starter 完整集成
- 不支持迁移脚本版本冲突检测 — 由 Flyway 内部处理
- 不支持跨数据源事务迁移 — 每个数据源独立迁移

---

## M8: Spring Boot Starter + @Transactional APT 实现（已完成）

### 交付清单

| 子任务 | 描述 | Commit |
|--------|------|--------|
| M8-1 | 扩展 `TransactionMethodMeta` 支持 `rollbackFor`/`noRollbackFor` + `shouldRollback()` 方法 | `<本 commit>` |
| M8-2 | 扩展 `TransactionAdvisorBuilder` 生成完整事务元数据（APT 读取 Class[] 注解属性） | `<本 commit>` |
| M8-3 | 创建 `TransactionAdvisorRegistry`：运行时加载 `transactions.idx` 索引，提供 O(1) 查询 | `<本 commit>` |
| M8-4 | 创建 `TransactionInterceptor`：运行时事务拦截器，执行事务边界 + 异常回滚判断 | `<本 commit>` |
| M8-5 | 创建 `@EnableHorm` 注解 + `HormAutoConfiguration` + `HormProperties` 配置类 | `<本 commit>` |
| M8-6 | 创建 `HormTransactionalBeanPostProcessor`：JDK 动态代理包装 @Transactional Bean | `<本 commit>` |
| M8-7 | Flyway 自动迁移（`HormMigrationAutoConfiguration`）+ 多数据源自动配置 | `<本 commit>` |
| M8-8 | 集成测试 + 覆盖率检查 + PROGRESS.md + tag v1.0.0-M8 | `<本 commit>` |

### 测试与覆盖率

- **测试总数**：863（meta 模块 156 + cache 模块 368 + core 模块 206 + migration 模块 99 + starter 模块 34）
- 新增测试：
  - `TransactionMethodMetaTest` 扩展 — `shouldRollback()` 异常判断逻辑
  - `HormAutoConfigurationTest`（6 测试）— Spring Boot 自动装配 + SpringDataSourceProvider 连接管理
  - `HormMultiDataSourceAutoConfigurationTest`（9 测试）— 多数据源配置 + ConfigurableDataSourceProvider 连接管理
  - `HormTransactionalBeanPostProcessorTest`（3 测试）— JDK 代理创建
  - `HormMigrationAutoConfigurationTest`（4 测试）— Flyway 自动迁移条件装配
  - `HormMigrationAutoConfigurationRunTest`（5 测试）— 迁移执行逻辑
  - `HormDataSourcePropertiesTest`（4 测试）— 配置属性绑定
  - `HormPropertiesTest`（3 测试）— 配置属性
- **JaCoCo 覆盖率**：
  - meta 模块整体：84%（> 80% 目标 ✅）
  - cache 模块整体：87%（> 80% 目标 ✅）
  - core 模块整体：86%（> 84% 目标 ✅）
  - migration 模块整体：82%（> 80% 目标 ✅）
  - starter 模块整体：97%（> 80% 目标 ✅）
- **验证命令**：`mvn -f holo-horm/pom.xml clean verify -Pskip-enforcer`

### 关键设计决策

1. **APT 实现 @Transactional（非 AOP）**：M8 采用 APT 编译期生成事务元数据 + 运行时拦截器方案，而非 Spring AOP。APT 在编译期为 `@Transactional` 类生成 `XxxTransactionAdvisor` 元数据伴随类，运行时通过 `TransactionAdvisorRegistry` 加载索引，`TransactionInterceptor` 执行事务边界。
2. **JDK 动态代理**：`HormTransactionalBeanPostProcessor` 使用 JDK 动态代理（非 CGLIB）包装 Spring Bean，要求目标 Bean 实现至少一个接口。未实现接口的 Bean 不会被代理。
3. **事务元数据索引**：APT 生成 `META-INF/horm/transactions.idx` 索引文件，列出所有 `XxxTransactionAdvisor` 全限定类名。运行时 `TransactionAdvisorRegistry` 通过 `ClassLoader.getResources()` 加载索引，反射读取 `METHODS` 静态字段。
4. **异常回滚规则**：`TransactionMethodMeta.shouldRollback(Throwable)` 实现三层判断：① `noRollbackFor` 匹配则不回滚；② `rollbackFor` 非空且不匹配则不回滚；③ 默认 `RuntimeException`/`Error` 回滚。
5. **APT 读取 Class[] 注解属性**：`TransactionAdvisorBuilder.extractClassNames()` 通过 `MirroredTypeException` 捕获 `TypeMirror`，处理 `TypeKind.ARRAY` 和 `TypeKind.DECLARED` 两种情况，提取异常类全限定名。
6. **Spring Boot 自动装配**：`HormAutoConfiguration` 从 `spring.datasource.*` 构造 `SpringDataSourceProvider`，创建 `HormContext` 并调用 `Horm.install()`。`@ConditionalOnClass(Horm.class)` 保证可选依赖。
7. **Flyway 自动迁移**：`HormMigrationAutoConfiguration` 实现 `CommandLineRunner`，当 `holo.horm.migration.auto-on-startup=true` 时启动时调用 `Horm.migrate()`。`@ConditionalOnClass({Horm.class, Flyway.class})` 保证 classpath 无 Flyway 时跳过。
8. **多数据源自动配置**：`HormMultiDataSourceAutoConfiguration` 从 `spring.datasource.<name>.*` 读取多数据源配置，为每个数据源创建 `DataSourceProvider` 并注册到 `DataSourceRegistry`。第一个数据源自动注册为默认。
9. **TransactionManager.popAndResume 可见性**：将 `popAndResume` 方法从 `private` 改为包级私有，供 `TransactionInterceptor` 调用。

### 已知限制（M8 范围内）

- **JDK 动态代理限制**：仅代理实现了接口的 Bean；未实现接口的 Bean 不会被代理（需 CGLIB，M8 未实现）
- **APT 多异常类处理**：`extractClassNames()` 当前仅处理数组第一个元素，`rollbackFor = {A.class, B.class}` 会丢失 B.class（待修复）
- **无集成测试**：M8 核心功能已实现，但 Spring Boot 集成测试（`@SpringBootTest`）待补充
- **无 CGLIB 支持**：未实现接口的 Bean 无法代理，需引入 CGLIB 或要求用户实现接口
- **无 @Transactional 继承**：不支持从父类/接口继承 `@Transactional` 注解
- **无嵌套事务**：`REQUIRES_NEW` 传播行为已支持，但 `NESTED`（保存点）未实现
- **无 Spring 事务管理器集成**：未暴露 `PlatformTransactionManager` 包装 HORM 的 `TransactionManager`

---

## M8.5: 数据库方言适配（MySQL/PostgreSQL/H2）（已完成）

### 交付清单

| 子任务 | 描述 | 状态 |
|--------|------|------|
| D1 | `Dialect` 接口 + `IdentityStrategy` + `BatchInsertSyntax` 枚举 | ✅ 完成 |
| D2 | `MySqlDialect` 实现（默认方言） | ✅ 完成 |
| D3 | `PostgresDialect` 实现 | ✅ 完成 |
| D4 | `H2Dialect` 实现（MySQL/PostgreSQL 兼容模式） | ✅ 完成 |
| D5 | `DialectDetector` JDBC URL 自动检测 | ✅ 完成 |
| D6 | `HormContext` 增加 Dialect 映射 | ✅ 完成 |
| D7 | `QueryImpl` 分页改用 `Dialect.paginate()` | ✅ 完成 |
| D8 | `JdbcRepository` exists/save 改用 `Dialect` | ✅ 完成 |
| D9 | `UpdateQueryImpl`/`DeleteQueryImpl` 分页改用 `Dialect` | ✅ 完成 |
| D10 | `SchemaRenderer` 对齐 `Dialect` 接口 | ✅ 完成 |
| D11 | `PostgresSchemaRenderer` 实现 | ✅ 完成 |
| D12 | `H2SchemaRenderer` 支持 PostgreSQL 模式 | ✅ 完成 |
| D13 | Starter 多数据源 Dialect 自动检测 | ✅ 完成 |
| D14 | Starter 默认数据源 Dialect 自动检测 | ✅ 完成 |
| D15 | `DialectTest` — 各方言方法单元测试 | ✅ 完成 |
| D16 | `DialectDetectorTest` — URL 检测测试 | ✅ 完成 |
| D17 | H2 MODE=PostgreSQL 集成测试 | ✅ 完成 |
| D18 | 现有测试回归验证 | ✅ 完成 |

### 分批实施计划

#### 批次 A：Dialect SPI + MySQL/H2（D1-D5, D16, D18）

建立 Dialect 体系，现有功能零回归。

#### 批次 B：核心模块改造 + PostgreSQL 方言（D3, D6-D9, D15, D17）

JdbcRepository/QueryImpl 通过 Dialect 生成 SQL，新增 PG 支持。

#### 批次 C：迁移模块 + Starter 集成（D10-D14）

迁移渲染器统一到 Dialect 体系，Spring Boot 自动检测。

### 关键设计决策

1. **Dialect 位于 core 模块**：`com.holo.framework.horm.core.dialect` 包，JdbcRepository/QueryImpl 直接消费，migration 模块通过 core 依赖获取类型映射
2. **默认 Dialect 为 MySQL**：与现有 H2 MODE=MySQL 测试行为一致，零回归
3. **Dialect.paginate() 签名含 bindings**：Oracle 方言的 `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` 需要绑定参数，且参数顺序与 MySQL 不同
4. **JDBC URL 自动检测 + 显式配置**：优先从 URL 检测，支持 `spring.datasource.<name>.dialect` 手动覆盖
5. **H2 双模式**：H2 支持 MySQL 和 PostgreSQL 兼容模式，`DialectDetector` 解析 `MODE=` 参数
6. **SchemaRenderer 保留独立**：Dialect 提供 SQL 类型映射和标识符引用，SchemaRenderer 专注 DDL 语法差异，两者协作但不合并
7. **Oracle/SQLite 仅定义接口**：M8.5 不实现 Oracle/SQLite 方言，但 Dialect 接口设计需预留扩展点

### 设计文档

详见 [docs/08-dialect-adaptation.md](./08-dialect-adaptation.md)

---

## M8.7: 零反射优化 — 编译期代理生成与反射消除（进行中）

### 交付清单

| 子任务 | 描述 | 模块 | 状态 |
|--------|------|------|------|
| **Phase 1: APT 事务代理生成（消除 R3 + R7/R8）** | | | |
| P1-1 | 定义 `TransactionProxyFactory` SPI 接口 | meta | ✅ |
| P1-2 | 实现 `TransactionProxyBuilder`（JavaPoet 生成代理子类 + 工厂类） | meta | ✅ |
| P1-3 | 扩展 `HormEntityProcessor` 调用 `TransactionProxyBuilder` | meta | ✅ |
| P1-4 | APT 生成 `META-INF/services/...TransactionProxyFactory` | meta | ✅ |
| P1-5 | 实现 `MethodBridgeFactory` 降级（LambdaMetafactory 桥接） | core | ✅ |
| P1-6 | 改造 `HormTransactionalBeanPostProcessor`（ServiceLoader 加载工厂 + 移除 JDK Proxy） | starter | ✅ |
| P1-7 | 改造 `TransactionInterceptor`（保留降级路径，内部用 MethodBridgeFactory） | core | ✅ |
| P1-8 | APT compile-testing（代理类和工厂类生成验证） | meta (test) | ✅ |
| P1-9 | Spring Boot 集成测试（@Transactional 方法事务拦截验证） | starter (test) | ✅ |
| P1-10 | 性能基准测试（JMH: Method.invoke vs APT 代理 vs LambdaMetafactory） | benchmark | ✅ |
| **Phase 2: ServiceLoader 替代自定义索引（消除 R1 + R2）** | | | |
| P2-1 | 定义 `EntityMetaProvider` SPI 接口 | meta | ✅ |
| P2-2 | 定义 `TransactionAdvisorProvider` SPI 接口 | meta | ✅ |
| P2-3 | APT 生成的 XxxMeta 实现 EntityMetaProvider | meta | ✅ |
| P2-4 | APT 生成 `META-INF/services` 文件（EntityMetaProvider + TransactionAdvisorProvider） | meta | ✅ |
| P2-5 | 改造 `EntityMetaRegistry`（ServiceLoader + entities.idx 兼容回退） | core | ✅ |
| P2-6 | 改造 `TransactionAdvisorRegistry`（ServiceLoader + transactions.idx 兼容回退） | core | ✅ |
| P2-7 | 兼容性测试 | meta (test) | ✅ |
| **Phase 3: 清理冗余反射（消除 R4 + R5 + 优化 R6）** | | | |
| P3-1 | 移除 CaffeineCache Class.forName（改用工厂方法 + NoClassDefFoundError） | cache | 📋 |
| P3-2 | 移除 RedisCache Class.forName（改用工厂方法 + NoClassDefFoundError） | cache | 📋 |
| P3-3 | TypeReference 增加 `of(Class)` 工厂方法 | cache | 📋 |
| P3-4 | Cache 接口增加 `Class<V>` 重载 | cache | 📋 |
| P3-5 | 回归测试 | all (test) | 📋 |

### 反射消除目标

| # | 文件 | 当前反射调用 | 消除方案 | 目标 |
|---|------|-------------|---------|------|
| R1 | `EntityMetaRegistry` | `Class.forName()` + `Method.invoke()` | ServiceLoader\<EntityMetaProvider\> | 接口直接调用 |
| R2 | `TransactionAdvisorRegistry` | `Class.forName()` + `Field.get()` | ServiceLoader\<TransactionAdvisorProvider\> | 接口直接调用 |
| R3 | `TransactionInterceptor` | `method.invoke(target, args)` | APT 代理子类直接调用 + LambdaMetafactory 降级 | 零反射 |
| R4 | `CaffeineCache` | `Class.forName()` | 工厂方法 + NoClassDefFoundError | 零反射 |
| R5 | `RedisCache` | `Class.forName()` | 工厂方法 + NoClassDefFoundError | 零反射 |
| R6 | `TypeReference` | `getGenericSuperclass()` | 保留 + 增加 Class 重载 | 保留（Java 泛型唯一方案） |
| R7 | `HormTransactionalBeanPostProcessor` | `Proxy.newProxyInstance()` | APT 代理子类 | 零反射 |
| R8 | `HormTransactionalBeanPostProcessor` | `getDeclaredMethods()` + `isAnnotationPresent()` | APT 代理子类 | 零反射 |

### 性能预期

| 调用方式 | ns/op | 相对直接调用 |
|---------|-------|------------|
| 直接调用 | ~1.2 | 1.0x |
| **APT 生成代理（目标）** | **~1.2** | **1.0x** |
| LambdaMetafactory 降级 | ~3.1 | 2.6x |
| Method.invoke()（当前） | ~8.5 | 7.1x |

### 关键设计决策

1. **APT 生成代理子类（Micronaut 模式）**：在编译期为含 `@Transactional` 方法的类生成代理子类，将事务拦截逻辑完全内联到生成的代码中。运行时无需 `Method.invoke`、无需 `Proxy.newProxyInstance`。此方案与 Micronaut 的编译期 AOP 模式本质相同。
2. **委托模式**：代理子类持有 `delegate` 字段引用原始 Bean，而非在子类中重新注入字段。避免 Spring 字段注入问题。
3. **TransactionProxyFactory SPI**：APT 为每个代理类生成工厂类，实现 `TransactionProxyFactory` 接口。`HormTransactionalBeanPostProcessor` 通过 ServiceLoader 发现工厂类并直接调用 `create(delegate, ctx)`，消除 `Constructor.newInstance`。
4. **LambdaMetafactory 降级路径**：对于无法生成 APT 代理的场景（final 类/方法、第三方类），`TransactionInterceptor` 使用 LambdaMetafactory 运行时桥接替代 `Method.invoke`，性能约 ~3.1ns/op（vs Method.invoke ~8.5ns/op）。
5. **ServiceLoader 替代自定义索引**：`EntityMetaRegistry` / `TransactionAdvisorRegistry` 从自定义 `entities.idx` / `transactions.idx` + `Class.forName` + 反射调用，迁移到 JDK 标准 `ServiceLoader` + 接口直接调用。保留自定义索引作为兼容回退。
6. **TypeReference 保留**：超级类型令牌（`getGenericSuperclass()`）是 Java 生态处理泛型擦除的唯一标准方案（Jackson/Guava/Spring 均采用相同模式），不存在零反射替代。增加 `of(Class)` 工厂方法简化简单类型场景。
7. **final 类/方法处理**：APT 在编译期检测 `final` 类/方法，发出 WARNING 日志；运行时降级到 LambdaMetafactory 桥接。

### 设计文档

详见 [docs/09-zero-reflection-optimization.md](./09-zero-reflection-optimization.md)

---

## 后续里程碑概览

| 里程碑 | 主题 | 预计 |
|--------|------|------|
| M8.5 | 数据库方言适配（MySQL/PostgreSQL/H2） | ✅ 已完成 |
| M8.7 | 零反射优化 — 编译期代理生成与反射消除 | 2026 Q3 |
| M9 | 性能基准与 GA 发布 | 2027 Q1-Q2 |

---

## 开发环境约定

- **JDK**：17
- **构建**：Maven 3.6.3+，`mvn -pl <module> verify -Pskip-enforcer` 验证
- **测试**：JUnit 5 + AssertJ + Mockito（单元）+ H2 内存库（集成）
- **覆盖率**：JaCoCo，processor 包 > 80%，集成测试 100% 通过
- **Git**：Conventional Commits（`<type>(<scope>): <subject>`），分支 `feature/<milestone>-<topic>`
- **APT 生成类位置**：`<实体包>.generated`
- **H2 测试库**：`jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1`（Testcontainers Docker 已禁用）

---

## 接续点（下次开发从这里开始）

1. **M8.5 收尾**（如未完成）：
   - 将 `feature/m8.5-dialect` squash merge 到 `main`
   - 打 tag `v1.0.0-M8.5`
2. **启动 M8.7**：新建分支 `feature/m8.7-zero-reflection`
   - 必读 `docs/09-zero-reflection-optimization.md`（设计文档）
   - Phase 1 优先：实现 `TransactionProxyBuilder` + 改造 `HormTransactionalBeanPostProcessor`
   - 验证命令：`mvn -pl holo-horm-meta,holo-horm-core,holo-horm-spring-boot-starter -am verify -Pskip-enforcer`
