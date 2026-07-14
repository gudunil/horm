[根目录](../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **16-function-dsl.md**

# HORM 函数 DSL 设计文档（M11）

> 状态：设计草案 v3（兼容性审查全量修复）
> 起草时间：2026-07-14
> 最近修订：2026-07-14（v3：修复 28 项兼容性问题，详见 §14 变更记录）
> 目标里程碑：M11（Function & Projection DSL）
> 前置里程碑：M8.5（方言适配）、M8.7（零反射优化）、M10（性能优化与风险修复）

---

## 1. 背景与目标

### 1.1 问题陈述

HORM 当前 Query API（[Query.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/query/Query.java)）只覆盖了"实体级 CRUD + 类型安全过滤"这一层：

| 已支持 | 不支持 |
|--------|--------|
| `where` / `and` / `or` / `not` | SQL 函数（UPPER / LOWER / DATE_FORMAT / NOW() …） |
| `orderBy` + `limit` / `offset` | 聚合函数（SUM / AVG / MAX / MIN，仅有 COUNT） |
| `fetch` / `leftJoin` / `innerJoin` / `join`（仅 APT 声明的关联） | GROUP BY / HAVING |
| `select(TypedField...)` 字段投影（必须含 id，返回实体） | 任意投影（`SELECT dept_id, SUM(amount) AS total`） |
| `count()` / `exists()` | 子查询、原生 SQL 逃生舱 |

设计哲学是"APT 编译期可静态推导 → 零运行时反射"，这保证了类型安全，但代价是**任何动态 SQL 或函数表达式都没有入口**。需要 `SUM(amount) GROUP BY dept HAVING COUNT(*) > 5`、`WHERE DATE(created_at) = ?` 这类场景时，目前完全无法表达。

### 1.2 设计目标

1. **保持类型安全**：聚合表达式、函数表达式都应是带泛型的 `Expr<T>`，编译期可推导返回类型
2. **跨方言可移植**：MySQL `DATE_FORMAT` / PostgreSQL `TO_CHAR` / H2 `FORMATDATETIME` 等差异由 Dialect 层吸收
3. **与现有体系无缝衔接**：复用 `Condition.sqlFragment() + bindings()` 二元组模式、`TypedField` 类型常量、APT 生成的元模型、`Row` 行抽象
4. **保留逃生舱**：对极端场景提供 `raw(String, Object...)` 入口，但需明确标注"绕过类型安全"
5. **零破坏向后兼容**：所有新 API 都是增量，现有 `Query.count()` / `Query.list()` 行为不变；`Row` 接口扩展通过默认方法实现
6. **AOT 兼容**：无运行时字节码生成，保持 GraalVM Native Image 友好

### 1.3 非目标

- 不实现完整 SQL 子查询（如 `WHERE id IN (SELECT ...)`），子查询仅限 HAVING 内的标量子查询
- 不替代 jOOQ：HORM 不追求"SQL 全集覆盖"，只覆盖 90% 业务场景
- 不引入运行时字符串解析或 AST：所有表达式都是 Java 对象，由 APT 或 Dialect 渲染

---

## 2. 业界方案调研

### 2.1 方案对比矩阵

| 维度 | jOOQ | JPA CriteriaBuilder | MyBatis-Plus | Ebean | **HORM 选择** |
|------|------|---------------------|--------------|-------|---------------|
| 类型安全 | ✅ 完全 | ✅ 完全（StaticMetamodel） | ❌ 字符串为主 | ⚠️ 部分 | ✅ 完全（APT 生成） |
| 函数覆盖 | ✅ 200+ 函数 | ✅ 30+ 内置 + `function()` 逃生舱 | ⚠️ 字符串拼接 | ⚠️ 字符串拼接 | ✅ 30+ 内置 + `Functions.raw` 逃生舱 |
| 聚合支持 | ✅ `DSL.sum(field)` | ✅ `cb.sum(path)` | ✅ `select("sum(...)")` | ✅ 字符串 | ✅ `Aggregates.sum(field)` |
| GROUP BY/HAVING | ✅ | ✅ | ✅ | ✅ | ✅ |
| 方言适配 | ✅ Dialect 内置 | ✅（通过 Hibernate Dialect） | ❌ 手写 | ⚠️ | ✅ Dialect 函数映射 |
| 学习曲线 | 陡 | 陡（API 啰嗦） | 平 | 平 | 中（接近现有 Query DSL） |
| AOT 兼容 | ✅ | ⚠️ 动态代理 | ✅ | ⚠️ 反射 | ✅ |

### 2.2 关键借鉴点

- **从 jOOQ** 借鉴：静态工厂方法 `DSL.sum(field)` → `Aggregates.sum(field)` 返回强类型 `AggExpr<T>`；用泛型承载返回类型（`sum` 返回 `Expr<BigDecimal>`，`count` 返回 `Expr<Long>`）
- **从 JPA CriteriaBuilder** 借鉴：`cb.function(name, returnType, args)` 逃生舱 → `Functions.raw(sqlFragment, returnType, bindings)`；HAVING 接受 `Expression<Boolean>` 而非纯 Condition
- **从 MyBatis-Plus** 借鉴反例：字符串 `select("sum(amount) as total")` 失去类型安全，HORM 必须避免；但保留 `raw` 逃生舱承认其灵活性
- **从 Ebean** 借鉴：`findSingleAttribute()` 投影到单一标量，HORM 设计 `listScalar(Class)` 入口
- **从 HORM 现有 `TypedField`** 借鉴：默认方法 `eq/ne/gt/lt/...` 直接委托 `Conditions` 的模式，新 `ComparableExpr<T>` 沿用此模式让函数表达式支持链式比较

### 2.3 参考文档

- jOOQ 函数 DSL：<https://www.jooq.org/doc/3.19/manual-single-page/#functions>
- JPA CriteriaBuilder API：<https://docs.oracle.com/javaee/7/api/javax/persistence/criteria/CriteriaBuilder.html>
- MyBatis-Plus Wrapper：<https://baomidou.com/guides/wrapper/>

---

## 3. 设计原则

| 编号 | 原则 | 说明 |
|------|------|------|
| P1 | 类型安全优先 | 聚合 / 函数表达式都带泛型，返回类型在编译期可知 |
| P2 | Dialect 吸收方言差异 | 上层 API 跨方言一致，底层由 `Dialect.functionSql()` 翻译 |
| P3 | APT 生成聚合字段级常量 | `XxxAggMeta.SUM_AMOUNT` 这种聚合字段级常量由 APT 自动生成；标量函数因参数动态、数量多不做常量化 |
| P4 | sqlFragment + bindings 不变 | 新表达式复用现有渲染协议，最小化对 QueryImpl 的改动 |
| P5 | 投影查询复用 Row | 不引入 Tuple，扩展 [Row.java](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/Row.java) 增加按 `Expr` 取值的默认方法 |
| P6 | 逃生舱显式标注 | `Functions.raw` / `Conditions.raw` 在 Javadoc 中明确"绕过类型安全" |
| P7 | 向后兼容零破坏 | 不修改现有接口方法签名，`Query` / `Dialect` / `Row` / `Repository` / `Horm` 全部通过新增方法扩展 |
| P8 | 命名对齐现有风格 | 工厂类用复数名词（`Aggregates` / `Functions`，对齐 `Conditions`）；Horm 入口用完整动词（`rawSql()`，对齐 `install`/`migrate`） |
| P9 | 统一类型系统 | `TypedField<E, T>` 继承 `Expr<T>`，消除 `TypedField` 与 `Expr` 的类型割裂，使函数 API 无缝接受字段常量 |
| P10 | 异常分层对齐 | 运行时数据访问错误 → `HormException`；API 参数校验失败 → `IllegalArgumentException`（对齐 M2 决策） |

---

## 4. 整体架构

```
┌────────────────────────────────────────────────────────────┐
│  用户 API 层（holo-horm-core/query）                       │
│  ┌──────────────┐                                          │
│  │  Query<T>    │  + groupBy() / having()                 │
│  │  + selectExpr│──→ ProjectionQuery                      │
│  └──────┬───────┘    + groupBy / having / listRows /      │
│         │              listScalar / firstRow               │
│  ┌──────▼──────────────────────────────┐                   │
│  │  Aggregates / Functions /            │  ← 表达式工厂     │
│  │  Conditions.rawWithLeading           │  (对齐 Conditions)│
│  └──────┬──────────────────────────────┘                   │
└─────────┼──────────────────────────────────────────────────┘
          │
┌─────────▼──────────────────────────────────────────────────┐
│  元模型层（holo-horm-meta）                                 │
│  ┌────────────────────────────────────────────┐             │
│  │  Expr<T>            (表达式根接口)          │             │
│  │  ↑ TypedField<E,T>  (已有，扩展实现 Expr)  │             │
│  │  ↑ ComparableExpr<T>(Comparable 表达式)    │             │
│  │  ↑ ComparableField  (已有，天然兼容)       │             │
│  │  AggExpr<T>         (聚合表达式)            │             │
│  │  FuncExpr<T>        (标量函数表达式)        │             │
│  │  FunctionType       (函数枚举，跨方言)      │             │
│  └────────────────────────────────────────────┘             │
│  ┌────────────────────────────────────────────┐             │
│  │  XxxAggMeta       (APT 生成，字段级常量)    │             │
│  │  XxxQueryMeta     (已有，扩展实现 Expr)     │             │
│  │  Row              (已有，扩展 get(Expr))    │             │
│  └────────────────────────────────────────────┘             │
└─────────┬──────────────────────────────────────────────────┘
          │
┌─────────▼──────────────────────────────────────────────────┐
│  方言层（holo-horm-core/dialect）                          │
│  ┌────────────────────────────────────────────┐             │
│  │  Dialect.functionSql(FunctionType, args)   │  ← 新增方法 │
│  │  H2Dialect / MySqlDialect / PostgresDialect│             │
│  └────────────────────────────────────────────┘             │
└────────────────────────────────────────────────────────────┘
```

---

## 5. API 设计

### 5.1 表达式抽象（meta 模块新增）

新增 `com.holo.framework.horm.meta.query.expr` 包，承载所有表达式类型。命名风格对齐现有 `meta.query` 包下的 `Condition` / `Conditions` / `TypedField` / `ComparableField` 体系。

#### 5.1.1 Expr<T> —— 表达式根接口

```java
package com.holo.framework.horm.meta.query.expr;

/**
 * Renderable SQL expression with typed return value.
 *
 * <p>Like {@link com.holo.framework.horm.meta.query.Condition}, an
 * {@code Expr} carries both the SQL text (with {@code ?} placeholders)
 * and the ordered bindings that fill those placeholders. Unlike
 * {@code Condition}, an {@code Expr} may return any Java type, not just
 * boolean.
 *
 * <p>{@link com.holo.framework.horm.meta.query.TypedField} implements
 * {@code Expr<T>}, so any API accepting {@code Expr<?>} also accepts
 * APT-generated {@code XxxQueryMeta} field constants directly.
 */
public interface Expr<T> {
    /** SQL text with {@code ?} placeholders, e.g. {@code "UPPER(t0.name)"} or {@code "SUM(t0.amount)"}. */
    String sqlFragment();

    /** Bindings for the {@code ?} placeholders, in fragment order. May contain {@code null}. */
    List<Object> bindings();

    /** Expression return type, used by {@link com.holo.framework.horm.meta.Row} typed accessors. */
    Class<T> javaType();

    /** Optional alias for projection column naming. */
    default String alias() { return null; }
}
```

`Expr<T>` 与现有 `Condition` 的关系：`Condition` 可视为 `Expr<Boolean>`，但为避免破坏现有 API（`Condition` 已被 `Conditions` / `CompositeCondition` 等大量引用），两者**并行存在**。`ComparableExpr` 默认方法产出的比较结果已是 `Condition`，无需额外桥接。

#### 5.1.2 TypedField 扩展实现 Expr<T>

为统一 `TypedField` 与 `Expr` 的类型系统，让 [TypedField.java](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/query/TypedField.java) 继承 `Expr<T>`。这使 `Functions.concat(UserQueryMeta.NAME, UserQueryMeta.EMAIL)` 等接受 `Expr<?>...` 的函数可直接传入字段常量，消除手动包装。

```java
public interface TypedField<E, T> extends Expr<T> {

    Class<E> entityType();

    String name();

    String column();

    Class<T> type();

    // —— Expr<T> 实现 ——

    /** 列名即 SQL 片段（无占位符、无 bindings）。 */
    @Override default String sqlFragment() { return column(); }

    /** TypedField 不含占位符，返回空列表。 */
    @Override default List<Object> bindings() { return List.of(); }

    /** 返回字段值类型。 */
    @Override default Class<T> javaType() { return type(); }

    // —— 现有默认方法保持不变 ——

    default Condition eq(T value) { return Conditions.eq(this, value); }
    default Condition ne(T value) { return Conditions.ne(this, value); }
    default Condition isNull() { return Conditions.isNull(this); }
    default Condition isNotNull() { return Conditions.isNotNull(this); }
    default Condition in(Collection<T> values) { return Conditions.in(this, values); }
}
```

> 向后兼容性：所有现有 `TypedField` 实现类（`StringField`/`LongField`/...）只需添加 4 个 `default` 方法实现，零破坏。`ComparableField extends TypedField` 自动继承 `Expr<T>`，天然兼容。

#### 5.1.3 ComparableExpr<T> —— 可比较表达式

对齐现有 [ComparableField.java](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/query/ComparableField.java) 的 `gt/lt/ge/le/between` 模式，让 `Func.year(field).gt(2026)` 这样的链式调用成为可能：

```java
package com.holo.framework.horm.meta.query.expr;

/**
 * Expression whose return type is {@link Comparable}, supporting
 * comparison operators via default methods (mirrors
 * {@link com.holo.framework.horm.meta.query.ComparableField}).
 */
public interface ComparableExpr<T extends Comparable<T>> extends Expr<T> {

    default Condition gt(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " > ?", bindings(), value);
    }

    default Condition lt(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " < ?", bindings(), value);
    }

    default Condition ge(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " >= ?", bindings(), value);
    }

    default Condition le(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " <= ?", bindings(), value);
    }

    default Condition between(T low, T high) {
        return Conditions.rawWithLeading(sqlFragment() + " BETWEEN ? AND ?", bindings(), low, high);
    }

    default Condition eq(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " = ?", bindings(), value);
    }

    default Condition ne(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " <> ?", bindings(), value);
    }

    default Condition isNull() {
        return Conditions.raw(sqlFragment() + " IS NULL", bindings());
    }

    default Condition isNotNull() {
        return Conditions.raw(sqlFragment() + " IS NOT NULL", bindings());
    }
}
```

> 实现注意：`ComparableExpr` 默认方法使用 `Conditions.rawWithLeading(fragment, leadingBindings, trailingValues...)` 组合前置 bindings 和后置值。此方法仅在 `ComparableExpr` 和内部渲染中使用，不作为公共 API 暴露，避免与 `raw(String, Object...)` 重载歧义。

#### 5.1.4 AggExpr<T> —— 聚合表达式

```java
public interface AggExpr<T> extends Expr<T> {
    FunctionType functionType();
    TypedField<?, ?> target();  // null 表示 COUNT(*)
}

/**
 * Factory for aggregate {@link AggExpr} instances. Naming follows
 * the {@link Conditions} convention (plural noun, final class,
 * private constructor, static methods only).
 */
public final class Aggregates {
    private Aggregates() {}

    /** COUNT(*) — 渲染时 argSqlFragments 为空，Dialect 负责输出 {@code COUNT(*)}。 */
    public static AggExpr<Long> count() { ... }

    public static AggExpr<Long> count(TypedField<?, ?> field) { ... }             // COUNT(field)
    public static AggExpr<Long> countDistinct(TypedField<?, ?> field) { ... }     // COUNT(DISTINCT field)

    /**
     * SUM 聚合。跨方言统一返回 {@code BigDecimal} 以避免整数溢出
     *（MySQL SUM(int) 返回 DECIMAL，PG SUM(bigint) 返回 BIGINT，
     * 统一 BigDecimal 最安全）。
     */
    public static <N extends Number> AggExpr<BigDecimal> sum(TypedField<?, N> field) { ... }

    public static <N extends Number> AggExpr<BigDecimal> avg(TypedField<?, N> field) { ... }
    public static <T extends Comparable<T>> AggExpr<T> max(TypedField<?, T> field) { ... }
    public static <T extends Comparable<T>> AggExpr<T> min(TypedField<?, T> field) { ... }

    /**
     * Attach an alias to any aggregate expression (for projection naming).
     * <p>Returns a new immutable {@code AggExpr} instance; the original
     * is unchanged (consistent with {@code TypedField} immutability).
     */
    public static <T> AggExpr<T> alias(AggExpr<T> expr, String alias) { ... }
}
```

#### 5.1.5 FuncExpr<T> —— 标量函数表达式

```java
public interface FuncExpr<T> extends Expr<T> {
    FunctionType functionType();
    List<Expr<?>> arguments();
}

/**
 * Factory for scalar function {@link FuncExpr} / {@link ComparableExpr}
 * instances. Naming follows the {@link Conditions} convention.
 */
public final class Functions {
    private Functions() {}

    // —— 字符串函数（返回 ComparableExpr<String>，支持链式比较）——
    public static ComparableExpr<String> upper(TypedField<?, String> field) { ... }
    public static ComparableExpr<String> lower(TypedField<?, String> field) { ... }
    public static ComparableExpr<String> trim(TypedField<?, String> field) { ... }
    public static ComparableExpr<String> substring(TypedField<?, String> field, int start, int length) { ... }
    /** Concatenates multiple expressions. Accepts {@code Expr<?>} arguments, so
     *  {@code TypedField} constants (which implement {@code Expr}) can be passed directly. */
    public static ComparableExpr<String> concat(Expr<?>... parts) { ... }
    public static ComparableExpr<Integer> length(TypedField<?, String> field) { ... }

    // —— 数学函数 ——
    public static <N extends Number> ComparableExpr<N> abs(TypedField<?, N> field) { ... }
    public static ComparableExpr<Integer> round(TypedField<?, ? extends Number> field, int scale) { ... }
    public static ComparableExpr<Integer> floor(TypedField<?, ? extends Number> field) { ... }
    public static ComparableExpr<Integer> ceil(TypedField<?, ? extends Number> field) { ... }

    // —— 日期函数（跨方言由 Dialect 翻译）——
    /** NOW() / CURRENT_TIMESTAMP。返回 Instant 类型，Row.getInstant 负责类型转换。 */
    public static ComparableExpr<Instant> now() { ... }

    /**
     * 日期格式化。pattern 参数以 Java 日期格式书写（如 {@code "yyyy-MM-dd"}），
     * Dialect 层在渲染时翻译为各数据库的本地格式，pattern 作为 binding
     * 传入（已翻译后），避免 SQL 注入风险。
     */
    public static ComparableExpr<String> dateFormat(TypedField<?, Instant> field, String pattern) { ... }
    public static ComparableExpr<Integer> year(TypedField<?, Instant> field) { ... }
    public static ComparableExpr<Integer> month(TypedField<?, Instant> field) { ... }
    public static ComparableExpr<Integer> day(TypedField<?, Instant> field) { ... }

    // —— 控制流函数 ——
    public static <T> Expr<T> coalesce(Expr<T> first, Expr<T> second) { ... }
    public static <T> Expr<T> nullif(Expr<T> a, Expr<T> b) { ... }

    // —— 逃生舱：原生 SQL 片段 ——
    /**
     * Bypass type safety and embed a raw SQL fragment.
     * <p><strong>WARNING:</strong> No compile-time validation; user is
     * responsible for matching placeholders with bindings.
     */
    public static <T> Expr<T> raw(String sqlFragment, Class<T> returnType, Object... bindings) { ... }
}
```

> 设计要点：标量函数返回 `ComparableExpr<T>` 而非 `FuncExpr<T>`，使其在 HAVING 中可直接 `.gt(...) / .lt(...)` 链式调用，对齐现有 `TypedField` 体验。`FuncExpr<T>` 仅作为内部接口保留用于类型区分（通过 `instanceof` 判断）。

#### 5.1.6 FunctionType 枚举

```java
public enum FunctionType {
    // 聚合
    COUNT, COUNT_DISTINCT, SUM, AVG, MAX, MIN,
    // 字符串
    UPPER, LOWER, TRIM, SUBSTRING, CONCAT, LENGTH,
    // 数学
    ABS, ROUND, FLOOR, CEIL,
    // 日期
    NOW, DATE_FORMAT, YEAR, MONTH, DAY,
    // 控制流
    COALESCE, NULLIF,
    // 逃生舱
    RAW
}
```

> 枚举值与 `Functions` 工厂方法一一对应：每个枚举值都有对应的工厂入口。`RAW` 作为逃生舱标记保留，永不进入 `Dialect.functionSql()`。

### 5.2 Query 接口扩展

在现有 [Query.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/query/Query.java) 上增量扩展：

```java
public interface Query<T extends Model<T>> {
    // —— 现有方法保持不变 ——
    Query<T> where(Condition... conditions);
    Query<T> orderBy(TypedField<T, ?> field, Order direction);
    Query<T> limit(long limit);
    Query<T> offset(long offset);
    Query<T> select(TypedField<T, ?>... fields);
    List<T> list();
    long count();
    // ...

    // —— M11 新增方法 ——

    /**
     * GROUP BY 子句（按字段分组）。可多次调用追加字段。
     *
     * @throws HormException if any field's entityType does not match this query's T
     *         (consistent with {@link #orderBy} cross-entity validation)
     */
    Query<T> groupBy(TypedField<T, ?>... fields);

    /**
     * GROUP BY 子句（按表达式分组），支持函数分组如
     * {@code GROUP BY YEAR(created_at)}。
     */
    Query<T> groupBy(Expr<?>... expressions);

    /** HAVING 子句，接受 Condition（由 ComparableExpr 比较方法产生）。 */
    Query<T> having(Condition... conditions);

    /**
     * Switch to projection mode. Returns a {@link ProjectionQuery} that
     * carries forward the current WHERE / ORDER BY / LIMIT / OFFSET /
     * GROUP BY / HAVING state and adds the specified projection expressions.
     *
     * <p>Once invoked, the returned {@link ProjectionQuery} supports
     * further {@code groupBy}/{@code having} calls and terminal methods
     * ({@code listRows}/{@code listScalar}/{@code firstRow}).
     *
     * <p>Named {@code selectExpr} (not {@code selectAgg}) because it
     * accepts any {@link Expr}, including scalar {@link FuncExpr} like
     * {@code Functions.upper(field)}.
     *
     * <p>After calling {@code selectExpr}, calling {@code fetch} /
     * {@code leftJoin} / {@code innerJoin} / {@code join} throws
     * {@link HormException} (projection and eager fetch are mutually
     * exclusive).
     */
    ProjectionQuery selectExpr(Expr<?>... projections);
}
```

### 5.3 ProjectionQuery —— 投影查询

聚合查询不返回实体，而是返回行 / 标量。**复用现有 [Row](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/Row.java) 接口**（已含 `getLong/Integer/String/Instant/BigDecimal/Boolean/getEnum/asMap` 等完整 typed accessor），不引入新的 `Tuple` 类型：

```java
package com.holo.framework.horm.core.query;

public interface ProjectionQuery {
    /** 追加 GROUP BY 字段。 */
    ProjectionQuery groupBy(Expr<?>... expressions);

    /** 追加 HAVING 条件。 */
    ProjectionQuery having(Condition... conditions);

    /** 每一行作为一个 Row 返回（含按 Expr 引用取值的能力）。 */
    List<Row> listRows();

    /**
     * 单列投影的快捷取值。要求 projections 长度为 1。
     * @throws IllegalArgumentException if projections length ≠ 1
     */
    <S> List<S> listScalar(Class<S> scalarType);

    /** 取首行。 */
    Optional<Row> firstRow();
    <S> Optional<S> firstScalar(Class<S> scalarType);
}
```

> 状态传递机制：`Query.selectExpr` 采用**快照传递**——创建 `ProjectionQueryImpl` 时拷贝 `QueryImpl` 已积累的 where/orderBy/limit/offset/groupBy/having 状态。后续对 `Query` 的修改不影响已产出的 `ProjectionQuery`，反之亦然。

### 5.4 Row 接口扩展（向后兼容的默认方法）

在现有 [Row.java](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/Row.java) 新增默认方法，按 `Expr` 引用取值，保持类型安全：

```java
public interface Row {
    // —— 现有方法保持不变 ——
    String table();
    boolean has(String column);
    Object get(String column);
    Long getLong(String column);
    // ...

    // —— M11 新增默认方法 ——

    /**
     * Whether a column is present in this row (key exists, regardless of null value).
     * Unlike {@link #has(String)} which returns false for SQL NULL values,
     * this method returns true as long as the column key exists.
     * This distinguishes "column absent" from "column is NULL", consistent
     * with {@code ResultSet.findColumn()} vs {@code ResultSet.wasNull()}.
     */
    default boolean contains(String column) {
        return asMap().containsKey(column);
    }

    /**
     * Typed accessor by expression reference. The expression's alias
     * (or generated column name) is used to look up the value, and the
     * expression's {@link Expr#javaType()} drives the typed accessor.
     *
     * @throws HormException if the expression has no resolvable alias or
     *         the column is absent from the row (runtime data access error,
     *         consistent with SQLException on missing column in ResultSet)
     */
    default <T> T get(Expr<T> expr) {
        String column = expr.alias() != null
            ? expr.alias()
            : ExprColumnResolver.resolve(expr);  // fallback to generated name
        if (!contains(column)) {
            throw new HormException("Column '" + column + "' not present in row");
        }
        return ExprAccessor.get(this, column, expr.javaType());
    }
}
```

> `ExprColumnResolver` 与 `ExprAccessor` 是 meta 模块新增的 **public** 工具类（`com.holo.framework.horm.meta` 包，与 `Row` 同包），负责从 `Expr` 推导列别名 + 按类型路由到 `getLong/getString/...` 等访问器。`ExprAccessor.get` 采用 if-else 链路由（AOT 兼容，无反射），复用 `Row.getInstant` 等已有方法处理类型转换。`MapRow` 无需改动，自动继承默认方法。

> `Row.get(int index, Class<T>)` 未纳入本设计——`Row` 基于 `LinkedHashMap<String, Object>` 无索引访问能力，且列顺序取决于 Mapper 填充顺序，不保证与 SQL SELECT 列顺序一致。如需按位置取值，使用 `asMap()` 的迭代器。

### 5.5 Conditions.raw —— where 逃生舱

```java
public final class Conditions {
    // —— 现有方法保持不变 ——

    /**
     * Embed a raw SQL fragment as a Condition.
     * <p><strong>WARNING:</strong> Bypasses type safety. The user is
     * responsible for ensuring the fragment returns boolean and that
     * placeholder count matches bindings length.
     *
     * <pre>{@code
     * Model.query(User.class)
     *     .where(Conditions.raw("DATE(created_at) = ?", date))
     *     .list();
     * }</pre>
     */
    public static Condition raw(String sqlFragment, Object... bindings) { ... }

    /**
     * Internal variant that prepends an expression's pre-rendered
     * bindings before appending trailing comparison values.
     * Package-private — used by {@code ComparableExpr} default methods
     * and internal rendering only. Not exposed as public API to avoid
     * overload ambiguity with {@link #raw(String, Object...)}.
     */
    static Condition rawWithLeading(String sqlFragment, List<Object> leadingBindings, Object... trailingBindings) { ... }
}
```

> 原设计的 `raw(String, List<Object>, Object...)` 重载会导致调用歧义：`Conditions.raw("a > ?", someList)` 编译器优先匹配第二重载而非第一重载。v3 将内部重载改名为 `rawWithLeading` 并降为包级私有，消除公共 API 歧义。

### 5.6 Repository / Horm 原生 SQL 入口

为不支持 APT 投影的场景（动态表名、跨库 join）保留原生 SQL 入口。命名遵循现有 `Horm.install` / `Horm.repository` / `Horm.migrate` 的动词风格：

```java
public interface Repository<T extends Model<T>> {
    // —— 现有方法保持不变 ——

    /**
     * Execute a raw SQL query and map rows via the entity's Mapper.
     * @implNote Does not participate in cache chain (no APT metadata for CacheKey).
     */
    default List<T> rawQuery(String sql, Object... bindings) {
        throw new UnsupportedOperationException("rawQuery not implemented");
    }

    /**
     * Execute a raw SQL query with a custom row mapper.
     * @implNote Does not participate in cache chain (no APT metadata for CacheKey).
     */
    default <R> List<R> rawQuery(String sql, RowMapper<R> mapper, Object... bindings) {
        throw new UnsupportedOperationException("rawQuery not implemented");
    }
}

/**
 * Functional interface for mapping a {@link java.sql.ResultSet} row to a
 * domain object. Located in {@code com.holo.framework.horm.meta} alongside
 * {@link com.holo.framework.horm.meta.Mapper} (Mapper is a specialized
 * RowMapper bound to an entity type).
 */
@FunctionalInterface
public interface RowMapper<R> {
    R map(java.sql.ResultSet rs) throws java.sql.SQLException;
}

public final class Horm {
    /**
     * Global raw SQL entry point on the default datasource, not bound to
     * a specific entity type. Naming aligns with {@link #install}/
     * {@link #migrate} verb style.
     */
    public static RawSql rawSql() { ... }

    /**
     * Raw SQL entry point on the specified datasource.
     * Naming aligns with {@link #tx(String, Runnable)} datasource overload.
     */
    public static RawSql rawSql(String dataSourceName) { ... }
}

public interface RawSql {
    <R> List<R> query(String sql, RowMapper<R> mapper, Object... bindings);
    <R> Optional<R> queryOne(String sql, RowMapper<R> mapper, Object... bindings);
    long update(String sql, Object... bindings);
}
```

---

## 6. Dialect 函数映射层

### 6.1 Dialect 接口扩展

在 [Dialect.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/dialect/Dialect.java) 新增方法（带默认实现，对齐现有 `batchInsertSyntax()` / `supportsBatchInsertGeneratedKeysInOrder()` 的 default 风格）：

```java
public interface Dialect {
    // —— 现有方法保持不变 ——

    /**
     * Render a function call for this dialect.
     *
     * @param type function type (never {@link FunctionType#RAW} — RAW expressions
     *        are rendered directly via {@code Expr.sqlFragment()})
     * @param argSqlFragments 已渲染的参数 SQL 片段（不含函数名）；
     *        for {@code COUNT(*)}, this list is empty
     * @return 渲染后的函数表达式，例如 "UPPER(t0.name)" 或 "DATE_FORMAT(?, ?)"
     * @throws IllegalArgumentException if type is {@code RAW}
     */
    default String functionSql(FunctionType type, List<String> argSqlFragments) {
        if (type == FunctionType.RAW) {
            throw new IllegalArgumentException("FunctionType.RAW must not reach Dialect.functionSql(); "
                + "RAW expressions are rendered directly via Expr.sqlFragment()");
        }
        // COUNT(*) 特殊处理：空参数列表 → "COUNT(*)"
        if (type == FunctionType.COUNT && argSqlFragments.isEmpty()) {
            return "COUNT(*)";
        }
        // 默认实现使用 ANSI SQL 标准语法（适用大多数函数）
        return type.name() + "(" + String.join(", ", argSqlFragments) + ")";
    }

    /**
     * 方言是否支持该函数。不支持时调用方应抛 HormException。
     */
    default boolean supportsFunction(FunctionType type) {
        return true;
    }

    /**
     * Translate a Java date format pattern to this dialect's native pattern.
     * Called at expression construction time, before SQL rendering, so the
     * translated pattern flows into bindings as a normal parameter value
     * (not embedded in SQL text — prevents SQL injection).
     *
     * @param javaPattern Java date format, e.g. {@code "yyyy-MM-dd"}
     * @return dialect-specific pattern, e.g. {@code "%Y-%m-%d"} for MySQL
     */
    default String translateDateFormatPattern(String javaPattern) {
        return javaPattern; // default: no translation (ANSI)
    }
}
```

> **关键设计变更（v3）**：日期格式 pattern 不再嵌入 SQL 字面量，而是在表达式构造阶段通过 `Dialect.translateDateFormatPattern()` 翻译后作为 **binding** 传入。`Dialect.functionSql()` 仅返回 SQL 模板（如 `DATE_FORMAT(?, ?)`），pattern 作为绑定参数流入 `Expr.bindings()`。这消除了 SQL 注入风险，对齐 HORM"所有用户值通过 `?` 占位符绑定"的安全原则。

### 6.2 各方言实现要点

| FunctionType | H2 | MySQL | PostgreSQL |
|--------------|----|----|------------|
| COUNT(*) | `COUNT(*)` (默认处理空参数) | 同左 | 同左 |
| COUNT / SUM / AVG / MAX / MIN | 标准 | 标准 | 标准 |
| UPPER / LOWER / LENGTH | 标准 | 标准 | 标准 |
| TRIM | `TRIM(s)` | `TRIM(s)` | `TRIM(s)` |
| SUBSTRING | `SUBSTRING(s, start, len)` | `SUBSTRING(s, start, len)` | `SUBSTRING(s, start, len)` |
| CONCAT | `CONCAT(a, b)` | `CONCAT(a, b)` | `a \|\| b \|\| c` (需 override) |
| NOW | `CURRENT_TIMESTAMP` | `NOW()` | `CURRENT_TIMESTAMP` |
| DATE_FORMAT | `FORMATDATETIME(?, ?)` pattern 通过 binding | `DATE_FORMAT(?, ?)` pattern 通过 binding | `TO_CHAR(?, ?)` pattern 通过 binding |
| YEAR / MONTH / DAY | `EXTRACT(YEAR FROM ?)` | `YEAR(?)` | `EXTRACT(YEAR FROM ?)` |
| FLOOR / CEIL | `FLOOR(n)` / `CEIL(n)` | 同左 | 同左 |
| ROUND | `ROUND(n, scale)` | `ROUND(n, scale)` | `ROUND(n, scale)` |
| COALESCE | `COALESCE(a, b)` | 同左 | 同左 |
| NULLIF | `NULLIF(a, b)` | 同左 | 同左 |

> Pattern 翻译流程：用户写 `Functions.dateFormat(field, "yyyy-MM-dd")` → 构造时调用 `Dialect.translateDateFormatPattern("yyyy-MM-dd")` → MySQL 输出 `"%Y-%m-%d"` → 作为 binding 传入 → `Dialect.functionSql(DATE_FORMAT, [columnFragment, "?"])` → 渲染 `DATE_FORMAT(t0.created_at, ?)` → binding 列表含翻译后的 pattern 字符串。

### 6.3 实现示例：MySqlDialect

```java
@Override
public String functionSql(FunctionType type, List<String> args) {
    return switch (type) {
        case DATE_FORMAT -> "DATE_FORMAT(" + args.get(0) + ", ?)";  // pattern 作为 binding
        case YEAR -> "YEAR(" + args.get(0) + ")";
        case MONTH -> "MONTH(" + args.get(0) + ")";
        case DAY -> "DAY(" + args.get(0) + ")";
        case NOW -> "NOW()";
        case CONCAT -> "CONCAT(" + String.join(", ", args) + ")";   // MySQL 用 CONCAT()
        // 其他函数走默认 ANSI 实现
        default -> Dialect.super.functionSql(type, args);
    };
}

@Override
public String translateDateFormatPattern(String javaPattern) {
    // 将 Java 日期模式 yyyy-MM-dd 转换为 MySQL %Y-%m-%d 模式
    return javaPattern
        .replace("yyyy", "%Y").replace("yy", "%y")
        .replace("MM", "%m").replace("dd", "%d")
        .replace("HH", "%H").replace("mm", "%i")
        .replace("ss", "%s");
}
```

### 6.4 实现示例：PostgresDialect

```java
@Override
public String functionSql(FunctionType type, List<String> args) {
    return switch (type) {
        case DATE_FORMAT -> "TO_CHAR(" + args.get(0) + ", ?)";     // pattern 作为 binding
        case YEAR -> "EXTRACT(YEAR FROM " + args.get(0) + ")";
        case MONTH -> "EXTRACT(MONTH FROM " + args.get(0) + ")";
        case DAY -> "EXTRACT(DAY FROM " + args.get(0) + ")";
        case NOW -> "CURRENT_TIMESTAMP";
        case CONCAT -> {
            // PostgreSQL 使用 || 拼接，非 CONCAT() 函数
            yield String.join(" || ", args);
        }
        // 其他函数走默认 ANSI 实现
        default -> Dialect.super.functionSql(type, args);
    };
}

@Override
public String translateDateFormatPattern(String javaPattern) {
    // 将 Java 日期模式转换为 PostgreSQL TO_CHAR 模式
    return javaPattern
        .replace("yyyy", "YYYY").replace("yy", "YY")
        .replace("MM", "MM").replace("dd", "DD")
        .replace("HH", "HH24").replace("mm", "MI")
        .replace("ss", "SS");
}
```

### 6.5 实现示例：H2Dialect

```java
@Override
public String functionSql(FunctionType type, List<String> args) {
    if (isPostgresqlMode()) {
        // PostgreSQL 兼容模式委托给 PG 语法
        return switch (type) {
            case DATE_FORMAT -> "TO_CHAR(" + args.get(0) + ", ?)";
            case YEAR -> "EXTRACT(YEAR FROM " + args.get(0) + ")";
            // ... 其余同 PostgresDialect
            default -> Dialect.super.functionSql(type, args);
        };
    }
    // MySQL 兼容模式
    return switch (type) {
        case DATE_FORMAT -> "FORMATDATETIME(" + args.get(0) + ", ?)";
        case YEAR -> "EXTRACT(YEAR FROM " + args.get(0) + ")";
        case MONTH -> "EXTRACT(MONTH FROM " + args.get(0) + ")";
        case DAY -> "EXTRACT(DAY FROM " + args.get(0) + ")";
        case NOW -> "CURRENT_TIMESTAMP";
        // 其他函数走默认 ANSI 实现
        default -> Dialect.super.functionSql(type, args);
    };
}

@Override
public String translateDateFormatPattern(String javaPattern) {
    if (isPostgresqlMode()) {
        // 同 PostgresDialect
        return toPostgresPattern(javaPattern);
    }
    // H2 使用 Java 原生日期模式，无需翻译
    return javaPattern;
}
```

---

## 7. APT 代码生成方案

### 7.1 新增生成类：`XxxAggMeta`

为每个 `@Entity` 实体生成伴随聚合常量类，位置在 `<实体包>.generated`，与现有 `XxxMeta` / `XxxMapper` / `XxxQueryMeta` 同级。

#### 生成示例（User 实体）

```java
// 由 APT 生成，请勿手动修改
package com.holo.example.generated;

import com.holo.framework.horm.meta.query.expr.Aggregates;
import com.holo.framework.horm.meta.query.expr.AggExpr;
import com.holo.example.generated.UserQueryMeta;
import java.math.BigDecimal;

/** User 实体的聚合表达式常量。 */
public final class UserAggMeta {
    private UserAggMeta() {}

    public static final AggExpr<Long> COUNT = Aggregates.count();
    public static final AggExpr<Long> COUNT_ID = Aggregates.count(UserQueryMeta.ID);

    // 对所有数值字段生成 sum/avg/max/min
    public static final AggExpr<BigDecimal> SUM_AGE = Aggregates.sum(UserQueryMeta.AGE);
    public static final AggExpr<BigDecimal> AVG_AGE = Aggregates.avg(UserQueryMeta.AGE);
    public static final AggExpr<Integer> MAX_AGE = Aggregates.max(UserQueryMeta.AGE);
    public static final AggExpr<Integer> MIN_AGE = Aggregates.min(UserQueryMeta.AGE);

    public static final AggExpr<BigDecimal> SUM_SALARY = Aggregates.sum(UserQueryMeta.SALARY);
    public static final AggExpr<BigDecimal> AVG_SALARY = Aggregates.avg(UserQueryMeta.SALARY);
    // ...
}
```

#### 生成规则

| 字段类型 | 生成的聚合常量 |
|---------|---------------|
| 任意类型 | `COUNT`（COUNT(*)）、`COUNT_ID`（COUNT(主键)） |
| Number 子类 | `SUM_<FIELD>`、`AVG_<FIELD>`、`MAX_<FIELD>`、`MIN_<FIELD>` |
| Comparable | `MAX_<FIELD>`、`MIN_<FIELD>` |
| 其他 | 仅 `COUNT_<FIELD>` |

### 7.2 AggMetaBuilder 扩展

在 [QueryMetaBuilder.java](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/processor/QueryMetaBuilder.java) 同级新增 `AggMetaBuilder`，由 `HormEntityProcessor` 在生成 `XxxQueryMeta` 后调用。命名对齐现有 `MetaClassBuilder` / `MapperBuilder` / `QueryMetaBuilder`。

#### AggMetaBuilder 伪代码

```java
public final class AggMetaBuilder {
    public TypeSpec build(EntityDescriptor desc) {
        String queryMetaRef = desc.simpleName() + "QueryMeta";
        TypeSpec.Builder builder = TypeSpec.classBuilder(desc.simpleName() + "AggMeta")
            .addModifiers(Modifier.FINAL)
            .addJavadoc("Generated aggregate constants for {@link $L}\n", desc.qualifiedName());

        // COUNT(*)
        builder.addField(aggField("COUNT", "Aggregates.count()"));
        // COUNT(id) — 使用 QueryMeta 的主键常量
        String idConstName = EntityDescriptorParser.snake(desc.idField().name()).toUpperCase();
        builder.addField(aggField("COUNT_ID", "Aggregates.count(" + queryMetaRef + "." + idConstName + ")"));

        for (FieldDescriptor f : desc.fields()) {
            // 常量名使用 snake(field.name).toUpperCase()，与 QueryMetaBuilder.constName() 一致
            String constName = EntityDescriptorParser.snake(f.name()).toUpperCase();
            if (f.isNumeric()) {
                builder.addField(aggField("SUM_" + constName,
                    "Aggregates.sum(" + queryMetaRef + "." + constName + ")"));
                builder.addField(aggField("AVG_" + constName,
                    "Aggregates.avg(" + queryMetaRef + "." + constName + ")"));
            }
            if (f.isComparable()) {
                builder.addField(aggField("MAX_" + constName,
                    "Aggregates.max(" + queryMetaRef + "." + constName + ")"));
                builder.addField(aggField("MIN_" + constName,
                    "Aggregates.min(" + queryMetaRef + "." + constName + ")"));
            }
        }
        return builder.build();
    }
}
```

### 7.3 不生成函数常量

`Functions.upper(field)` 等标量函数**不通过 APT 生成常量**，因为：
1. 函数太多（200+），全量生成会污染命名空间
2. 函数参数动态，静态常量无法覆盖（如 `Functions.substring(field, 1, 10)`）
3. 用户直接调用 `Functions.upper(UserQueryMeta.NAME)` 已足够类型安全

---

## 8. 完整使用示例

### 8.1 聚合 + GROUP BY + HAVING

```java
// 按部门统计平均工资大于 1 万的部门
List<Row> result = Model.query(User.class)
    .groupBy(UserQueryMeta.DEPT_ID)              // 先 GROUP BY
    .having(UserAggMeta.AVG_SALARY.gt(new BigDecimal("10000")))  // 再 HAVING
    .selectExpr(                                  // 最后投影
        UserQueryMeta.DEPT_ID,
        UserAggMeta.AVG_SALARY,
        Aggregates.alias(Aggregates.count(), "headcount")
    )
    .listRows();

for (Row row : result) {
    Long deptId = row.get(UserQueryMeta.DEPT_ID);
    BigDecimal avgSalary = row.get(UserAggMeta.AVG_SALARY);
    Long headcount = row.getLong("headcount");
}
```

> 调用顺序约定：`groupBy` / `having` 可在 `selectExpr` 之前或之后调用（`ProjectionQuery` 也提供 `groupBy` / `having` 方法）。推荐先 `groupBy`/`having` 再 `selectExpr`，但不是强制。

### 8.2 日期函数 + WHERE（链式比较）

```java
// 查询 2026 年创建的用户 —— 类型安全写法，ComparableExpr.eq 链式调用
List<User> users = Model.query(User.class)
    .where(Functions.year(UserQueryMeta.CREATED_AT).eq(2026))
    .list();

// 等价的 raw 逃生舱写法（不推荐，丢失类型安全）
List<User> users2 = Model.query(User.class)
    .where(Conditions.raw("YEAR(created_at) = ?", 2026))
    .list();
```

### 8.3 字符串函数投影

```java
// 查询所有用户邮箱的大写形式
List<String> upperEmails = Model.query(User.class)
    .selectExpr(Functions.upper(UserQueryMeta.EMAIL))
    .listScalar(String.class);
```

### 8.4 单列标量聚合

```java
// 统计总用户数
Long total = Model.query(User.class)
    .selectExpr(Aggregates.count())
    .firstScalar(Long.class)
    .orElse(0L);
```

### 8.5 HAVING 中的链式函数比较

```java
// 查询平均年龄大于 30 的部门
List<Row> result = Model.query(User.class)
    .groupBy(UserQueryMeta.DEPT_ID)
    .having(UserAggMeta.AVG_AGE.gt(new BigDecimal("30")))
    .selectExpr(UserQueryMeta.DEPT_ID, UserAggMeta.AVG_AGE)
    .listRows();
```

### 8.6 函数表达式 GROUP BY

```java
// 按创建年份分组统计
List<Row> result = Model.query(User.class)
    .groupBy(Functions.year(UserQueryMeta.CREATED_AT))
    .selectExpr(Functions.year(UserQueryMeta.CREATED_AT), Aggregates.count())
    .listRows();
```

### 8.7 concat 函数（TypedField 可直接传入 Expr 参数位）

```java
// 拼接姓和名 —— TypedField 实现 Expr，可直接传入 concat
List<String> fullNames = Model.query(User.class)
    .selectExpr(Functions.concat(UserQueryMeta.LAST_NAME, UserQueryMeta.FIRST_NAME))
    .listScalar(String.class);
```

### 8.8 原生 SQL 逃生舱

```java
// 跨表 join + 自定义 RowMapper（使用默认数据源）
String sql = """
    SELECT u.id, u.name, COUNT(o.id) AS order_count
    FROM users u LEFT JOIN orders o ON o.user_id = u.id
    WHERE u.status = ?
    GROUP BY u.id, u.name
    HAVING COUNT(o.id) > ?
    """;

List<UserOrderCount> results = Horm.rawSql().query(sql,
    rs -> new UserOrderCount(rs.getLong("id"), rs.getString("name"), rs.getLong("order_count")),
    "active", 5);

// 指定数据源的 raw SQL（对齐 Horm.tx(String, Runnable) 风格）
List<UserOrderCount> results2 = Horm.rawSql("secondary").query(sql,
    rs -> new UserOrderCount(rs.getLong("id"), rs.getString("name"), rs.getLong("order_count")),
    "active", 5);
```

---

## 9. 向后兼容与迁移路径

### 9.1 兼容性矩阵

| 现有 API | M11 后行为 | 破坏性 |
|---------|-----------|--------|
| `Query.count()` | 行为不变（仍走 `SELECT COUNT(*)`） | 无 |
| `Query.list()` | 行为不变 | 无 |
| `Query.select(TypedField...)` | 行为不变（仍返回实体，含 id） | 无 |
| `Dialect` 接口 | 新增 `functionSql` / `supportsFunction` / `translateDateFormatPattern` 默认方法 | 无（默认实现可工作） |
| `Condition` 接口 | 不变，新增 `Conditions.raw` 公共方法 + `rawWithLeading` 包级私有方法 | 无 |
| `Row` 接口 | 新增 `get(Expr)` / `contains(String)` 默认方法 | 无（`MapRow` 自动继承） |
| `Repository` 接口 | 新增 `rawQuery` 默认方法 | 无（默认抛 `UnsupportedOperationException`） |
| `Horm` 类 | 新增 `rawSql()` / `rawSql(String)` 静态方法 | 无 |
| `TypedField` / `ComparableField` | 新增 `sqlFragment/bindings/javaType` 默认方法（实现 `Expr<T>`） | 无（全部 default，现有实现类零改动） |
| APT 生成 `XxxMeta` / `XxxMapper` / `XxxQueryMeta` | 不变，新增 `XxxAggMeta` 同级类 | 无（旧代码不受影响） |

### 9.2 用户迁移步骤

1. 升级到 M11 版本，现有代码无需任何改动
2. 需要聚合查询时，引入 `Aggregates` / `Functions` / `UserAggMeta`（自动生成）
3. 需要原生 SQL 时，调用 `Horm.rawSql()` / `Horm.rawSql(dataSourceName)` 或 `Repository.rawQuery`
4. 跨方言部署时，将 `Conditions.raw("YEAR(created_at) = ?", 2026)` 替换为 `Functions.year(field).eq(2026)` 获得类型安全

### 9.3 已知限制

- `selectExpr(...)` 一旦调用，无法再切回实体查询；需要重新构造 `Model.query(...)`
- `Query<T>.groupBy(TypedField...)` 校验字段实体类型匹配（与 `orderBy` 一致），但 `groupBy(Expr<?>...)` 重载不校验（函数表达式的实体归属不可静态推导）
- `Functions.raw` / `Conditions.raw` 不参与缓存键计算（避免泄露绑定值到日志），与 `QueryHash` 配合时需手动剔除
- `Row.get(Expr)` 依赖 `Expr.alias()` 或 APT 生成的列名；无 alias 的 raw 表达式需手动指定别名
- `Row` 不支持按列索引取值（`get(int, Class)` 未纳入），因为 `MapRow` 基于无索引的 `LinkedHashMap`

---

## 10. 里程碑拆分（M11）

| 子任务 | 模块 | 内容 | 测试覆盖目标 | 预计工时 |
|--------|------|------|-------------|---------|
| **M11.1** | meta | `Expr` / `ComparableExpr` / `AggExpr` / `FuncExpr` / `FunctionType` 抽象 + `TypedField` 扩展实现 `Expr<T>` + 单元测试 | >85% | 1.5 天 |
| **M11.2** | meta | `Aggregates` / `Functions` / `Conditions.raw` / `Conditions.rawWithLeading` 工厂类 + 单元测试 | >85% | 1 天 |
| **M11.3** | meta | `Row.get(Expr)` / `Row.contains(String)` 默认方法 + `ExprColumnResolver` / `ExprAccessor` public 工具类 | >85% | 0.5 天 |
| **M11.4** | core | `Dialect.functionSql()` / `translateDateFormatPattern()` + H2/MySQL/PostgreSQL 三方言实现 | >80% | 1.5 天 |
| **M11.5** | core | `Query.groupBy(TypedField)` / `groupBy(Expr)` / `having` / `selectExpr` 接口 + QueryImpl 实现（含跨实体校验） | >84% | 2 天 |
| **M11.6** | core | `ProjectionQuery`（含 groupBy/having） / `RawSql` + 多数据源 + `RowMapper` 实现 | >84% | 1.5 天 |
| **M11.7** | core | `Repository.rawQuery` + `Horm.rawSql()` / `Horm.rawSql(String)` 原生 SQL 入口 | >80% | 0.5 天 |
| **M11.8** | meta | `AggMetaBuilder` APT 代码生成（常量名对齐 `QueryMetaBuilder.constName()`） + compile-testing 测试 | >80% | 1.5 天 |
| **M11.9** | all | 集成测试（H2 全场景） + JaCoCo 覆盖率验证 + 文档更新 | — | 1 天 |

**总工时估算**：11 人天（含测试与文档）

### 10.1 验证命令

```bash
# M11 各阶段验证
mvn -pl holo-horm-meta,holo-horm-core -am verify -Pskip-enforcer

# 全量验证
mvn verify -Pskip-enforcer
```

### 10.2 关键测试用例

| 测试类 | 覆盖场景 |
|--------|---------|
| `ExprTest` | `Expr` 接口契约 + 默认方法 |
| `TypedFieldExprTest` | `TypedField` 实现 `Expr` 的 `sqlFragment/bindings/javaType` |
| `ComparableExprTest` | 链式比较 `gt/lt/ge/le/between/eq/ne/isNull` |
| `AggExprTest` | 聚合表达式渲染、别名、类型推断、`COUNT(*)` 空参数渲染 |
| `FuncExprTest` | 字符串 / 日期 / 数学 / 控制流函数渲染 |
| `DialectFunctionTest` | 三方言函数映射（含 DATE_FORMAT pattern 作为 binding、CONCAT 方言差异、RAW 防御） |
| `GroupByHavingTest` | GROUP BY + HAVING 端到端 |
| `ProjectionQueryTest` | listRows / listScalar / firstRow + groupBy/having on ProjectionQuery |
| `RowExprAccessorTest` | `Row.get(Expr)` 类型路由 + 缺列 HormException + `contains` vs `has` 语义 |
| `ConditionsRawTest` | `Conditions.raw` placeholder/binding 匹配、空 bindings、null binding |
| `RawSqlTest` | 原生 SQL 逃生舱 + RowMapper + 多数据源 |
| `AggMetaBuilderTest` | APT 生成 `XxxAggMeta` 的字段完整性 + 常量名对齐 `QueryMetaBuilder.constName()` |

---

## 11. 风险与开放问题

### 11.1 风险清单

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| `selectExpr` 切换后状态机变复杂 | QueryImpl 可读性下降 | `ProjectionQueryImpl` 独立类，快照传递 QueryImpl 的 where/orderBy/limit/offset/groupBy/having 状态 |
| Dialect 函数清单爆炸 | 维护成本 | 分两批：M11 只覆盖 20+ 高频函数，其余走 `Functions.raw` |
| 日期 pattern 跨方言翻译出错 | 运行时 SQL 错误 | 单元测试覆盖三方言 × 8 种 pattern；`translateDateFormatPattern` 为 Dialect 公开方法便于调试 |
| APT 生成 `XxxAggMeta` 与手写冲突 | 编译失败 | 强制生成在 `generated` 子包；用户禁止在该包写代码 |
| 缓存键计算含 raw SQL 片段 | 缓存击穿 | `Conditions.raw` / `Functions.raw` 不参与缓存（默认禁用 cache） |
| `Row.get(Expr)` 列名解析失败 | 运行时异常 | `ExprColumnResolver` 必须有 fallback 到 `sqlFragment()` 的 hash；缺列时抛 `HormException`（区分"列不存在"与"列值为 NULL"：`contains` 列存在 + `get` 返回 null） |
| `TypedField` 继承 `Expr` 引入隐式依赖 | meta 模块 `query.expr` 包被 `TypedField` 引用 | `Expr` 在 `query.expr` 包，`TypedField` 在 `query` 包，跨包引用可接受；`Expr` 不依赖 core/cache |

### 11.2 待决策的开放问题

| 问题 | 选项 | 倾向 |
|------|------|------|
| Q1：`selectExpr` 后是否允许 `fetch` 关联？ | A. 抛 HormException / B. 静默忽略 / C. 支持 | A（语义冲突，抛 `HormException`，对齐 `Query.select` 缺 id 时抛 `HormException`） |
| Q2：`Repository.rawQuery` 是否参与缓存？ | A. 不参与 / B. 可选 cache key | A（缓存键需 APT 元数据，raw 无；Javadoc 已标注 `@implNote`） |
| Q3：是否生成 `XxxFuncMeta`（字段级函数常量）？ | A. 生成 / B. 不生成，靠 `Functions.upper(field)` | B（函数太多，常量化反而冗余） |
| Q4：是否支持 `DISTINCT` 修饰符（`SUM(DISTINCT x)`）？ | A. M11 支持 / B. 后续版本 | B（M11 仅 `COUNT(DISTINCT)`） |

---

## 12. 与现有文档的关联

| 现有文档 | 关联点 |
|---------|--------|
| [08-dialect-adaptation.md](./08-dialect-adaptation.md) | Dialect 接口扩展遵循既有方言适配模式 |
| [02-zero-reflection.md](./02-zero-reflection.md) | APT 生成 `XxxAggMeta` 延续零反射原则 |
| [05-active-record.md](./05-active-record.md) | `Model.query()` 入口不变，仅扩展方法 |
| [11-user-guide.md](./11-user-guide.md) | M11 完成后需补充"聚合查询"章节 |
| [PROGRESS.md](./PROGRESS.md) | M11 状态需在交付后更新 |

---

## 13. 风格统一性核对记录

> 本节记录 v2→v3 修订中对齐现有 HORM 命名风格与架构约束的具体改动，便于审阅。

| 维度 | 现有 HORM 风格来源 | v2 设计 | v3 修订 |
|------|-------------------|---------|---------|
| 工厂类命名 | [Conditions.java](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/query/Conditions.java) `final class Conditions` | `Agg` / `Func` | **`Aggregates` / `Functions`**（复数名词对齐） |
| 行抽象 | [Row.java](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/Row.java) `interface Row` + `MapRow` 内部类 | 新增 `Tuple` 接口 | **取消 Tuple，扩展 `Row`** 加 `get(Expr)` 默认方法 |
| Horm 入口命名 | [Horm.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/Horm.java) `install/repository/tx/migrate/configureMigration` | `Horm.sql()` | **`Horm.rawSql()` + `rawSql(String)`**（完整动词 + 多数据源重载对齐 `tx(String, Runnable)`） |
| Expr 比较方法 | [TypedField.java](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/query/TypedField.java) 自带 `eq/ne/gt/lt/...` 默认方法 | `Expr<T>` 无比较方法 | **新增 `ComparableExpr<T extends Comparable<T>>`** 接口，对齐 `ComparableField` 模式 |
| Query 方法命名 | [Query.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/query/Query.java) `select/fetch/leftJoin` | `selectAgg` | **`selectExpr`**（接受任意 `Expr`，不只是聚合） |
| TypedField 与 Expr 统一 | `TypedField` 无 Expr 关系 | `TypedField` 和 `Expr` 类型割裂 | **`TypedField<E,T> extends Expr<T>`**，函数 API 可直接接受字段常量 |
| Conditions.raw 重载 | `Conditions.eq(field, value)` 单一签名 | `raw(String, Object...)` + `raw(String, List, Object...)` 公共重载 | **`raw(String, Object...)` 公共 + `rawWithLeading(String, List, Object...)` 包级私有**（消除重载歧义） |
| 日期 pattern 安全 | `Query` 所有用户值通过 `?` 占位符 | pattern 嵌入 SQL 字面量 | **pattern 通过 `translateDateFormatPattern` 翻译后作为 binding**（消除 SQL 注入） |
| Row 缺列检测 | `Row.has()` 检查"列存在且非 null" | `get(Expr)` 用 `has()` 判断缺列 | **新增 `contains(String)` 仅判断列存在** + **缺列抛 `HormException`**（区分缺列与 NULL） |
| 索引取值 | `Row` 基于 `LinkedHashMap` 无索引能力 | `Row.get(int, Class)` | **删除**（`MapRow` 不支持可靠索引访问） |
| APT 生成器命名 | `MetaClassBuilder/MapperBuilder/QueryMetaBuilder` | `AggMetaBuilder` | 保持（已对齐） |
| APT 生成类命名 | `XxxMeta/XxxMapper/XxxQueryMeta` | `XxxAggMeta` | 保持（已对齐） |
| APT 常量名对齐 | `QueryMetaBuilder.constName()` 使用 `snake(name).toUpperCase()` | `f.name().toUpperCase()` | **改用 `EntityDescriptorParser.snake(f.name()).toUpperCase()`** 对齐 |
| Dialect 默认方法 | `batchInsertSyntax()` / `supportsBatchInsertGeneratedKeysInOrder()` | `functionSql()` / `supportsFunction()` | 保持（已对齐）+ 新增 `translateDateFormatPattern()` |
| 异常分层 | `IllegalArgumentException`（参数校验）vs `HormException`（运行时数据错误） | `Row.get(Expr)` 缺列抛 `IllegalArgumentException` | **改抛 `HormException`**（运行时数据访问错误，对齐 SQLException） |
| ExprColumnResolver 可见性 | meta 模块工具类通常 `public` | package-private | **改为 `public`**（跨包访问需要） |
| RowMapper 位置 | `Mapper<T>` 在 `com.holo.framework.horm.meta` 包 | 未指定 | **`com.holo.framework.horm.meta`**（与 `Mapper<T>` 同包） |
| COUNT(*) 渲染 | ANSI SQL `COUNT(*)` | 默认实现 `COUNT()`（空参数无效） | **默认实现增加 `COUNT` 空参数分支** → `"COUNT(*)"` |
| Dialect RAW 防御 | 无 | 无防御 | **默认实现增加 `RAW` 类型 `IllegalArgumentException`** |
| PostgreSQL CONCAT | `a \|\| b` | 默认渲染 `CONCAT(a, b)` | **`PostgresDialect` override 渲染 `a \|\| b \|\| c`** |
| FunctionType 枚举 | 与 `Functions` 工厂一一对应 | 含 `TRIM/FLOOR/CEIL/COALESCE/NULLIF/EXTRACT` 无对应工厂 | **补充 `trim/floor/ceil/coalesce/nullif` 工厂方法，删除 `EXTRACT`** |
| Javadoc 语言 | 英文 | 英文 | 保持 |
| 设计文档语言 | 中文（docs/xx.md） | 中文 | 保持 |

---

## 14. 变更记录

| 日期 | 变更 | 作者 |
|------|------|------|
| 2026-07-14 | 初稿起草，覆盖 API 设计、Dialect 映射、APT 生成、里程碑拆分 | HORM Team |
| 2026-07-14 | v2 风格统一性修订：工厂类改 `Aggregates`/`Functions`；取消 `Tuple`，扩展 `Row`；`Horm.sql()` → `Horm.rawSql()`；`selectAgg` → `selectExpr`；新增 `ComparableExpr`；新增 §13 风格核对记录 | HORM Team |
| 2026-07-14 | v3 兼容性审查全量修复（28 项）：<br/>**🔴 严重修复**：(S1) 里程碑 M10→M11 避免与已落地的"性能优化"M10 冲突；(S2) `ProjectionQuery` 增加 `groupBy/having` 方法 + 调用顺序说明 + 快照传递机制；(S3) 日期 pattern 改为 binding 传入 + 新增 `Dialect.translateDateFormatPattern()`；(S4) 新增 `Row.contains(String)` 区分"列不存在"与"列值为 NULL"；(S5) `Dialect.functionSql` 默认实现增加 `COUNT` 空参数分支；(S6) `TypedField extends Expr<T>` 统一类型系统；(S7) `ExprColumnResolver/ExprAccessor` 改为 public；(S8) `Row.get(Expr)` 缺列改抛 `HormException`；(S9) `Horm.rawSql(String)` 多数据源重载；(S10) 删除 `Row.get(int, Class)` <br/>**🟡 中等修复**：(M1) `PostgresDialect` override `CONCAT` 渲染 `a || b || c`；(M2) `AggMetaBuilder` 常量名对齐 `QueryMetaBuilder.constName()`；(M3) 删除冗余 `Conditions.expr(Expr<Boolean>)`；(M4) `Aggregates.alias` 明确不可变语义；(M5) `FunctionType` 枚举与 `Functions` 工厂对齐（补充 `trim/floor/ceil/coalesce/nullif`，删除 `EXTRACT`）；(M6) `groupBy(TypedField)` 校验实体类型；(M7) 新增 `groupBy(Expr<?>...)` 重载；(M8) `Dialect.functionSql` 增加 `RAW` 防御；(M9) `RowMapper` 放 `com.holo.framework.horm.meta` 包；(M10) §1.1 补全 `Query.join`；(M11) `Conditions.rawWithLeading` 改名降级包级私有；(M12) `selectExpr` 快照传递机制明确 <br/>**🟢 轻微修复**：(L1) P3 表述精确化为"聚合字段级常量"；(L2) `Aggregates.sum` Javadoc 说明 BigDecimal 统一原因；(L3) `Functions.now` Javadoc 说明 `Row.getInstant` 类型转换；(L4) 测试用例补充 `ConditionsRawTest`/`TypedFieldExprTest`；(L5) `ExprAccessor` AOT 兼容性说明（if-else 链路由）；(L6) `Repository.rawQuery` Javadoc 标注 `@implNote` 不参与缓存 | HORM Team |
