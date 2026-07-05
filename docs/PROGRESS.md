# HORM 开发进度

> 接续点文档：记录各里程碑完成状态、关键决策、下一步计划。
> 维护人：Holo Framework Team · 更新时间：2026-07-05

---

## 当前状态

| 里程碑 | 状态 | 完成时间       | Tag        |
|--------|------|----------------|------------|
| M1     | ✅ 完成 | 2026-07-04     | v1.0.0-M1  |
| M2     | ✅ 完成 | 2026-07-04     | v1.0.0-M2  |
| M3     | ✅ 完成 | 2026-07-05     | v1.0.0-M3  |
| M4     | ✅ 完成 | 2026-07-05     | v1.0.0-M4  |
| M5-M9  | 📋 规划中 | —              | —          |

**当前分支**：`feature/m4-transactions`（M4 完成后待 squash merge 到 `main`）

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
| M4-3 | `TransactionMethodMeta` + `TransactionAdvisorBuilder`（编译期生成事务代理类） + `IndexWriter` 扩展 | `5a87281` |
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

1. **APT 编译期 AOP 织入**：`TransactionAdvisorBuilder` 在编译期为 `@Transactional` 方法生成代理类，避免运行时字节码增强依赖（如 Spring AOP / ByteBuddy）。
2. **双重事务边界**：同时支持 `@Transactional` 声明式和 `Horm.tx()` 编程式事务。
3. **REQUIRED + REQUIRES_NEW 传播**：REQUIRED 加入现有事务或创建新事务；REQUIRES_NEW 始终创建新事务并挂起现有事务。
4. **注解驱动 + 显式 cascade 双模式**：`save()`/`delete()` 自动级联带 `CascadeType.PERSIST`/`REMOVE`/`ALL` 的关联；`saveWith()`/`deleteWith()` 显式指定级联关联名。
5. **Query 风格 + Model 静态批量 API**：`Model.update(Class)`/`Model.delete(Class)` 返回 `UpdateQuery`/`DeleteQuery` 流畅 API。
6. **@Version 乐观锁**：仅支持 int/Integer/long/Long；版本字段 `insertable=false, updatable=false`（框架管理）；UPDATE/DELETE 添加 `WHERE version = ?` + 受影响行数检测 + `OptimisticLockException`。
7. **单数据源 + SPI 钩子**：M4 仅单数据源，`DataSourceProvider` SPI 为 M5 多数据源预留扩展点。

### 已知限制（M4 范围内）

- 不支持 `NESTED`/`SUPPORTS`/`NOT_SUPPORTED`/`MANDATORY`/`NEVER` 传播级别 —— 仅 REQUIRED + REQUIRES_NEW
- 不支持 `MERGE`/`DETACH`/`REFRESH` cascade 操作 —— 仅 `PERSIST`/`REMOVE`/`ALL` 实际生效
- `@Transactional` 仅支持类级别和方法级别，不支持接口继承
- 乐观锁仅在 `JdbcRepository.update` 和 `delete(entity)` 中生效，`deleteById` 不检查版本
- 不支持 `INSERT ... ON DUPLICATE KEY UPDATE` —— 留待 M5
- 不支持批量 `IN` 参数上限校验 —— 留待后续

---

## 后续里程碑概览

| 里程碑 | 主题 | 预计 |
|--------|------|------|
| M5 | 多数据源 SPI 与路由 | 2026 Q3 |
| M6 | 缓存链（L1 + L2 组合）+ batch loading | 2026 Q4 |
| M7 | 数据库迁移（Flyway 集成） | 2026 Q4 |
| M8 | Spring Boot Starter | 2027 Q1 |
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

1. **可选**：将 `feature/m4-transactions` squash merge 到 `main`：
   ```bash
   git -C e:\project\Holo\holo-horm checkout main
   git -C e:\project\Holo\holo-horm merge --squash feature/m4-transactions
   git -C e:\project\Holo\holo-horm commit -m "feat(m4): squash merge transaction, cascade, batch, optimistic locking"
   git -C e:\project\Holo\holo-horm tag -a v1.0.0-M4 -m "M4: ..."
   ```
2. **启动 M5**：新建分支 `feature/m5-datasource`，实现多数据源 SPI 与路由
