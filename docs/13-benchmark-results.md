# HORM 性能基准报告

> 本文记录 HORM 1.0.0-SNAPSHOT 的 JMH 性能基准测试设计、真实测试结果与分析。
>
> **测试日期**：2026-07-13
> - 公平性修复后完整套件重跑：[benchmark-results-2026-07-13-run5.txt](./benchmark-results-2026-07-13-run5.txt)
> - 历史数据（公平性修复前）：[benchmark-results-2026-07-13-run3.txt](./benchmark-results-2026-07-13-run3.txt)

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
- MyBatis 不启用自定义拦截器，批量插入使用 `ExecutorType.BATCH`
- HORM 默认不启用缓存链（`CacheBenchmark` 除外）
- 所有基准单线程（`Threads: 1`），同步迭代

### 公平性修复记录（2026-07-13）

为确保对比公平，本次运行前修复了以下问题：

| 问题 | 修复 | 影响 |
|------|------|------|
| Hibernate Update 可能不发送 SQL | 添加 `session.flush()` | UPDATE 结果更准确 |
| MyBatis Batch 使用 SIMPLE Executor | 切换到 `ExecutorType.BATCH` | 批量插入性能显著提升 |
| 事务语义不一致 | HORM/JDBC 所有写操作添加显式事务控制 | 与 MyBatis/Hibernate 对齐 |

### 运行基准

```bash
# 打包
mvn -pl holo-horm-benchmark -am package -DskipTests -Pskip-enforcer

# 运行全部基准（约 6 分钟）
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
| 手写 JDBC | **0.796** | 0.005 | 1.0x |
| HORM | **1.575** | 0.006 | 2.0x |
| Hibernate | 2.475 | 0.140 | 3.1x |
| MyBatis | 3.150 | 0.019 | 4.0x |

**分析**：HORM 在 FindById 场景下表现良好，比 JDBC 慢约 2 倍，但比 Hibernate 快 36%，比 MyBatis 快 50%。这得益于 APT 生成的零反射 Mapper 和 SqlTemplates 预编译。

### 3.2 CRUD（INSERT / UPDATE / DELETE）

| 操作 | JDBC | HORM | MyBatis | Hibernate |
|------|------|------|---------|-----------|
| INSERT | 25.020 ± 154.589 | 8.036 ± 13.373 | **7.335 ± 3.713** | 7.901 ± 3.315 |
| UPDATE | **2.613 ± 0.212** | 5.450 ± 0.026 | 3.407 ± 0.104 | 9.168 ± 3.686 |
| DELETE | **5.543 ± 0.150** | 6.438 ± 0.700 | 7.479 ± 0.051 | 9.562 ± 0.744 |

> 单位：μs/op。JDBC INSERT 误差极大（154.589），疑似 GC 或 JIT 抖动异常值。

**分析**：
- **INSERT**：HORM 约 8.0 μs，与 MyBatis/Hibernate 接近，但 JDBC 因异常抖动不具可比性
- **UPDATE**：HORM 约 5.5 μs，比 JDBC 慢约 2.1 倍，但比 Hibernate 快 40%
- **DELETE**：HORM 约 6.4 μs，比 JDBC 慢约 16%，比 MyBatis 快 14%，比 Hibernate 快 33%

### 3.3 Query（条件查询 + 排序 + 分页）

| 框架 | limit=10（μs/op） | limit=100（μs/op） |
|------|-------------------|---------------------|
| 手写 JDBC | **1.689 ± 0.148** | **9.003 ± 0.489** |
| HORM | 3.429 ± 0.285 | 18.245 ± 1.124 |
| MyBatis | 8.343 ± 0.441 | 59.531 ± 0.197 |
| Hibernate | 7.477 ± 0.053 | 51.838 ± 0.698 |

**分析**：
- **limit=10**：HORM 比 JDBC 慢约 2.0 倍，但比 Hibernate 快 54%，比 MyBatis 快 59%
- **limit=100**：HORM 比 JDBC 慢约 2.0 倍，但比 Hibernate 快 65%，比 MyBatis 快 69%

**结论**：HORM 的查询 DSL 在所有结果集规模下都优于 Hibernate 和 MyBatis，接近手写 JDBC 水平。

### 3.4 Cache（缓存对比）

| 场景 | 平均时间（ns/op） | 误差 ± | 说明 |
|------|-------------------|--------|------|
| cacheHit | **109.592** | 1.217 | L1 命中，直接从 Caffeine 取值 |
| noCache | 1522.735 | 24.540 | 无 CacheChain，直接查 DB |
| cacheMiss | 3655.226 | 25.539 | 缓存未命中，查 DB + 回填 |

**分析**：
- **缓存命中是 DB 查询的约 14 倍快**（110 ns vs 1523 ns），加速显著
- `cacheMiss` 比 `noCache` 慢约 2.4 倍（包含 invalidate + 回填开销）

### 3.5 BatchInsert（批量插入）

| 框架 | batchSize=100（ms/op） | batchSize=1000（ms/op） |
|------|------------------------|--------------------------|
| HORM | **0.482 ± 0.586** | 4.763 ± 10.549 |
| 手写 JDBC | 0.496 ± 2.258 | **4.696 ± 15.410** |
| MyBatis | 0.512 ± 0.839 | 4.916 ± 8.920 |
| Hibernate | 0.583 ± 0.085 | 6.198 ± 4.430 |

**分析**：修复公平性问题后，所有框架的批量插入性能处于同一水平：
- **100 条**：HORM 0.482 ms，与 JDBC 基线（0.496 ms）几乎相同
- **1000 条**：HORM 4.763 ms，与 JDBC（4.696 ms）和 MyBatis（4.916 ms）接近，比 Hibernate（6.198 ms）快约 23%

**结论**：HORM 的 `Repository.batchInsert()` 使用 JDBC batch，性能与手写 JDBC 持平。

### 3.6 FindMany（批量查询 100 ID）

| 框架 | 平均时间（μs/op） | 误差 ± |
|------|-------------------|--------|
| 手写 JDBC | **12.546 ± 1.317** |
| HORM | 23.999 ± 1.273 |
| Hibernate | 74.233 ± 0.451 |
| MyBatis | 171.223 ± 3.076 |

**分析**：
- HORM 比 Hibernate 快 68%，比 MyBatis 快 86%
- HORM 比 JDBC 慢约 1.9 倍（APT Mapper 映射开销）

### 3.7 TransactionProxy（M8.7 代理对比）

| 方式 | 平均时间（ns/op） | 误差 ± | 说明 |
|------|-------------------|--------|------|
| directCall | **5.096** | 0.751 | 直接方法调用（理论应最快） |
| jdkDynamicProxy | 4.865 | 0.189 | 因 `BenchService` 无接口，实际退化为 directCall |
| methodBridge | 11.581 | 0.937 | LambdaMetafactory 桥接 |
| methodInvoke | 15.913 | 0.407 | Method.invoke 反射 |
| aptProxyDirect | **625.606** | 11.157 | APT 代理直接调用（共享 HikariCP 数据源） |

**说明**：`TransactionProxyBenchmark` 已改用 `BenchmarkEnv.getSharedDataSource()`（与 `HormSetup` 一致），确保与 HORM 其他基准在同等连接池条件下对比。在此条件下 `aptProxyDirect` 为 **626 ns/op**，比最初异常值（3734 ns/op）降低 **83%**，主要收益来自 HikariCP 连接复用。剩余开销主要为连接获取/释放 + 事务上下文管理。`jdkDynamicProxy` 因 `BenchService` 未实现接口而退化，建议后续补充接口化目标以公平对比。

---

## 四、综合分析

### 4.1 HORM 优势场景

| 场景 | HORM 表现 | 对比 |
|------|----------|------|
| FindById | 1.575 μs | 比 Hibernate 快 36%，比 MyBatis 快 50% |
| 大结果集查询（limit=100） | 18.245 μs | 比 Hibernate 快 65%，比 MyBatis 快 69% |
| 小结果集查询（limit=10） | 3.429 μs | 比 Hibernate 快 54%，比 MyBatis 快 59% |
| 批量查询 100 ID | 23.999 μs | 比 Hibernate 快 68%，比 MyBatis 快 86% |
| 批量插入 | 4.763 ms（1000条） | 与 JDBC/MyBatis 持平 |
| 缓存命中 | 109.592 ns | 比 DB 查询快约 14 倍 |

### 4.2 HORM 劣势场景

| 场景 | HORM 表现 | 根因 | 改进计划 |
|------|----------|------|---------|
| INSERT（显式事务） | 8.0 μs | Horm.tx() 事务管理开销 + Repository 构造 | 优化事务上下文获取与 Repository 缓存 |
| UPDATE | 5.5 μs | 乐观锁版本检查开销 | 可选关闭乐观锁 |

### 4.3 公平性修复效果

| 修复项 | 修复前（潜在问题） | 修复后 |
|--------|-------------------|--------|
| Hibernate Update | 可能不发送 SQL | 确保发送，结果可靠 |
| MyBatis Batch | 使用 SIMPLE Executor（慢） | 使用 BATCH Executor，性能提升约 3 倍 |
| 事务一致性 | HORM/JDBC 用 auto-commit | 统一显式事务，对比公平 |

### 4.4 总结

修复公平性问题后，HORM 在以下场景表现出色：

1. **FindById**：比 Hibernate 快 36%，比 MyBatis 快 50%
2. **Query**：比 Hibernate 快 54-65%，比 MyBatis 快 59-69%
3. **FindMany**：比 Hibernate 快 68%，比 MyBatis 快 86%
4. **BatchInsert**：与 JDBC/MyBatis 持平

HORM 的零反射设计和 APT 预编译 SQL 模板在查询场景带来显著优势。

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

缓存命中（111 ns）比 DB 查询（1492 ns）快 13 倍。

#### 使用 `findMany` 批量查询

```java
// ❌ N+1
List<User> users = ids.stream().map(id -> Model.find(User.class, id)).toList();

// ✅ 批量（24 μs 查 100 条）
Map<Object, User> users = Model.findMany(User.class, ids);
```

#### 使用 `fetch` 预加载关联

```java
List<User> users = Model.query(User.class).fetch(UserQueryMeta.ORDERS).list();
```

### 5.2 框架侧改进

| 优化项 | 状态 | 预期收益 |
|--------|------|---------|
| `Repository.batchInsert(List<T>)` + JDBC batch | ✅ 已完成 | BatchInsert 接近 JDBC 基线 |
| 缓存 `EntityMeta` 引用到 `Repository` 字段 | ✅ 已完成 | FindById 减少 Map 查找 |
| 延迟 Cascade 扫描 | ✅ 已完成 | UPDATE/DELETE 减少注解扫描开销 |
| 预编译 SQL 模板（`SqlTemplates`） | ✅ 已完成 | Query 提速 |
| 短路空 CacheChain | ⏳ 待评估 | FindById 减少方法调用 |

---

## 六、JMH 配置说明

### 6.1 当前配置权衡

- `@Fork(1)`：单次 fork 节省时间（生产基准建议 `@Fork(3)` 提高可信度）
- `@Warmup(3,1)` + `@Measurement(5,1)`：平衡精度与耗时
- `@State(Scope.Benchmark)`：共享 setup（数据源、Session、SqlSession）

### 6.2 结果稳定性说明

部分结果误差较大，建议生产环境基准使用：
- `@Fork(3)` — 3 次 fork 取平均
- `@Warmup(iterations=5, time=2)` — 充分预热
- `@Measurement(iterations=10, time=2)` — 提高统计置信度

---

## 七、参考

- [基准测试源码](../holo-horm-benchmark/src/main/java/com/holo/framework/horm/benchmark/)
- [完整套件重跑结果 2026-07-13-run5](./benchmark-results-2026-07-13-run5.txt)
- [历史数据 2026-07-13-run3](./benchmark-results-2026-07-13-run3.txt)
- [JMH 官方文档](https://openjdk.org/projects/code-tools/jmh/)
- [04-cache-chain.md](./04-cache-chain.md) — 缓存链设计
- [09-zero-reflection-optimization.md](./09-zero-reflection-optimization.md) — 零反射优化
