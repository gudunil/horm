# HORM 开发进度

> 接续点文档：记录各里程碑完成状态、关键决策、下一步计划。
> 维护人：Holo Framework Team · 更新时间：2026-07-04

---

## 当前状态

| 里程碑 | 状态 | 完成时间       | Tag        |
|--------|------|----------------|------------|
| M1     | ✅ 完成 | 2026-07-04     | v1.0.0-M1  |
| M2     | ⏳ 待开始 | —              | —          |
| M3-M9  | 📋 规划中 | —              | —          |

**当前分支**：`feature/m1-meta-spi`（M1 完成后待 squash merge 到 `main`）

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

## M2: 查询构建器与条件查询（待开始）

### 目标

为 `Repository` 添加类型安全的链式查询 API，基于 APT 生成的 `XxxQueryMeta` TypedField 常量。

### 计划交付

- `Query<T>` 流畅 API：`select`/`where`/`orderBy`/`limit`/`offset`
- `Predicate` 组合：`eq`/`ne`/`gt`/`lt`/`like`/`in`/`between`/`isNull`/`isNotNull`
- `AND`/`OR`/`NOT` 逻辑组合
- `JdbcRepository` 集成 `Query<T>` 执行器
- `HormException` 错误路径测试

### 关键决策点（待 M2 启动时确认）

1. Query API 风格：JPA Criteria 风格 vs jOOQ 风格 vs MyBatis-Plus LambdaQueryWrapper 风格
2. 是否支持子查询、JOIN（M2 范围 vs 留到 M3 关联）
3. 排序方向、分页 API 形状
4. 是否引入 `Optional<T>` 返回值

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

1. **可选**：将 `feature/m1-meta-spi` squash merge 到 `main`：
   ```bash
   git -C e:\project\Holo\holo-horm checkout main
   git -C e:\project\Holo\holo-horm merge --squash feature/m1-meta-spi
   git -C e:\project\Holo\holo-horm commit -m "feat(m1): squash merge metadata SPI & APT pipeline"
   # 重新打 tag 到 main HEAD（如需）
   git -C e:\project\Holo\holo-horm tag -d v1.0.0-M1
   git -C e:\project\Holo\holo-horm tag -a v1.0.0-M1 -m "M1: ..."
   ```
2. **启动 M2**：新建分支 `feature/m2-query-builder`，参考本文档 "M2 计划交付" 章节
3. **优先修复**：`HormException` 覆盖率 0% —— 在 M2 错误路径测试中补充
