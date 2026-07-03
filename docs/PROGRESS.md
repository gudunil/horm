# HORM 开发进度

> 接续点文档：记录各里程碑完成状态、关键决策、下一步计划。
> 维护人：Holo Framework Team · 更新时间：2026-07-04

---

## 当前状态

| 里程碑 | 状态 | 完成时间       | Tag        |
|--------|------|----------------|------------|
| M1     | ✅ 完成 | 2026-07-04     | v1.0.0-M1  |
| M2     | ✅ 完成 | 2026-07-04     | v1.0.0-M2  |
| M3-M9  | 📋 规划中 | —              | —          |

**当前分支**：`feature/m2-query-builder`（M2 完成后待 squash merge 到 `main`）

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

## 后续里程碑概览

| 里程碑 | 主题 | 预计 |
|--------|------|------|
| M3 | 关联关系（`@OneToMany`/`@ManyToOne`/`@ManyToMany`） | 2026 Q3 |
| M4 | 事务管理（`@Transactional` AOP） | 2026 Q3 |
| M5 | 多数据源 SPI 与路由 | 2026 Q4 |
| M6 | 缓存链（L1 + L2 组合） | 2026 Q4 |
| M7 | 数据库迁移（Flyway 集成） | 2027 Q1 |
| M8 | Spring Boot Starter | 2027 Q1 |
| M9 | 性能基准与 GA 发布 | 2027 Q2-Q3 |

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

1. **可选**：将 `feature/m2-query-builder` squash merge 到 `main`：
   ```bash
   git -C e:\project\Holo\holo-horm checkout main
   git -C e:\project\Holo\holo-horm merge --squash feature/m2-query-builder
   git -C e:\project\Holo\holo-horm commit -m "feat(m2): squash merge query builder and Condition DSL"
   # 重新打 tag 到 main HEAD（如需）
   git -C e:\project\Holo\holo-horm tag -d v1.0.0-M2
   git -C e:\project\Holo\holo-horm tag -a v1.0.0-M2 -m "M2: ..."
   ```
2. **启动 M3**：新建分支 `feature/m3-relations`，实现 `@OneToMany`/`@ManyToOne`/`@ManyToMany` 关联关系映射，并补全 `select` 投影与 JOIN/子查询支持
3. **可选优化**：为 `Query<T>` 增加批量 `IN` 参数上限校验、`Page<T>` 分页对象、`UPDATE/DELETE ... WHERE` 等扩展（按需）
