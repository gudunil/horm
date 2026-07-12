# HORM 性能基准报告

> 本文记录 HORM 1.0.0-SNAPSHOT 的 JMH 性能基准测试设计、真实测试结果与分析。
>
> **测试日期**：2026-07-13
> **原始数据**：[benchmark-results-2026-07-13.txt](./benchmark-results-2026-07-13.txt) + [benchmark-cache-results-2026-07-13.txt](./benchmark-cache-results-2026-07-13.txt)

---

## 一、测试环境

| 项目 | 配置 |
|------|------|
| OS | Windows 10 Pro |
| JDK | 17.0.18 (OpenJDK 64-Bit Server VM, 17.0.18+8-LTS) |
| JVM 参数 | `-Xms256m -Xmx256m` |
| 数据库 | H2 内存库（`jdbc:h2:mem:bench;MODE=MySQL;DB_CLOSE_DELAY=-1`） |
| JMH 版本 | 1.37 |
| Blackhole | compiler (auto-detected) |

### 对比框架

| 框架 | 版本 | 用途 |
|------|------|------|
| HORM | 1.0.0-SNAPSHOT | 待测对象 |
| 手写 JDBC | JDK 17 `java.sql` | 性能上限基线 |
| MyBatis | 3.5.16 | 流行半自动 ORM |
| Hibernate ORM | 6.4.4.Final | 流行全自动 ORM（L2 Cache 已关闭） |

### 数据集

- 表：`bench_users(id BIGINT PRIMARY KEY AUTO_INCREMENT, email VARCHAR(255), name VARCHAR(255), created_at TIMESTAMP)`
- 预填充：1000 条记录（id 从 1 到 1000）

---

## 二、基准测试设计

### 测试矩阵

| 基准类 | 场景 | 方法数 | 时间单位 | JMH 配置 |
|--------|------|--------|---------|----------|
| `FindByIdBenchmark` | 按主键查 1 条 | 4 | μs | Warmup(3,1) / Measure(5,1) / Fork(1) |
| `CrudBenchmark` | INSERT / UPDATE / DELETE | 12 | μs | Warmup(3,1) / Measure(5,1) / Fork(1) |
| `QueryBenchmark` | 条件查询 + 排序 + 分页（10/100 条） | 8 | μs | Warmup(3,1) / Measure(5,1) / Fork(1) |
| `CacheBenchmark` | 缓存命中 / 未命中 / 无缓存 | 3 | ns | Warmup(5,1) / Measure(10,1) / Fork(1) |
| `BatchInsertBenchmark` | 批量插入（100/1000 条） | 8 | ms | Warmup(2,1) / Measure(3,1) / Fork(1) |
| `FindManyBenchmark` | 批量查询 100 ID | 4 | μs | Warmup(3,1) / Measure(5,1) / Fork(1) |
| `TransactionProxyBenchmark` | APT 代理 vs JDK Proxy vs 反射（M8.7） | 5 | ns | Warmup(3,1) / Measure(5,1) / Fork(1) |

### 公平性保证

- 所有框架共享同一 H2 schema 与 1000 条预填充数据
- Hibernate 关闭二级缓存（`hibernate.cache.use_second_level_cache=false`）
- MyBatis 不启用自定义拦截器
- HORM 默认不启用缓存链（`CacheBenchmark` 除外）
- 所有基准单线程（`Threads: 1`），同步迭代

### 运行基准

```bash
# 打包
mvn -pl holo-horm-benchmark -am package -DskipTests -Pskip-enforcer

# 运行全部基准（约 5-6 分钟）
java -jar holo-horm/holo-horm-benchmark/target/holo-horm-benchmark-1.0.0-SNAPSHOT.jar

# 运行指定基准
java -jar holo-horm/holo-horm-benchmark/target/holo-horm-benchmark-1.0.0-SNAPSHOT.jar CacheBenchmark

# 输出 CSV 结果
java -jar holo-horm/holo-horm-benchmark/target/holo-horm-benchmark-1.0.0-SNAPSHOT.jar -rf csv -rff results.csv
```

---

## 三、真实测试结果

> 以下数据为 2026-07-13 在上述测试环境下运行的真实结果。`Score` 为平均执行时间，`Error` 为 99.9% 置信区间。**越低越好**。

### 3.1 FindById（按主键查 1 条）

| 框架 | 平均时间（μs/op） | 误差 ± | 相对 JDBC |
|------|-------------------|--------|----------|
| 手写 JDBC | 0.934 | 0.140 | 1.0x |
| Hibernate | 2.669 | 0.511 | 2.9x |
| MyBatis | 3.553 | 2.230 | 3.8x |
| **HORM** | **7.555** | **2.638** | **8.1x** |

**分析**：HORM 在 FindById 场景下表现不如预期，比 Hibernate 慢 2.8 倍，比 MyBatis 慢 2.1 倍。可能原因：
1. `HormContext.current()` 每次查询都从 ThreadLocal 获取上下文
2. `EntityMetaRegistry.lookup(Class)` 通过 ServiceLoader 发现的 provider 查找元数据
3. `JdbcRepository` 的 `dbFindManyByKey` 路径包含 CacheKey 构建与缓存查询（即使无 CacheChain 也有空检查开销）
4. APT 生成的 `Mapper.map(Row)` 虽然零反射，但有 Row 包装层开销

**改进方向**（M10）：缓存 `EntityMeta` 引用、短路空 CacheChain 检查、减少 ThreadLocal 查找。

### 3.2 CRUD（INSERT / UPDATE / DELETE）

| 操作 | JDBC | HORM | MyBatis | Hibernate |
|------|------|------|---------|-----------|
| INSERT | 20.896 ± 113.598 | **12.706 ± 0.551** | 7.540 ± 4.581 | 7.987 ± 3.104 |
| UPDATE | 2.185 ± 0.135 | **19.077 ± 1.417** | 3.567 ± 0.117 | 8.573 ± 1.740 |
| DELETE | 7.839 ± 5.357 | **20.628 ± 2.341** | 7.186 ± 0.247 | 9.739 ± 1.315 |

> 单位：μs/op。JDBC INSERT 误差极大（113.598），疑似 GC 或 JIT 抖动异常值，仅供参考。

**分析**：
- **INSERT**：HORM 表现尚可（12.7 μs），比 Hibernate/MyBatis 慢约 60%，但比 JDBC 的异常值低。HORM 的 INSERT 路径包含主键回填、`@Version` 初始化、Cascade 预处理。
- **UPDATE / DELETE**：HORM 明显最慢（~20 μs），是 MyBatis 的 5.3-5.7 倍。根因是 HORM 的 `save()` / `delete()` 路径包含：
  1. 乐观锁版本检查（`@Version` 字段 WHERE 子句）
  2. 事务上下文注册（`TransactionManager` 注册 savepoint）
  3. Cascade 关系预处理（即使无关联也要扫描注解）
  4. CacheChain 失效通知（即使无 CacheChain 也有空检查）

**改进方向**（M10）：延迟 Cascade 扫描、短路空 CacheChain、可选关闭乐观锁检查。

### 3.3 Query（条件查询 + 排序 + 分页）

| 框架 | limit=10（μs/op） | limit=100（μs/op） |
|------|-------------------|---------------------|
| 手写 JDBC | 1.710 ± 0.006 | 9.249 ± 0.087 |
| Hibernate | 7.589 ± 0.540 | 53.530 ± 3.385 |
| MyBatis | 7.755 ± 0.207 | 57.862 ± 2.246 |
| **HORM** | **12.945 ± 2.572** | **33.229 ± 0.206** |

**分析**：
- **limit=10**：HORM 比 Hibernate/MyBatis 慢 70%。Query 构建器拼装 SQL + 参数绑定有固定开销，小结果集时占比较高。
- **limit=100**：**HORM 比 Hibernate 快 38%，比 MyBatis 快 43%**。大结果集时，HORM 的 APT 生成 `Mapper.map(Row)` 零反射优势显现，Hibernate 的 Session 脏检查与 MyBatis 的 ResultMap 解析开销放大。

**结论**：HORM 的查询 DSL 在大结果集场景有竞争优势，小结果集时需优化 SQL 拼装开销。

### 3.4 Cache（缓存对比）

| 场景 | 平均时间（ns/op） | 误差 ± | 说明 |
|------|-------------------|--------|------|
| cacheHit | **145.954** | 4.565 | L1 命中，直接从 Caffeine 取值 |
| noCache | 7164.899 | 135.660 | 无 CacheChain，直接查 DB |
| cacheMiss | 8779.601 | 33.824 | 缓存未命中，查 DB + 回填 |

**分析**：
- **缓存命中是 DB 查询的 49 倍快**（146 ns vs 7165 ns），加速显著
- `cacheMiss` 比 `noCache` 慢 22%（多一次 invalidate + 回填开销），符合预期
- L1 Caffeine 缓存对读多写少场景价值极高

### 3.5 BatchInsert（批量插入）

| 框架 | batchSize=100（ms/op） | batchSize=1000（ms/op） |
|------|------------------------|--------------------------|
| MyBatis | 0.522 ± 0.645 | 5.123 ± 5.608 |
| 手写 JDBC | 0.534 ± 2.082 | 5.032 ± 6.064 |
| Hibernate | 0.558 ± 0.323 | 6.149 ± 8.804 |
| **HORM** | **1.219 ± 0.416** | **11.685 ± 3.268** |

**分析**：HORM 批量插入最慢，是 JDBC/MyBatis 的 2.3 倍（100 条）和 2.3 倍（1000 条）。根因：**HORM 当前用循环 `repository.save(entity)`，未做 JDBC batch 优化**。每次 INSERT 独立提交，无 `addBatch/executeBatch`。

**改进方向**（M10）：实现 `Model.batchInsert(List<T>)` + JDBC `addBatch` + 单次 `executeBatch`，预期可达到 JDBC 基线水平。

### 3.6 FindMany（批量查询 100 ID）

| 框架 | 平均时间（μs/op） | 误差 ± |
|------|-------------------|--------|
| 手写 JDBC | 12.321 | 0.210 |
| **HORM** | **72.003** | **0.553** |
| Hibernate | 73.863 | 1.177 |
| MyBatis | 162.947 | 5.314 |

**分析**：
- HORM 与 Hibernate 接近（72.00 vs 73.86），差距 < 3%
- HORM 比 MyBatis 快 56%（72.00 vs 162.95）
- HORM 的 `findMany` 用 `SELECT ... WHERE id IN (?,?,...)` 一次性查询，APT 生成的 Mapper 零反射映射结果
- MyBatis 最慢，因 ResultMap 解析 + foreach 动态 SQL 开销

### 3.7 TransactionProxy（M8.7 代理对比）

| 方式 | 平均时间（ns/op） | 误差 ± | 说明 |
|------|-------------------|--------|------|
| jdkDynamicProxy | 4.990 | 0.259 | JDK 动态代理（基准） |
| directCall | 6.805 | 5.522 | 直接方法调用（理论应最快，误差大） |
| methodBridge | 11.507 | 0.316 | LambdaMetafactory 桥接 |
| methodInvoke | 11.861 | 0.559 | Method.invoke 反射 |
| aptProxyDirect | 3734.602 | 341.736 | APT 代理直接调用 |

**异常说明**：`aptProxyDirect` 结果 3734 ns 异常高，与 M8.7 设计预期（应接近 `directCall` 的 ns 级）严重不符。初步分析：
- 可能是 benchmark 方法内部每次迭代都重新查找代理实例（而非复用）
- 或 APT 代理子类的 `super` 调用路径有额外开销
- **需进一步调查**（M10 优先项）

`directCall` 误差大（5.522 ns，与 score 同量级），说明 JIT 优化波动大，结果不稳定。

---

## 四、综合分析

### 4.1 HORM 优势场景

| 场景 | HORM 表现 | 对比 |
|------|----------|------|
| 大结果集查询（limit=100） | 33.23 μs | 比 Hibernate 快 38%，比 MyBatis 快 43% |
| 批量查询 100 ID | 72.00 μs | 与 Hibernate 持平，比 MyBatis 快 56% |
| 缓存命中 | 146 ns | 比 DB 查询快 49 倍 |
| INSERT | 12.71 μs | 比 JDBC 异常值低（但比 Hibernate/MyBatis 慢） |

### 4.2 HORM 劣势场景

| 场景 | HORM 表现 | 根因 | 改进计划 |
|------|----------|------|---------|
| FindById | 7.56 μs（最慢） | Context 查找 + MetaRegistry + 空 CacheChain 检查 | M10：缓存 Meta 引用、短路空检查 |
| UPDATE / DELETE | ~20 μs（最慢） | 乐观锁 + 事务注册 + Cascade 扫描 | M10：延迟 Cascade、可选关闭锁检查 |
| BatchInsert | 11.69 ms（最慢） | 循环 save，未 batch | M10：实现 `batchInsert` + JDBC batch |
| 小结果集查询（limit=10） | 12.95 μs（最慢） | SQL 拼装固定开销 | M10：预编译 SQL 模板 |

### 4.3 与设计预期的差异

M9 Phase 2 文档中的"预期范围"基于零反射设计的理论分析，假设 HORM 应接近 JDBC、快于 Hibernate/MyBatis。**真实测试结果显示这一假设仅在部分场景成立**：

| 场景 | 预期 | 实际 | 差异 |
|------|------|------|------|
| FindById | HORM 9-18 μs，快于 Hibernate | HORM 7.56 μs，慢于 Hibernate | ❌ 反转 |
| Query(100) | HORM 70-120 μs | HORM 33.23 μs | ✅ 比预期快 |
| BatchInsert | HORM 45-110 ms | HORM 11.69 ms | ✅ 比预期快（但仍是对照组最慢） |
| FindMany | HORM 110-210 μs | HORM 72.00 μs | ✅ 比预期快 |

**教训**：零反射不等于零开销。HORM 的 Active Record 抽象层（Context 查找、Meta 注册、Cascade 扫描、CacheChain 空检查）在单条操作场景的固定开销，可能超过反射的开销。反射在现代 JVM 中已被高度优化（JIT 内联），不再是主要瓶颈。

---

## 五、性能优化建议

### 5.1 用户侧优化

#### 启用缓存链（读多写少场景）

```java
@Entity
@Cached(levels = {CacheLevel.L1},
        policy = @CachePolicy(ttl = "1h", writeStrategy = WriteStrategy.THROUGH))
public class DictItem extends Model<DictItem> { ... }
```

缓存命中（146 ns）比 DB 查询（7165 ns）快 49 倍。

#### 使用 `findMany` 批量查询

```java
// ❌ N+1
List<User> users = ids.stream().map(id -> Model.find(User.class, id)).toList();

// ✅ 批量（72 μs 查 100 条，比 N+1 快 10 倍）
Map<Object, User> users = Model.findMany(User.class, ids);
```

#### 使用 `fetch` 预加载关联

```java
List<User> users = Model.query(User.class).fetch(UserQueryMeta.ORDERS).list();
```

### 5.2 框架侧改进（M10 计划）

| 优化项 | 预期收益 | 优先级 |
|--------|---------|--------|
| `Model.batchInsert(List<T>)` + JDBC batch | BatchInsert 提速 2-3 倍 | P0 |
| 缓存 `EntityMeta` 引用到 `Repository` 字段 | FindById 减少一次 Map 查找 | P0 |
| 短路空 CacheChain（null 检查提前） | FindById/CRUD 减少 2-3 次方法调用 | P1 |
| 延迟 Cascade 扫描（仅有关联时扫描） | UPDATE/DELETE 减少注解扫描开销 | P1 |
| 预编译 SQL 模板（缓存 PreparedStatement） | 小结果集 Query 提速 30-50% | P2 |
| 调查 `aptProxyDirect` 异常 | 修复 TransactionProxy 性能 | P2 |

---

## 六、JMH 配置说明

### 6.1 当前配置权衡

- `@Fork(1)`：单次 fork 节省时间（生产基准建议 `@Fork(3)` 提高可信度）
- `@Warmup(3,1)` + `@Measurement(5,1)`：平衡精度与耗时
- `@State(Scope.Benchmark)`：共享 setup（数据源、Session、SqlSession）

### 6.2 结果稳定性说明

本次测试部分结果误差较大：
- `jdbcInsert` 误差 113.598 μs（score 20.896 μs）— GC/JIT 抖动异常值
- `directCall` 误差 5.522 ns（score 6.805 ns）— JIT 优化波动
- `mybatis` FindById 误差 2.230 μs（score 3.553 μs）— 62% 相对误差

生产环境基准建议：
- `@Fork(3)` — 3 次 fork 取平均
- `@Warmup(iterations=5, time=2)` — 充分预热
- `@Measurement(iterations=10, time=2)` — 提高统计置信度
- 多次运行取中位数

---

## 七、参考

- [基准测试源码](../holo-horm-benchmark/src/main/java/com/holo/framework/horm/benchmark/)
- [原始结果数据 2026-07-13](./benchmark-results-2026-07-13.txt)
- [缓存基准结果 2026-07-13](./benchmark-cache-results-2026-07-13.txt)
- [JMH 官方文档](https://openjdk.org/projects/code-tools/jmh/)
- [04-cache-chain.md](./04-cache-chain.md) — 缓存链设计
- [09-zero-reflection-optimization.md](./09-zero-reflection-optimization.md) — 零反射优化
- [07-performance-security.md](./07-performance-security.md) — 性能与安全设计
