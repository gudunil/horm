# HORM 性能基准报告

> 本文记录 HORM 1.0.0-SNAPSHOT 的 JMH 性能基准测试设计、典型结果范围与分析。
>
> **重要**：本文表格中的数据为**基于设计的预期范围**，实际数值取决于硬件、JVM 版本、数据规模。若要获取本机真实数据，请按 [3.1 运行基准](#31-运行基准) 章节执行。

---

## 一、测试环境

### 1.1 推荐基准环境

| 项目 | 推荐配置 |
|------|---------|
| OS | Linux x86_64 / Windows 10+ |
| JDK | 17 (Temurin / Zulu) |
| JVM 参数 | `-Xms256m -Xmx256m` |
| CPU | 4 核以上 |
| 内存 | 8 GB 以上 |
| 数据库 | H2 内存库（`jdbc:h2:mem:bench;MODE=MySQL;DB_CLOSE_DELAY=-1`） |

### 1.2 对比框架

| 框架 | 版本 | 用途 |
|------|------|------|
| HORM | 1.0.0-SNAPSHOT | 待测对象 |
| 手写 JDBC | JDK 17 `java.sql` | 性能上限基线 |
| MyBatis | 3.5.16 | 流行半自动 ORM |
| Hibernate ORM | 6.4.4.Final | 流行全自动 ORM |

### 1.3 数据集

- 表：`bench_users(id BIGINT PRIMARY KEY AUTO_INCREMENT, email VARCHAR(255), name VARCHAR(255), created_at TIMESTAMP)`
- 预填充：1000 条记录（id 从 1 到 1000）
- 每次迭代前清理（针对 INSERT/UPDATE/DELETE）

---

## 二、基准测试设计

### 2.1 测试矩阵

| 基准类 | 场景 | 方法数 | 时间单位 | 配置 |
|--------|------|--------|---------|------|
| `FindByIdBenchmark` | 按主键查 1 条 | 4 | μs | Warmup(3,1) / Measure(5,1) / Fork(1) |
| `CrudBenchmark` | INSERT / UPDATE / DELETE | 12 | μs | Warmup(3,1) / Measure(5,1) / Fork(1) |
| `QueryBenchmark` | 条件查询 + 排序 + 分页（10/100 条） | 8 | μs | Warmup(3,1) / Measure(5,1) / Fork(1) |
| `CacheBenchmark` | 缓存命中 / 未命中 / 无缓存 | 3 | ns | Warmup(5,1) / Measure(10,1) / Fork(1) |
| `BatchInsertBenchmark` | 批量插入（100/1000 条） | 8 | ms | Warmup(2,1) / Measure(3,1) / Fork(1) |
| `FindManyBenchmark` | 批量查询 100 ID | 4 | μs | Warmup(3,1) / Measure(5,1) / Fork(1) |

### 2.2 JMH 配置说明

- `@BenchmarkMode(Mode.AverageTime)` — 测量平均执行时间
- `@OutputTimeUnit` — 按场景选择：缓存 ns 级，单条 μs 级，批量 ms 级
- `@Fork(1)` — 单次 fork 节省时间（生产基准建议 `@Fork(3)` 提高可信度）
- `@Warmup(iterations=3, time=1)` + `@Measurement(iterations=5, time=1)` — 平衡精度与耗时
- `@State(Scope.Benchmark)` — 共享 setup（数据源、Session、SqlSession）

### 2.3 公平性保证

- 所有框架共享同一 H2 schema 与 1000 条预填充数据
- 所有框架使用相同 JDBC 连接池（H2 内置）
- Hibernate 关闭二级缓存（`hibernate.cache.use_second_level_cache=false`）
- MyBatis 不启用自定义拦截器
- HORM 默认不启用缓存链（`CacheBenchmark` 除外）

---

## 三、运行基准

### 3.1 运行基准

```bash
# 编译
mvn -pl holo-horm-benchmark -am compile -Pskip-enforcer

# 打包可执行 jar
mvn -pl holo-horm-benchmark -am package -Pskip-enforcer -DskipTests

# 运行全部基准
java -jar holo-horm/holo-horm-benchmark/target/benchmarks.jar

# 运行指定基准（如仅 FindById）
java -jar holo-horm/holo-horm-benchmark/target/benchmarks.jar FindByIdBenchmark

# 输出 CSV 结果
java -jar holo-horm/holo-horm-benchmark/target/benchmarks.jar \
    FindByIdBenchmark -rf csv -rff results.csv
```

### 3.2 入口类

`com.holo.framework.horm.benchmark.BenchmarkRunner`：

```java
public static void main(String[] args) throws RunnerException {
    OptionsBuilder builder = new OptionsBuilder()
        .include(FindByIdBenchmark.class.getSimpleName())
        .include(CrudBenchmark.class.getSimpleName())
        .include(QueryBenchmark.class.getSimpleName())
        .include(CacheBenchmark.class.getSimpleName())
        .include(BatchInsertBenchmark.class.getSimpleName())
        .include(FindManyBenchmark.class.getSimpleName());

    if (args.length > 0) {
        builder = new OptionsBuilder().include(args[0]);
    }
    new Runner(builder.build()).run();
}
```

---

## 四、预期结果范围

> 以下数据为**基于 HORM 零反射设计的性能预期**，单位为平均执行时间（越低越好）。

### 4.1 FindById（按主键查 1 条）

| 框架 | 平均时间（μs） | 相对 JDBC |
|------|---------------|----------|
| 手写 JDBC | 8 - 15 | 1.0x |
| **HORM** | **9 - 18** | **1.1x - 1.2x** |
| MyBatis | 12 - 22 | 1.5x - 1.6x |
| Hibernate | 18 - 35 | 2.2x - 2.4x |

**分析**：HORM 通过 APT 生成的 `Mapper` 直接调用 setter，零反射；JDBC 路径仅多一层 `Row → Entity` 映射开销，接近原生性能。Hibernate 因 Session 缓存检查、脏字段跟踪等机制存在额外开销。

### 4.2 CRUD（INSERT / UPDATE / DELETE）

| 操作 | JDBC | HORM | MyBatis | Hibernate |
|------|------|------|---------|-----------|
| INSERT | 10 - 18 μs | 11 - 20 μs | 14 - 25 μs | 25 - 45 μs |
| UPDATE | 8 - 15 μs | 9 - 17 μs | 12 - 22 μs | 20 - 38 μs |
| DELETE | 6 - 12 μs | 7 - 14 μs | 10 - 18 μs | 18 - 32 μs |

**分析**：HORM 与 JDBC 差距控制在 10%-15% 内。Hibernate 的 INSERT 较慢主要因为 `persist()` 触发 dirty checking 与 flush 时机控制。

### 4.3 Query（条件查询 + 排序 + 分页）

| 结果集大小 | JDBC | HORM | MyBatis | Hibernate |
|-----------|------|------|---------|-----------|
| 10 条 | 15 - 25 μs | 18 - 30 μs | 22 - 38 μs | 35 - 60 μs |
| 100 条 | 60 - 100 μs | 70 - 120 μs | 85 - 145 μs | 130 - 220 μs |

**分析**：HORM 的 `QueryImpl` 拼装 SQL + `?` 参数绑定，无字符串拼接（避免 SQL 注入），相比 JDBC 多一次 `EntityMapper.map(row)` 调用。

### 4.4 Cache（缓存对比）

| 场景 | 平均时间（ns） | 说明 |
|------|---------------|------|
| cacheHit | 50 - 200 | L1 命中，直接从 Caffeine 取值 |
| cacheMiss | 8_000 - 15_000 | 缓存未命中，查 DB + 回填 |
| noCache | 8_000 - 15_000 | 无 CacheChain，直接查 DB |

**分析**：缓存命中是 ns 级，相比 DB 查询快 40-150 倍。`cacheMiss` 与 `noCache` 接近（miss 多一次 invalidate + 回填开销）。

### 4.5 BatchInsert（批量插入）

| 批量大小 | JDBC | HORM | MyBatis | Hibernate |
|---------|------|------|---------|-----------|
| 100 条 | 5 - 12 ms | 6 - 15 ms | 8 - 20 ms | 15 - 35 ms |
| 1000 条 | 40 - 90 ms | 45 - 110 ms | 60 - 150 ms | 120 - 280 ms |

**分析**：
- JDBC 用 `addBatch` + `executeBatch` 最快
- HORM 当前用循环 `repository.save(entity)`，未做 batch 优化（M10 计划）
- Hibernate 每 50 条 `flush()` + `clear()` 避免 Session 膨胀，但仍较慢

### 4.6 FindMany（批量查询 100 ID）

| 框架 | 平均时间（μs） |
|------|---------------|
| 手写 JDBC | 100 - 180 |
| **HORM** | **110 - 210** |
| MyBatis | 140 - 260 |
| Hibernate | 200 - 380 |

**分析**：HORM 的 `findMany` 先查 L1 缓存（若启用），未命中 key 用 `SELECT ... WHERE id IN (?,?,...)` 一次性回填。Hibernate 的 `byMultipleIds().multiLoad()` 内部走 batch loader，但仍有 Session 缓存开销。

---

## 五、性能优化建议

### 5.1 启用缓存链

对读多写少的实体（如配置表、字典表）启用 `@Cached` + `WriteStrategy.THROUGH`：

```java
@Entity
@Cached(levels = {CacheLevel.L1},
        policy = @CachePolicy(ttl = "1h", writeStrategy = WriteStrategy.THROUGH))
public class DictItem extends Model<DictItem> { ... }
```

参考 [04-cache-chain.md](./04-cache-chain.md)。

### 5.2 使用 `findMany` 批量查询

避免 N+1 查询，使用 `findMany` 一次性获取：

```java
// ❌ N+1
List<User> users = ids.stream()
    .map(id -> Model.find(User.class, id))
    .toList();

// ✅ 批量
Map<Object, User> users = Model.findMany(User.class, ids);
```

### 5.3 使用 `fetch` 预加载关联

避免 N+1 关联查询：

```java
// ❌ N+1：每个 user 单独查 orders
List<User> users = Model.all(User.class);
for (User u : users) {
    u.getOrders().size();        // 触发 SELECT
}

// ✅ Eager JOIN
List<User> users = Model.query(User.class)
    .fetch(UserQueryMeta.ORDERS)
    .list();
```

### 5.4 合理设置 JMH 参数

生产环境基准建议：
- `@Fork(3)` — 3 次 fork 取平均
- `@Warmup(iterations=5, time=2)` — 充分预热
- `@Measurement(iterations=10, time=2)` — 提高统计置信度

### 5.5 关闭 Hibernate 二级缓存（对比基准）

公平对比时关闭 Hibernate L2 Cache：

```properties
hibernate.cache.use_second_level_cache=false
hibernate.cache.use_query_cache=false
```

---

## 六、性能瓶颈与改进方向

### 6.1 当前已识别瓶颈

| 场景 | 瓶颈 | 改进计划 |
|------|------|---------|
| 批量插入 | 循环 `save()` 未批量化 | M10：实现 `Model.batchInsert(List<T>)` + JDBC batch |
| 查询缓存 | 仅单表、无 JOIN、无投影 | M10：扩展查询缓存支持范围 |
| 关联加载 | 仅 eager JOIN，无 lazy proxy | M10：实现 batch loading 缓解 N+1 |
| TypeReference | `getGenericSuperclass()` 反射 | 保留（Java 泛型擦除唯一方案） |

### 6.2 性能 vs 功能权衡

| 功能 | 性能影响 | 决策 |
|------|---------|------|
| 零反射（APT 生成 Mapper） | 编译期成本，运行时收益 | 保留 |
| 类型安全 DSL（XxxQueryMeta） | 增加类文件体积 | 保留 |
| 乐观锁（@Version） | UPDATE 多一次条件检查 | 按需启用 |
| 缓存链 | 缓存失效/回填开销 | 按需启用 |
| 事务代理（APT 生成子类） | 增加类文件体积 | 保留（替代 Method.invoke） |

---

## 七、参考

- [基准测试源码](../holo-horm-benchmark/src/main/java/com/holo/framework/horm/benchmark/)
- [JMH 官方文档](https://openjdk.org/projects/code-tools/jmh/)
- [04-cache-chain.md](./04-cache-chain.md) — 缓存链设计
- [09-zero-reflection-optimization.md](./09-zero-reflection-optimization.md) — 零反射优化
- [07-performance-security.md](./07-performance-security.md) — 性能与安全设计
