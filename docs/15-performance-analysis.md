# HORM 性能瓶颈深度分析与优化建议

> 本文基于 M9 JMH 基准测试真实结果、HORM 1.0.0-SNAPSHOT 源码静态分析、以及行业 ORM 框架最佳实践调研，对 HORM 当前性能局限性的根因进行系统化剖析，并提出可落地的优化路线。
>
> **分析日期**：2026-07-13
> **分析对象**：HORM 1.0.0-SNAPSHOT（feature/m9-ga-benchmark 分支）
> **基准数据来源**：[13-benchmark-results.md](./13-benchmark-results.md)、[benchmark-results-2026-07-13.txt](./benchmark-results-2026-07-13.txt)

---

## 目录

1. [方法论](#一方法论)
2. [基准测试发现](#二基准测试发现)
3. [根因分析](#三根因分析)
4. [行业最佳实践调研](#四行业最佳实践调研)
5. [优化建议](#五优化建议)
6. [实施优先级与路线图](#六实施优先级与路线图)

---

## 一、方法论

### 1.1 分析路径

本文采用「**自上而下定位 + 自下而上验证**」的三层分析方法：

| 层次 | 输入 | 产出 |
|------|------|------|
| L1 — 基准层 | JMH 真实测试结果（7 个基准类、43 个数据点） | 性能劣势场景定位与量化 |
| L2 — 源码层 | HORM core/cache/meta 三模块热路径源码 | 劣势场景的代码级根因 |
| L3 — 行业层 | MyBatis / Hibernate / jOOQ / EF Core 优化技术 + Caffeine / HikariCP 调优资料 | 可借鉴的优化策略与权衡 |

### 1.2 数据来源

- **基准数据**：2026-07-13 在 Windows 10 + JDK 17.0.18 + H2 内存库环境下运行的真实 JMH 结果，原始日志见 `docs/benchmark-results-2026-07-13.txt`
- **源码版本**：feature/m9-ga-benchmark 分支提交 `5c644e0`
- **行业资料**：通过 WebSearch 收集的 30+ 篇 ORM 性能优化文章、Caffeine W-TinyLFU 论文（arXiv:1512.00727）、JMH 学术研究（TSE 2019）、Hibernate/MyBatis 官方文档

### 1.3 分析边界

**包含**：
- JdbcRepository / QueryImpl 的 CRUD 热路径
- DefaultCacheChain / CaffeineCache 的读写路径
- HormContext / TransactionManager / EntityMetaRegistry 的上下文查找路径
- APT 生成的 Mapper 代码模式

**不包含**：
- Spring Boot Starter 自动装配开销（已由 M8 覆盖）
- 网络层与真实数据库性能（仅 H2 内存库）
- GraalVM Native Image AOT 配置（已由 14-aot-graalvm.md 覆盖）

---

## 二、基准测试发现

### 2.1 性能矩阵全景

下表汇总自 `docs/benchmark-results-2026-07-13.txt`，**越低越好**：

| 场景 | JDBC 基线 | HORM | MyBatis | Hibernate | HORM 排名 |
|------|----------|------|---------|-----------|----------|
| FindById（μs/op） | 0.934 | **7.555** | 3.553 | 2.669 | ❌ 最慢（8.1× JDBC） |
| INSERT（μs/op） | 20.896† | 12.706 | 7.540 | 7.987 | ❌ 倒数第二 |
| UPDATE（μs/op） | 2.185 | **19.077** | 3.567 | 8.573 | ❌ 最慢（8.7× JDBC） |
| DELETE（μs/op） | 7.839 | **20.628** | 7.186 | 9.739 | ❌ 最慢（2.6× JDBC） |
| Query limit=10（μs/op） | 1.710 | **12.945** | 7.755 | 7.589 | ❌ 最慢 |
| Query limit=100（μs/op） | 9.249 | **33.229** | 57.862 | 53.530 | ✅ 仅次于 JDBC |
| FindMany 100 IDs（μs/op） | 12.321 | 72.003 | 162.947 | 73.863 | ✅ 仅次于 JDBC |
| BatchInsert 100（ms/op） | 0.534 | **1.219** | 0.522 | 0.558 | ❌ 最慢（2.3× JDBC） |
| BatchInsert 1000（ms/op） | 5.032 | **11.685** | 5.123 | 6.149 | ❌ 最慢（2.3× JDBC） |
| Cache Hit（ns/op） | — | **145.954** | — | — | ✅ 比 DB 快 49× |
| APT Proxy Direct（ns/op） | — | **3734.602** | — | — | ⚠️ 异常高 |

† JDBC INSERT 误差 113.598 μs（GC/JIT 抖动异常值）

### 2.2 关键发现

#### 发现 1：单条 CRUD 路径开销过大

HORM 在 **FindById / UPDATE / DELETE** 三个高频单条操作上均是最慢，比 MyBatis 慢 2-5 倍。这与 HORM 设计目标「零反射 → 接近 JDBC」严重不符。

#### 发现 2：零反射优势仅在大结果集场景显现

`Query limit=100` 场景下 HORM（33.23 μs）比 Hibernate（53.53 μs）快 38%，比 MyBatis（57.86 μs）快 43%。APT 生成的 `Mapper.map(Row)` 零反射优势在 100 行规模时压过了 Hibernate Session 脏检查与 MyBatis ResultMap 解析的固定开销。但 `limit=10` 时 HORM 反而最慢，说明 **SQL 拼装固定开销在小结果集时占比过高**。

#### 发现 3：批量插入未使用 JDBC batch

HORM `BatchInsertBenchmark.hormBatch` 直接循环 `u.save()`，每次都走完整的 INSERT 流程（连接获取 + PreparedStatement 准备 + 执行 + 主键回填 + 连接释放），未使用 `addBatch/executeBatch`。这与 MyBatis `BatchExecutor`、Hibernate `jdbc.batch_size=20` 形成鲜明对比，导致 2.3× 性能差距。

#### 发现 4：缓存命中性能卓越

L1 Caffeine 缓存命中仅需 146 ns，是无缓存 DB 查询（7165 ns）的 1/49。这是 HORM 当前最大的性能亮点，证明缓存链架构设计正确。

#### 发现 5：APT 事务代理性能异常

`TransactionProxyBenchmark.aptProxyDirect` 测得 3734 ns/op，与 M8.7 设计预期（应接近 `directCall` 的 ns 级，约 7 ns）相差 500 倍。需进一步调查是 benchmark 设计问题还是代理实现问题。

### 2.3 测试方法学局限

当前基准存在以下方法学问题，影响结论可信度：

1. **`@Fork(1)`**：单次 fork 易受 JIT profile 污染。JMH 官方与学术研究（TSE 2019）建议 `@Fork(3)` 以上。这导致 `directCall` 误差 5.522 ns（score 6.805 ns，81% 相对误差）等不稳定结果。
2. **`@Warmup(3, 1) + @Measurement(5, 1)`**：预热与测量迭代较少，Caffeine 等需要时间稳定的数据可能未充分预热。
3. **`@State(Scope.Benchmark)` 共享 Setup**：所有框架共享同一 H2 schema，可能存在缓存污染（虽然 Hibernate L2 已关闭）。
4. **JVM 堆仅 256 MB**：`-Xms256m -Xmx256m` 偏小，GC 压力大，导致 JDBC INSERT 误差达 113.598 μs 异常值。

---

## 三、根因分析

### 3.1 根因 1：每次操作新建 JdbcRepository（影响：FindById / CRUD）

**代码位置**：[Horm.java#L114-L116](../holo-horm-core/src/main/java/com/holo/framework/horm/core/Horm.java#L114-L116)

```java
public static <T extends Model<T>> Repository<T> repository(Class<T> entityType) {
    return new JdbcRepository<>(entityType, HormContext.current());
}
```

**调用链**：`Model.find(Class, id)` → `Horm.repository(type)` → `new JdbcRepository<>(...)`

**JdbcRepository 构造函数开销**（[JdbcRepository.java#L62-L69](../holo-horm-core/src/main/java/com/holo/framework/horm/core/JdbcRepository.java#L62-L69)）：

```java
public JdbcRepository(Class<T> entityType, HormContext ctx) {
    this.entityType = entityType;
    this.ctx = ctx;
    this.meta = EntityMetaRegistry.lookup(entityType);       // ① ConcurrentHashMap.get
    this.mapper = meta.mapper();                              // ② 已是字段访问，无开销
    this.dataSourceName = MetaSupport.resolveDataSourceName(meta);  // ③ 字符串比较
    this.runtimeCachePolicy = MetaSupport.toRuntimePolicy(meta.cachePolicy());  // ④ 每次 new CachePolicy builder
}
```

**每次 find 调用的固定开销**：
- 1 次 `new JdbcRepository` 对象分配（含 6 个字段初始化）
- 1 次 `EntityMetaRegistry.lookup`（ConcurrentHashMap.get，约 10-30 ns）
- 1 次 `MetaSupport.toRuntimePolicy`（创建 CachePolicy builder + 7 次 setter，约 200-500 ns）
- 1 次 `new TypeReference<>() {}` 匿名类实例化（[JdbcRepository.java#L59](../holo-horm-core/src/main/java/com/holo/framework/horm/core/JdbcRepository.java#L59)）
- 1 次 `HormContext.current()`（volatile read + null 检查）

**估算**：每次 find 调用前已经消耗约 500-1000 ns 在框架开销上，这与 FindById 实测 7555 ns 中约 10-15% 是构造开销吻合。

### 3.2 根因 2：SQL 每次重新拼装（影响：所有 CRUD / Query）

**代码位置**：[JdbcRepository.java#L83-L90](../holo-horm-core/src/main/java/com/holo/framework/horm/core/JdbcRepository.java#L83-L90)

```java
private T dbFind(Object id) {
    FieldMeta<?> idField = requireIdField();
    String sql = "SELECT * FROM " + MetaSupport.qualifiedTable(meta)
        + " WHERE " + idField.column() + " = ?";   // 每次都拼字符串
    return JdbcOperations.query(ctx, dataSourceName, sql, List.of(id), ...);
}
```

**类似问题在以下方法都存在**：
- `JdbcRepository.all()` — `SELECT * FROM {table}`
- `JdbcRepository.count()` — `SELECT COUNT(*) FROM {table}`
- `JdbcRepository.insert()` — 动态拼 INSERT + VALUES 占位符
- `JdbcRepository.update()` — 动态拼 UPDATE SET + WHERE
- `JdbcRepository.delete()` — 动态拼 DELETE FROM + WHERE
- `JdbcRepository.dbFindMany()` — 动态拼 IN 占位符
- `QueryImpl.dbList()` — StringBuilder 拼 SELECT/WHERE/ORDER BY/LIMIT

**INSERT 路径特别严重**（[JdbcRepository.java#L134-L159](../holo-horm-core/src/main/java/com/holo/framework/horm/core/JdbcRepository.java#L134-L159)）：

```java
List<String> columns = meta.fields().stream()       // ① stream 遍历所有字段
    .filter(FieldMeta::insertable)                  // ② filter 1
    .filter(f -> !f.isId())                         // ③ filter 2
    .map(FieldMeta::column)                         // ④ map
    .toList();                                       // ⑤ 终结操作，分配新 List
String sql = "INSERT INTO " + ... + " (" 
    + String.join(", ", columns)                    // ⑥ 字符串拼接
    + ") VALUES ("
    + columns.stream().map(c -> "?").collect(Collectors.joining(", "))  // ⑦ 又一次 stream
    + ")";
Row row = mapper.toRow(entity);                     // ⑧ 创建 LinkedHashMap
List<Object> bindings = columns.stream().map(row::get).toList();  // ⑨ 又一次 stream
```

**每次 INSERT 涉及**：3 次 stream 操作、2 次 `String.join`、1 次 `Collectors.joining`、1 个新 ArrayList、1 个 LinkedHashMap（Row）、1 个 ArrayList（bindings）。

**对比 MyBatis**：MyBatis 启动时解析 XML 中的 SQL 模板并缓存为 `SqlSource`，运行时仅做参数绑定，无字符串拼接。这是 MyBatis INSERT（7.54 μs）vs HORM INSERT（12.71 μs）1.7× 差距的主因之一。

### 3.3 根因 3：Row 中间层引入双重开销（影响：所有 SELECT 路径）

**代码位置**：[MetaSupport.java#L64-L70](../holo-horm-core/src/main/java/com/holo/framework/horm/core/MetaSupport.java#L64-L70) + [MapperBuilder.java#L65-L89](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/processor/MapperBuilder.java#L65-L89)

`MetaSupport.toRow` 将 `ResultSet` 转为 `Row`（LinkedHashMap），再由 APT 生成的 `Mapper.map(Row)` 转为实体：

```java
// 第一步：ResultSet → Row（MetaSupport.toRow）
public static Row toRow(ResultSet rs, EntityMeta<?> meta) throws SQLException {
    Row row = Row.create(meta.tableName());          // new MapRow + new LinkedHashMap
    for (FieldMeta<?> fd : meta.fields()) {
        row.set(fd.column(), rs.getObject(fd.column()));  // 每字段一次 getObject + put
    }
    return row;
}

// 第二步：Row → Entity（APT 生成的 Mapper.map）
public User map(Row row) {
    User u = new User();
    if (row.has("id")) u.setId(row.getLong("id"));         // containsKey + get + 类型转换
    if (row.has("email")) u.setEmail(row.getString("email"));
    if (row.has("name")) u.setName(row.getString("name"));
    if (row.has("created_at")) u.setCreatedAt(row.getInstant("created_at"));
    return u;
}
```

**每行实体的映射开销**：
- 1 个 LinkedHashMap 分配（Row.MapRow.values）
- N 次 `rs.getObject(column)` 调用（N = 字段数，BenchUser 为 4）
- N 次 `LinkedHashMap.put`
- N 次 `row.has(column)`（= `containsKey + null check`）
- N 次 `row.getXxx(column)`（= `get + instanceof 链 + 类型转换`）

**对比手写 JDBC**：

```java
// JdbcSetup.findById 直接 ResultSet → Entity
User u = new User();
if (rs.next()) {
    u.setId(rs.getLong("id"));
    u.setEmail(rs.getString("email"));
    u.setName(rs.getString("name"));
    u.setCreatedAt(rs.getTimestamp("created_at").toInstant());
}
```

手写 JDBC 完全省略了 Row 中间层，N 次 `rs.getXxx` 直接写入 setter。这是 HORM FindById（7.56 μs）vs JDBC（0.93 μs）8.1× 差距的关键来源。

**Row 中间层的设计意图**是数据源无关（JDBC/Mongo/HTTP 都能复用 Mapper），但对 JDBC 场景引入了不必要的中间分配。`Row.MapRow` 的 `getLong`/`getInstant` 等还包含 `instanceof` 类型检查链（[Row.java#L85-L117](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/Row.java#L85-L117)），进一步增加开销。

### 3.4 根因 4：Cascade 扫描开销（影响：UPDATE / DELETE）

**代码位置**：[Model.java#L94-L98](../holo-horm-core/src/main/java/com/holo/framework/horm/core/Model.java#L94-L98)

```java
public final void save() {
    Set<Object> visited = new HashSet<>();          // 每次都分配 HashSet
    visited.add(this);
    cascadePersist(this, visited);                  // 进入 cascadePersist
}
```

`cascadePersist` 即使实体没有任何关联（如 BenchUser），也会执行（[Model.java#L300-L323](../holo-horm-core/src/main/java/com/holo/framework/horm/core/Model.java#L300-L323)）：

```java
private static void cascadePersist(Model entity, Set<Object> visited) {
    EntityMeta meta = EntityMetaRegistry.lookup(entity.getClass());  // ① 又一次 Registry 查找
    for (Object r : meta.relations()) {        // ② 遍历关联（即使为空）
        RelationMeta rel = (RelationMeta) r;
        if (!hasCascadeType(rel, CascadeType.PERSIST)) continue;  // ③ 即便有关联也多数跳过
        if (rel.type() == RelationType.BELONGS_TO) { ... }
    }
    entity.repository().save(entity);           // ④ 又一次 new JdbcRepository！
    // ... 第二轮遍历 children
}
```

**每次 save 的额外开销**：
- 1 个 HashSet 分配
- 1 次 EntityMetaRegistry.lookup（已在 JdbcRepository 构造时查过一次，重复）
- 2 次遍历 `meta.relations()`（BELONGS_TO 一轮 + HAS_ONE/HAS_MANY 一轮）
- `entity.repository()` 调用 `Horm.repository(getClass())` 又构造一个新的 JdbcRepository

**对比 MyBatis UPDATE**：MyBatis 直接执行预定义的 UPDATE SQL，无任何 cascade 扫描，所以 UPDATE 仅 3.57 μs vs HORM 19.08 μs。

### 3.5 根因 5：缓存链未集成 SingleFlightLoader（影响：缓存击穿场景）

**关键发现**：[SingleFlightLoader.java](../holo-horm-cache/src/main/java/com/holo/framework/horm/cache/SingleFlightLoader.java) 类已实现完整的 single-flight 语义（`ConcurrentHashMap.computeIfAbsent` 共享 in-flight Future），但 **DefaultCacheChain 完全没有使用它**。

[DefaultCacheChain.java#L170-L233](../holo-horm-cache/src/main/java/com/holo/framework/horm/cache/DefaultCacheChain.java#L170-L233) 的 `get(key, type, loader, policy)` 方法在所有 tier 都 miss 后，**直接调用 `loader.get()`**，没有任何 single-flight 保护：

```java
// All tiers missed: invoke the loader.
V loaded;
try {
    loaded = loader.get();   // ⚠️ N 个线程同时 miss 时，N 次执行 loader！
} catch (RuntimeException | Error ex) { ... }
```

**风险场景**：高并发下热点 key 过期或被invalidate 时，所有请求线程同时 miss → 同时执行 loader（JDBC 查询）→ 数据库连接池耗尽。这正是 Caffeine 官方文档与 Redis 雪崩防护资料中强调的「缓存击穿」问题。

**Caffeine 自身的 `computeIfAbsent`** 在单 tier 内有锁保护，但 DefaultCacheChain 跨 L1+L2 链式查询时绕过了 Caffeine 的内建锁。

### 3.6 根因 6：CacheEvent 在无监听器时仍被构造（影响：缓存命中热路径）

**代码位置**：[DefaultCacheChain.java#L146-L167](../holo-horm-cache/src/main/java/com/holo/framework/horm/cache/DefaultCacheChain.java#L146-L167)

```java
for (int i = 0; i < size; i++) {
    Cache tier = levels.get(i);
    Optional<V> cached = tier.get(key, type);
    if (cached.isPresent()) {
        V value = cached.get();
        publishEvent(CacheEvent.of(                                  // ⚠️ 每次命中都构造 CacheEvent
            CacheEventType.HIT, tier.name(), tier.level(), key, value));
        backFillAbove(key, value, i, null);
        ...
    }
    publishEvent(CacheEvent.of(                                      // ⚠️ 每次 miss 也构造
        CacheEventType.MISS, tier.name(), tier.level(), key, null));
}
```

**publishEvent 内部有监听器检查**（[DefaultCacheChain.java#L544-L559](../holo-horm-cache/src/main/java/com/holo/framework/horm/cache/DefaultCacheChain.java#L544-L559)）：

```java
void publishEvent(CacheEvent event) {
    Objects.requireNonNull(event, "event");
    for (CacheEventListener listener : listeners) {   // listeners 为空时跳过循环
        ...
    }
}
```

**问题**：`CacheEvent.of(...)` 在调用 `publishEvent` **之前**就已经构造，即使 `listeners` 为空也已经分配了对象。在 Cache Hit 热路径（146 ns/op）中，每次命中都创建 1-2 个 CacheEvent 对象，给年轻代 GC 增加压力。

### 3.7 根因 7：QueryHash 每次 SHA-256 计算（影响：查询缓存）

**代码位置**：[QueryHash.java#L103-L115](../holo-horm-cache/src/main/java/com/holo/framework/horm/cache/key/QueryHash.java#L103-L115)

```java
private static String sha256Truncated(String input, int truncateLength) {
    MessageDigest md;
    try {
        md = MessageDigest.getInstance("SHA-256");   // ⚠️ 每次都查 Provider
    } catch (NoSuchAlgorithmException e) { ... }
    byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
    String hex = bytesToHex(digest);
    return hex.substring(0, Math.min(truncateLength, hex.length()));
}
```

**问题**：
- `MessageDigest.getInstance("SHA-256")` 每次都经过 JCA Provider 查找（约 1-5 μs）
- 即使 SHA-256 算法在 JVM 中是内置的，查找过程仍涉及 `Provider.getService` 同步开销
- `bytesToHex` 每次分配 StringBuilder

**优化方向**：缓存 `MessageDigest` 实例（thread-local 或 clone），或换用更快的 hash（xxHash、MurmurHash3）。

### 3.8 根因 8：DefaultCacheChain.backFillAbove 调用 defaultBackFillPolicy（影响：缓存回填）

**代码位置**：[DefaultCacheChain.java#L585-L596](../holo-horm-cache/src/main/java/com/holo/framework/horm/cache/DefaultCacheChain.java#L585-L596)

```java
private <K, V> void backFillAbove(K key, V value, int hitIndex, CachePolicy policy) {
    if (hitIndex <= 0) {
        return;
    }
    CachePolicy effectivePolicy = policy != null ? policy : defaultBackFillPolicy();
    for (int i = 0; i < hitIndex; i++) {
        levels.get(i).put(key, value, effectivePolicy);
    }
}
```

虽然 `defaultBackFillPolicy()` 通过静态内部类懒加载（线程安全），但 `get(key, type)`（无 loader 版本）调用时传入 `policy = null`，导致每次命中都走 `defaultBackFillPolicy()` 路径。对 L1-only 场景（hitIndex 通常为 0）影响小，但对 L1+L2 链式缓存影响明显。

### 3.9 根因 9：事务代理性能异常（影响：@Transactional 方法调用）

`TransactionProxyBenchmark.aptProxyDirect` 原测得 3734 ns/op，远超 JDK 动态代理（4.99 ns）和 Method.invoke（11.86 ns）。

**调查结果**：
- 3734 ns 的绝大部分并非代理分发开销，而是 benchmark 每次都经过真实 JDBC 连接生命周期：
  - `BenchDataSourceProvider.getConnection()` 调用 `DriverManager.getConnection(...)` 新建 H2 内存连接
  - `TransactionManager.begin()` 设置 `setAutoCommit(false)`
  - `TransactionManager.commit()` 调用 `Connection.commit()`
  - `TransactionManager.cleanup()` 恢复 `setAutoCommit(true)` 并 `close()` 连接
- 为隔离代理本身开销，新增 `NoOpDataSourceProvider`：返回一个 JDK 动态代理的 `Connection`，`commit/rollback/close/setAutoCommit` 全部 no-op。
- 重新运行后 `aptProxyDirect` 降至 **68 ns/op**，较之前降低 **98%**。

| 基准 | 修复前 (ns/op) | 修复后 (ns/op) | 说明 |
|------|---------------|---------------|------|
| directCall | ~7 | 4.7 | 纯方法调用，无事务 |
| methodInvoke | ~11.9 | 11.8 | 反射调用 |
| methodBridge | — | 11.6 | LambdaMetafactory |
| aptProxyDirect | **3734** | **68** | APT 代理 + 事务边界（no-op 连接） |
| jdkDynamicProxy | ~5 | 4.8 | 因 `BenchService` 无接口，实际退化为 directCall |

**结论**：APT 代理本身的开销约为 **63 ns/op**（相对 directCall），属于合理范围。原 3734 ns 是 benchmark 设计问题（测量了不该测量的数据库连接开销），并非代理实现缺陷。

**遗留问题**：
- `jdkDynamicProxy` 基准当前因 `BenchService` 未实现接口而退化，建议后续让 `BenchService` 实现接口，或新增 `BenchServiceInterface` 以公平对比 JDK 动态代理。
- 真实生产环境（使用连接池）中，`@Transactional` 方法的真实成本仍包括从连接池获取/归还连接，但该成本与代理实现无关。

### 3.10 根因汇总表

| 根因 | 影响场景 | 估算开销 | 修复难度 |
|------|---------|---------|---------|
| R1：每次 new JdbcRepository | FindById / CRUD | 500-1000 ns | 易 |
| R2：SQL 每次重新拼装 | 所有 CRUD / Query | 1-3 μs | 中 |
| R3：Row 中间层双重映射 | 所有 SELECT | 2-4 μs（按字段数） | 难（架构层） |
| R4：Cascade 扫描开销 | UPDATE / DELETE | 1-2 μs | 易 |
| R5：SingleFlightLoader 未集成 | 缓存击穿场景 | 无固定开销，并发风险 | 中 |
| R6：CacheEvent 无监听器仍构造 | 缓存命中热路径 | 50-100 ns | 易 |
| R7：QueryHash SHA-256 重计算 | 查询缓存 | 1-5 μs | 易 |
| R8：defaultBackFillPolicy 回填 | L1+L2 链缓存命中 | 100-300 ns | 易 |
| R9：事务代理异常 | @Transactional | 3700+ ns（实测为 benchmark 设计问题，代理本身约 63 ns） | 已调查清楚 |

---

## 四、行业最佳实践调研

本节汇总通过 WebSearch 收集的 6 大主题权威资料，详见各小节末尾的来源链接。

### 4.1 主流 ORM 框架性能优化技术

#### MyBatis Executor 分层

MyBatis 提供三档 Executor 策略：

| Executor | 行为 | 适用场景 |
|----------|------|---------|
| `SimpleExecutor`（默认） | 每次创建 PreparedStatement，用完关闭 | 通用 |
| `ReuseExecutor` | 缓存 PreparedStatement（按 SQL 模板），复用 | 高频同模板查询 |
| `BatchExecutor` | JDBC `addBatch/executeBatch` 合并提交 | 批量 INSERT/UPDATE |

**关键启示**：HORM 当前所有操作都等价于 SimpleExecutor，缺少 ReuseExecutor 与 BatchExecutor。`statement` 复用对高频同模板查询（如 `SELECT * FROM users WHERE id = ?`）可省去 PreparedStatement 准备开销。

#### Hibernate StatelessSession

Hibernate `StatelessSession` 不实现一级缓存、不与二级/查询缓存交互、不做脏检查，更接近 JDBC 层。常规 `Session` 必须定期 `flush() + clear()` 防止 L1 膨胀导致 OOM（10 万行 naive insert 在 5 万行 OOM）。

**关键启示**：HORM 应提供 `Horm.stateless()` API，绕过缓存链与 cascade 扫描，专供批量 ETL 场景。

**来源**：
- [MyBatis Executor 详解](https://blog.csdn.net/liuyinghui523/article/details/160795631)
- [Hibernate 批处理](https://docs.hibernate.org/core/3.3/reference/en-US/html/batch.html)
- [Baeldung: Hibernate StatelessSession](https://www.baeldung.com/hibernate-stateless-session)

### 4.2 APT 代码生成 ORM 最佳实践

#### Doma2 的三层产物

Doma2（日本 SEZOOM 公司开源）基于 JSR 269，编译期生成三层产物：

1. **EntityMeta** — 字段元数据
2. **Dao 实现** — 直接调用 JDBC
3. **Criteria 元模型** — `_User` 后缀的类型安全查询类

Doma2 还支持**编译期 SQL 校验**（`doma.sql.validation`），SQL 模板错误在 IDE 红线报错，而非运行时才发现。

#### jOOQ Codegen

jOOQ 从数据库 schema 生成表/列/约束/索引/序列/存储过程的 Java 表示，编译器即时感知 schema 漂移。每个表、每列、每个约束都生成对应的 Java 类。

**关键启示**：HORM 的 APT 已经生成 EntityMeta + Mapper + QueryMeta，与 Doma2 设计接近。可考虑增加：
1. 编译期 SQL 模板校验（针对 `@Query` 注解或 SQL 文件）
2. 投影 DTO 自动生成（消除 `SELECT *`）

**来源**：
- [Doma2 Annotation Processing](https://docs.domaframework.org/en/2.53.0/annotation-processing/)
- [Doma2 Codegen](https://docs.domaframework.org/en/stable/codegen/)
- [jOOQ Codegen 优势](https://blog.jooq.org/why-you-should-use-jooq-with-code-generation/)

### 4.3 Caffeine 缓存高级调优

#### W-TinyLFU 算法

Caffeine 使用 W-TinyLFU（Windowed TinyLFU）算法，结构如下：

```
              ┌─────────────────────────────────────────────┐
              │                  Window（~1%）                │
              │           吸收突发访问模式                     │
              └────────────────────┬────────────────────────┘
                                   │
              ┌────────────────────▼────────────────────────┐
              │            Admission Filter                  │
              │   Count-Min Sketch 频率对比，决定是否准入      │
              └────────────────────┬────────────────────────┘
                                   │
              ┌────────────────────▼────────────────────────┐
              │              Main SLRU Cache                 │
              │  ┌─────────────────┐  ┌──────────────────┐  │
              │  │ Probation（20%）│  │ Protected（79%）  │  │
              │  │   新晋升温区     │  │   稳定热数据区     │  │
              │  └─────────────────┘  └──────────────────┘  │
              └─────────────────────────────────────────────┘
```

- **Window**：约 1% 容量，吸收突发访问（新 key 先进这里）
- **Admission Filter**：Count-Min Sketch 频率对比，新 key 击败低频旧 key 才能进入 Main
- **Main SLRU**：Probation（20%）→ Protected（79%），第二次访问才升温到 Protected
- **Aging**：定期将 FrequencySketch 计数器减半，保留近态、淘汰历史

**实测数据**：scan-loop 负载下 LRU 命中率 ~0%，W-TinyLFU ~45%；search workload +13%。

#### 缓存三防

| 问题 | 成因 | 解决方案 |
|------|------|---------|
| 穿透 | 查询不存在的 key，反复打 DB | 布隆过滤器 + 空值短 TTL（5 min） |
| 击穿 | 热点 key 过期，瞬间高并发打 DB | SingleFlight 互斥重建 / refresh-ahead |
| 雪崩 | 大量 key 同时过期 | TTL jitter ±5-10% |

**关键启示**：
1. HORM 的 `NullMarker` 已实现空值缓存（穿透防护）✅
2. HORM 的 `SingleFlightLoader` 已实现击穿防护，但未集成到 `DefaultCacheChain` ❌（见根因 R5）
3. HORM 的 `TtlJitter` 类已存在但需检查是否在 CachePolicy 默认配置中启用

**来源**：
- [Caffeine W-TinyLFU 详解](https://blog.csdn.net/2401_89214369/article/details/158073385)
- [W-TinyLFU 论文](https://arxiv.org/pdf/1512.00727)
- [缓存三防详解](https://chanjunren.github.io/docs/zettelkasten/backend/caching/cache_penetration_breakdown_avalanche)
- [Redis 雪崩防护](https://redis.io/blog/how-to-tame-the-thundering-herd-problem.md)

### 4.4 JDBC 批处理与连接池优化

#### rewriteBatchedStatements（MySQL）

MySQL JDBC 驱动的 `rewriteBatchedStatements=true` 参数会重写批量 SQL：

| 操作 | 原始 | 重写后 |
|------|------|--------|
| INSERT | `INSERT INTO t VALUES (?)` × N | `INSERT INTO t VALUES (?),(?),...,(?)` 单条 |
| UPDATE/DELETE | `UPDATE t SET ... WHERE id=?` × N | `UPDATE t SET ... WHERE id=?; UPDATE ... ;` 拼接 |

**实测数据**：50000 条数据，每批 500，无 rewrite 1295s → 加 rewrite 7s，**185× 提升**。

⚠️ **限制**：
- `batchSize <= 3` 时驱动仍逐条执行
- 受 `max_allowed_packet` 限制（默认 4MB），超限自动拆分
- `IDENTITY` 主键生成策略会自动禁用批处理（Hibernate 文档明确说明）

#### HikariCP 连接池调优

**PoolSize 公式**：`connections = (core_count × 2) + effective_spindle_count`

- 8 核 SSD 服务器 → (8 × 2) + 1 = 17 连接即可
- 连接过多反而引发 MySQL 互斥锁争用，200 → 30 连接反而 QPS +40%

**超时三件套**：
- `connectionTimeout` ≤ 3s（默认 30s 过长）
- `maxLifetime` < 网络设备超时（建议 30 min = 1800000 ms）
- `leakDetectionThreshold` = 60s（测试期兜底）

**PreparedStatement 缓存**：HikariCP 不直接缓存，依赖驱动参数 `useServerPrepStmts=true` + `prepStmtCacheSize=250` + `prepStmtCacheSqlLimit=2048`。

**关键启示**：HORM 的 `holo-horm-datasource` 模块仍在规划中（POM 已建），实现时应默认开启 `rewriteBatchedStatements` + 提供 HikariCP 公式化配置。

**来源**：
- [MySQL rewriteBatchedStatements 详解](https://blog.csdn.net/tolcf/article/details/52102849)
- [JPA vs JDBC 33× 差距](https://velog.io/@zbnerd/성능-튜닝-1만-건-데이터-삽입-JPA-vs-JDBC-성능-33배-차이의-비밀-15.2s-0.4s)
- [HikariCP 调优公式](https://oneuptime.com/blog/post/2026-01-25-tune-hikaricp-maximum-throughput-spring-boot/view)
- [High-Performance Java Persistence](http://samples.leanpub.com/high-performance-java-persistence-sample.pdf)

### 4.5 JMH 基准测试规范

#### 参数选择原则

| 参数 | 推荐值 | 原因 |
|------|--------|------|
| `@Fork` | ≥ 3 | 每次 fork 独立 JVM，防 profile 污染；统计置信度 |
| `@Warmup` | 3-5 iter × 1-2s | JIT 充分预热 |
| `@Measurement` | 5-10 iter × 1-2s | 提高统计置信度 |
| `@BenchmarkMode` | Throughput + AverageTime | 不同维度看问题 |

#### 死代码消除（DCE）防护

JIT 会消除无副作用的方法返回值。JMH 自动处理返回值消费，但若方法返回 `void` 需显式注入 `Blackhole`。

**Bug 案例**（CODETOOLS-7901494）：`Blackhole.consume(boolean)` 因 JIT 分支预测差异导致 fork 间 2× 波动（3700 vs 7400 ns/op），证明 fork 数与稳定性至关重要。

#### 学术研究

TSE 2019 论文分析 123 个 OSS 项目中 35 个存在 JMH 坏实践，12 个项目坏实践 > 10 处。常见问题：fork=1、无 warmup、void 返回值无 Blackhole。

**关键启示**：HORM 当前基准 `@Fork(1)` + `@Warmup(3,1)` + `@Measurement(5,1)` 偏激进，建议升级为 `@Fork(3)` + `@Warmup(5,2)` + `@Measurement(10,2)`，并增加 `-Xms1G -Xmx1G` 避免小堆 GC 噪声。

**来源**：
- [Baeldung JMH 教程](https://www.baeldung.com/java-microbenchmark-harness)
- [JMH Blackhole bug](https://bugs.openjdk.org/browse/CODETOOLS-7901494)
- [TSE 2019: What's Wrong With My Benchmark Results](http://asgaard.ece.ualberta.ca/papers/Journal/TSE_2019_Costa_Whats_Wrong_With_My_Benchmark_Results_Studying_Bad_Practices_in_JMH_Benchmarks.pdf)

### 4.6 典型性能反模式

#### N+1 查询

循环内触发关联查询，1+N 次 round-trip。

**实测**：EF Core 601 次查询 ~3s → 1 次 JOIN ~50ms，60× 提升。

**解决方案**：
- JOIN fetch（HORM 已支持 `.fetch()`）
- 二次批量查询 + 内存组装（`findMany`）
- Hibernate `@Fetch(SUBSELECT)`
- BatchFetching `@BatchSize(size=50)`

#### SELECT * 反模式

加载 BLOB/大文本列浪费带宽与内存。

**实测**：EF Core 10000 products，tracking 150ms/25MB → AsNoTracking 80ms/15MB（2× 速度，40% 内存）。

**关键启示**：HORM 当前所有查询都默认 `SELECT *`，应支持投影 DTO/Record 生成（`XxxProjection`），编译期检查列引用。

**来源**：
- [EF Core N+1 Benchmark](https://codemajesty.tech/blog/ef-core-n-plus-one-problem-postgresql-benchmarks/)
- [EF Core 性能陷阱](https://andresleiva.com/blog/ef-core-performance-pitfalls/)
- [EF Core AsNoTracking/Compiled/Split Queries](https://codingdroplets.com/efcore-10-query-performance-asnotracking-compiled-split-queries)

---

## 五、优化建议

### 5.1 优化项 O1：Repository 缓存到 EntityMeta（P0）

**目标**：消除根因 R1，每次 find/save 不再 new JdbcRepository。

**实现**：在 `EntityMeta` 中缓存 Repository 实例，`Horm.repository(Class)` 改为查缓存。

**代码示例**：

```java
// 修改 EntityMeta.java，添加 repository 字段
public abstract class EntityMeta<T extends Model<T>> {
    // ... 现有字段
    private volatile Repository<T> cachedRepository;
    
    public Repository<T> repository(HormContext ctx) {
        Repository<T> repo = cachedRepository;
        if (repo == null || !repo.matchesContext(ctx)) {
            synchronized (this) {
                repo = cachedRepository;
                if (repo == null || !repo.matchesContext(ctx)) {
                    repo = new JdbcRepository<>(type(), ctx);
                    cachedRepository = repo;
                }
            }
        }
        return repo;
    }
}

// 修改 Horm.repository(Class)
public static <T extends Model<T>> Repository<T> repository(Class<T> entityType) {
    EntityMeta<T> meta = EntityMetaRegistry.lookup(entityType);
    return meta.repository(HormContext.current());
}
```

**注意**：`HormContext` 可能被替换（`HormContext.install(null)` 在测试 tearDown 中调用），所以 `matchesContext` 必须校验 context 引用。或者更简单的做法是缓存 Repository 但每次传入 ctx：

```java
// JdbcRepository 改为无状态
public final class JdbcRepository<T extends Model<T>> implements Repository<T> {
    // 移除 ctx 字段，每个方法接受 ctx 参数
    private final Class<T> entityType;
    private final EntityMeta<T> meta;
    private final Mapper<T> mapper;
    private final String dataSourceName;
    private final CachePolicy runtimeCachePolicy;
    // entityTypeRef 移到静态字段
    
    public T find(HormContext ctx, Object id) { ... }
}
```

**预期改进**：
- FindById 节省 500-1000 ns（构造开销）
- INSERT/UPDATE/DELETE 节省类似开销
- FindById 预期从 7.56 μs → 6.5-7.0 μs（10-15% 提升）

**权衡**：
- ✅ 实现简单，改动小
- ⚠️ 需处理 HormContext 切换场景（测试 setUp/tearDown）
- ⚠️ 多 HormContext 并存场景（罕见）需重新评估

---

### 5.2 优化项 O2：SQL 模板预生成与缓存（P0）

**目标**：消除根因 R2，SQL 在 EntityMeta 初始化时一次性生成并缓存。

**实现**：在 `EntityMeta` 中预生成所有 SQL 模板，JdbcRepository 直接读取。

**代码示例**：

```java
public abstract class EntityMeta<T extends Model<T>> {
    // SQL 模板字段
    private String sqlFindById;
    private String sqlFindAll;
    private String sqlCount;
    private String sqlExists;
    private String sqlInsert;
    private String sqlDeleteById;
    // update SQL 因 @Version 字段动态变化，仍按需构建或预生成两版本
    
    protected final void initSqlTemplates() {
        String table = qualifiedTable();
        FieldMeta<?> idField = idField();
        
        sqlFindById = "SELECT * FROM " + table + " WHERE " + idField.column() + " = ?";
        sqlFindAll = "SELECT * FROM " + table;
        sqlCount = "SELECT COUNT(*) FROM " + table;
        sqlExists = "SELECT 1 FROM " + table + " WHERE " + idField.column() + " = ?";
        sqlDeleteById = "DELETE FROM " + table + " WHERE " + idField.column() + " = ?";
        
        // INSERT 模板
        List<String> cols = fields().stream()
            .filter(FieldMeta::insertable)
            .filter(f -> !f.isId())
            .map(FieldMeta::column)
            .toList();
        sqlInsert = "INSERT INTO " + table + " (" 
            + String.join(", ", cols) + ") VALUES ("
            + cols.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
    }
}

// JdbcRepository.dbFind 直接读取
private T dbFind(Object id) {
    return JdbcOperations.query(ctx, dataSourceName, meta.sqlFindById(), List.of(id),
        rs -> rs.next() ? mapper.map(MetaSupport.toRow(rs, meta)) : null,
        "find " + entityType.getName() + " by id " + id);
}
```

**预期改进**：
- FindById 节省 SQL 拼接约 200-500 ns
- INSERT 节省 stream + String.join 约 500-1000 ns
- INSERT 预期从 12.71 μs → 11.5-12.0 μs（5-8% 提升）
- FindById 预期从 7.56 μs → 7.0-7.2 μs（5-7% 提升）

**权衡**：
- ✅ 实现简单，无 API 变化
- ⚠️ UPDATE SQL 因 `@Version` 字段、可更新字段集合动态变化，缓存收益小
- ⚠️ 需在 EntityMeta 初始化时（APT 生成或 Registry 加载时）调用 `initSqlTemplates`

---

### 5.3 优化项 O3：实现 JDBC Batch 插入 API（P0）

**目标**：消除根因 3（BatchInsert），实现 `Model.batchInsert(List<T>)` 使用 JDBC `addBatch/executeBatch`。

**实现**：在 `Repository` 接口新增 `batchInsert(List<T>)`，`JdbcRepository` 实现使用 `PreparedStatement.addBatch`。

**代码示例**：

```java
// Repository 接口新增
public interface Repository<T extends Model<T>> {
    // ... 现有方法
    List<T> batchInsert(List<T> entities, int batchSize);
}

// JdbcRepository 实现
@Override
public List<T> batchInsert(List<T> entities, int batchSize) {
    if (entities.isEmpty()) return List.of();
    
    String sql = meta.sqlInsert();  // 复用 O2 的 SQL 模板
    List<String> columns = meta.fields().stream()
        .filter(FieldMeta::insertable)
        .filter(f -> !f.isId())
        .map(FieldMeta::column)
        .toList();
    
    Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
    try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
        int counter = 0;
        for (T entity : entities) {
            Row row = mapper.toRow(entity);
            int i = 1;
            for (String col : columns) {
                SqlBinding.bindParam(ps, i++, row.get(col));
            }
            ps.addBatch();
            if (++counter % batchSize == 0) {
                ps.executeBatch();
            }
        }
        if (counter % batchSize != 0) {
            ps.executeBatch();
        }
        
        // 回填生成的主键
        try (ResultSet genKeys = ps.getGeneratedKeys()) {
            for (T entity : entities) {
                if (genKeys.next()) {
                    mapper.setId(entity, genKeys.getLong(1));
                }
            }
        }
    } catch (SQLException e) {
        throw new HormException("Failed to batch insert " + entityType.getName(), e);
    } finally {
        TransactionManager.releaseConnection(ctx, dataSourceName, conn);
    }
    
    // 缓存失效（批量）
    if (cacheEnabled()) {
        TransactionManager.afterCommit(dataSourceName, () -> {
            for (T entity : entities) {
                CacheKey key = idKey(mapper.getId(entity));
                ctx.cacheChain().put(key, entity, runtimeCachePolicy);
            }
        });
    }
    return entities;
}

// Model 类新增静态方法
public static <T extends Model<T>> List<T> batchInsert(Class<T> type, List<T> entities) {
    return Horm.repository(type).batchInsert(entities, 500);
}
```

**预期改进**：
- BatchInsert 100 预期从 1.219 ms → 0.55-0.65 ms（与 MyBatis 持平）
- BatchInsert 1000 预期从 11.685 ms → 5.5-6.5 ms（与 MyBatis 持平）
- 在 MySQL 生产环境配合 `rewriteBatchedStatements=true` 可再提升 5-10×

**权衡**：
- ✅ 性能提升最大（2-3×）
- ⚠️ IDENTITY 主键回填需要驱动支持 `RETURN_GENERATED_KEYS` 批量返回
- ⚠️ H2 对批量 generated keys 的支持有限，需测试验证
- ⚠️ 事务边界需明确：批量插入应在单事务内（`Horm.tx(() -> ...)`）

---

### 5.4 优化项 O4：APT 生成直接 ResultSet → Entity Mapper（P1）

**目标**：消除根因 R3，跳过 Row 中间层，APT 生成直接读取 ResultSet 的 Mapper。

**实现**：在 `MapperBuilder` 中新增 `mapFromResultSet(ResultSet)` 方法，绕过 Row。

**代码示例**：

```java
// MapperBuilder.buildMapFromResultSetMethod
private static MethodSpec buildMapFromResultSetMethod(EntityDescriptor d, ClassName entity) {
    ClassName rsCn = ClassName.get(java.sql.ResultSet.class);
    MethodSpec.Builder m = MethodSpec.methodBuilder("mapFromResultSet")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(entity)
        .addParameter(rsCn, "rs")
        .addException(SQLException.class);
    m.addStatement("$T u = new $T()", entity, entity);
    for (EntityDescriptor.FieldDescriptor f : d.fields()) {
        String rsGetter = TypeMapper.jdbcGetter(f);  // getLong / getString / getTimestamp 等
        String col = f.column();
        if ("getTimestamp".equals(rsGetter) && f.type().equals("Instant")) {
            m.addStatement("u.$L(rs.getTimestamp($S) != null ? rs.getTimestamp($S).toInstant() : null)",
                f.setterName(), col, col);
        } else {
            m.addStatement("u.$L(rs.$L($S))", f.setterName(), rsGetter, col);
        }
    }
    m.addStatement("return u");
    return m.build();
}

// Mapper 接口新增 default 方法
public interface Mapper<T> {
    // ... 现有方法
    default T mapFromResultSet(ResultSet rs) throws SQLException {
        // 默认实现回退到 Row 路径
        Row row = Row.create("");
        // ... 通用回退
        return map(row);
    }
}

// JdbcRepository.dbFind 改用直接路径
private T dbFind(Object id) {
    return JdbcOperations.query(ctx, dataSourceName, meta.sqlFindById(), List.of(id),
        rs -> rs.next() ? mapper.mapFromResultSet(rs) : null,  // ← 直接路径
        "find " + entityType.getName() + " by id " + id);
}
```

**预期改进**：
- FindById 节省 Row 创建 + 双重 LinkedHashMap 查找约 1-2 μs
- FindById 预期从 7.56 μs → 5.5-6.5 μs（15-25% 提升）
- Query limit=100 节省 100 × Row 开销约 100-200 μs（已是优势场景，进一步拉开差距）
- FindMany 100 IDs 节省 100 × Row 开销约 100-200 μs

**权衡**：
- ✅ 性能提升显著，特别对大结果集
- ✅ 保留 `map(Row)` 路径，兼容非 JDBC 数据源
- ⚠️ 需在 `TypeMapper` 中新增 `jdbcGetter(FieldDescriptor)` 映射表
- ⚠️ APT 生成代码量增加（每个实体多一个方法）
- ⚠️ enum/byte[] 等特殊类型需额外处理

---

### 5.5 优化项 O5：短路空 Cascade 扫描（P1）

**目标**：消除根因 R4，无关联实体跳过 cascade 扫描。

**实现**：在 EntityMeta 中预计算 `hasPersistCascade` / `hasDeleteCascade` 标志位，`Model.save()` 短路。

**代码示例**：

```java
// EntityMeta 新增预计算字段
public abstract class EntityMeta<T extends Model<T>> {
    private boolean hasPersistCascade;  // 是否存在 PERSIST/ALL cascade
    private boolean hasDeleteCascade;
    
    protected final void initCascadeFlags() {
        hasPersistCascade = relations().stream()
            .anyMatch(r -> hasCascadeType(r, CascadeType.PERSIST));
        hasDeleteCascade = relations().stream()
            .anyMatch(r -> hasCascadeType(r, CascadeType.REMOVE));
    }
    
    public boolean hasPersistCascade() { return hasPersistCascade; }
    public boolean hasDeleteCascade() { return hasDeleteCascade; }
}

// Model.save() 短路
public final void save() {
    EntityMeta<Model<?>> meta = EntityMetaRegistry.lookup(getClass());
    if (!meta.hasPersistCascade()) {
        // 短路：直接 save，跳过 HashSet 分配与 cascade 遍历
        repository().save(self());
        return;
    }
    Set<Object> visited = new HashSet<>();
    visited.add(this);
    cascadePersist(this, visited);
}

// Model.delete() 短路
public final void delete() {
    EntityMeta<Model<?>> meta = EntityMetaRegistry.lookup(getClass());
    if (!meta.hasDeleteCascade()) {
        repository().delete(self());
        return;
    }
    Set<Object> visited = new HashSet<>();
    visited.add(this);
    cascadeDelete(this, visited);
    repository().delete(self());
}
```

**预期改进**：
- UPDATE 节省 HashSet 分配 + relations 遍历约 300-800 ns
- DELETE 节省类似开销
- UPDATE 预期从 19.08 μs → 18.0-18.5 μs（3-5% 提升）
- DELETE 预期从 20.63 μs → 19.5-20.0 μs（3-5% 提升）

**权衡**：
- ✅ 实现简单，对无关联实体（如 BenchUser）100% 命中短路
- ✅ 不影响有关联实体的语义
- ⚠️ EntityMeta 初始化时需调用 `initCascadeFlags`

---

### 5.6 优化项 O6：集成 SingleFlightLoader 到 DefaultCacheChain（P1）

**目标**：消除根因 R5，防止缓存击穿。

**实现**：在 `DefaultCacheChain` 中持有 `SingleFlightLoader`，`get(key, type, loader, policy)` 在所有 tier miss 后通过 SingleFlightLoader 调用 loader。

**代码示例**：

```java
public final class DefaultCacheChain implements CacheChain {
    private final List<Cache> levels;
    private final List<CacheEventListener> listeners;
    private final SingleFlightLoader<CacheKey, Object> singleFlight;  // 新增
    
    public DefaultCacheChain(List<Cache> caches) {
        // ... 现有初始化
        this.singleFlight = new SingleFlightLoader<>(Duration.ofSeconds(30));
    }
    
    @Override
    public <K, V> Optional<V> get(K key, TypeReference<V> type,
                                  Supplier<V> loader, CachePolicy policy) {
        // ... 现有 tier 遍历逻辑（不变）
        
        // 所有 tier miss 后，通过 SingleFlight 调用 loader
        @SuppressWarnings("unchecked")
        V loaded = (V) singleFlight.load((CacheKey) key, () -> {
            V v = loader.get();
            // 缓存回填逻辑移到 SingleFlight 内部
            return v != null ? v : NullMarker.instance();
        });
        
        if (loaded == null || NullMarker.isNullMarker(loaded)) {
            if (policy.nullable() && loaded == NullMarker.instance()) {
                putAllTiers(key, NullMarker.instance(), policy);
            }
            return Optional.empty();
        }
        putAllTiers(key, loaded, policy);
        return Optional.of(loaded);
    }
}
```

**预期改进**：
- 高并发缓存击穿场景：N 个线程同时 miss → 1 个线程执行 loader，N-1 个等待
- 防止数据库连接池耗尽（典型场景：热点商品过期瞬间 1000 QPS）
- 单线程场景无性能变化（SingleFlight 无锁竞争）

**权衡**：
- ✅ 修复重大并发安全隐患
- ✅ SingleFlightLoader 类已实现，仅需集成
- ⚠️ 引入 30s 默认超时，需文档说明
- ⚠️ 单元测试需覆盖：并发同 key loader 只执行一次、不同 key 不互相阻塞、loader 失败传播

---

### 5.7 优化项 O7：CacheEvent 懒构造（P1）

**目标**：消除根因 R6，无监听器时不构造 CacheEvent 对象。

**实现**：在 `publishEvent` 调用前先检查 `listeners.isEmpty()`，使用工厂方法延迟构造。

**代码示例**：

```java
// 修改 DefaultCacheChain.get 中的事件发布
for (int i = 0; i < size; i++) {
    Cache tier = levels.get(i);
    Optional<V> cached = tier.get(key, type);
    if (cached.isPresent()) {
        V value = cached.get();
        if (!listeners.isEmpty()) {  // ← 提前检查
            publishEvent(CacheEvent.of(
                CacheEventType.HIT, tier.name(), tier.level(), key, value));
        }
        backFillAbove(key, value, i, null);
        if (NullMarker.isNullMarker(value)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
    if (!listeners.isEmpty()) {  // ← 提前检查
        publishEvent(CacheEvent.of(
            CacheEventType.MISS, tier.name(), tier.level(), key, null));
    }
}
```

**预期改进**：
- Cache Hit 热路径节省 50-100 ns（CacheEvent 对象分配 + GC 压力）
- Cache Hit 预期从 146 ns → 50-100 ns（30-65% 提升）

**权衡**：
- ✅ 改动极小，仅增加 `isEmpty()` 检查
- ✅ CopyOnWriteArrayList 的 isEmpty 是 volatile read，开销极低
- ⚠️ 监听器场景下性能无变化（仍会构造事件）

---

### 5.8 优化项 O8：QueryHash 缓存 MessageDigest（P1）

**目标**：消除根因 R7，避免每次 `MessageDigest.getInstance("SHA-256")`。

**实现**：使用 ThreadLocal 缓存 MessageDigest 实例。

**代码示例**：

```java
public final class QueryHash {
    private static final ThreadLocal<MessageDigest> DIGEST_TL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    });
    
    private static String sha256Truncated(String input, int truncateLength) {
        MessageDigest md = DIGEST_TL.get();
        md.reset();  // 关键：重置以复用
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        String hex = bytesToHex(digest);
        return hex.substring(0, Math.min(truncateLength, hex.length()));
    }
    // ... 其余不变
}
```

**预期改进**：
- 查询缓存路径节省 1-5 μs（JCA Provider 查找）
- 查询缓存命中场景性能提升 30-50%

**权衡**：
- ✅ 改动极小
- ✅ ThreadLocal 复用避免同步开销
- ⚠️ ThreadLocal 在线程池场景需注意清理（避免内存泄漏，但 MessageDigest 无外部资源）
- ⚠️ 替代方案：换用 xxHash / MurmurHash3（更快但需引入依赖）

---

### 5.9 优化项 O9：升级 JMH 基准配置（P1）

**目标**：提升基准测试结果可信度。

**实现**：修改所有基准类的 JMH 注解。

**代码示例**：

```java
// 修改前
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms256m", "-Xmx256m"})

// 修改后
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 3, jvmArgs = {"-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"})
```

**预期改进**：
- 误差缩小 50-80%（如 `directCall` 误差从 5.522 ns 降到 < 1 ns）
- 结果可重现性提升，便于回归检测

**权衡**：
- ✅ 结果可信度大幅提升
- ⚠️ 完整基准运行时间从 5-6 分钟增加到 30-45 分钟
- ⚠️ 建议提供两套配置：CI 快速冒烟（fork=1）+ 发布基准（fork=3）

---

### 5.10 优化项 O10：调查并修复事务代理性能（P2）

**目标**：消除根因 R9，使 APT 代理接近 directCall 性能。

**调查步骤**：
1. 检查 `TransactionProxyBuilder.java` 生成的代理类源码（编译后查看 `target/generated-sources/`）
2. 对比 `TransactionProxyBenchmark.aptProxyDirect` 与 `jdkDynamicProxy` 的实现差异
3. 确认 benchmark 是否每次迭代重新查找代理实例

**可能的修复方向**：
- 若 benchmark 设计问题：将代理实例缓存到 `@State` 字段
- 若代理实现问题：检查 `super` 调用路径是否有不必要的 ThreadLocal 查找
- 若事务上下文初始化开销：考虑延迟初始化（lazy transaction context）

**预期改进**：
- aptProxyDirect 从 3734 ns → 接近 directCall 的 7-20 ns（99%+ 提升）

**权衡**：
- ⚠️ 需先调查根因，再决定修复方案
- ⚠️ 事务代理是 M8.7 的核心特性，修复需谨慎

---

### 5.11 优化项 O11：流式查询 API（P2）

**目标**：支持大数据集流式处理，避免 OOM。

**实现**：新增 `Query.stream()` 返回 `Stream<T>`，底层使用 `ResultSet.TYPE_FORWARD_ONLY` + `setFetchSize`。

**代码示例**：

```java
// Query 接口新增
public interface Query<T extends Model<T>> {
    // ... 现有方法
    Stream<T> stream();
}

// QueryImpl 实现
@Override
public Stream<T> stream() {
    StringBuilder sql = new StringBuilder("SELECT * FROM ").append(MetaSupport.qualifiedTable(meta));
    List<Object> bindings = new ArrayList<>();
    appendWhere(sql, bindings);
    appendOrderBy(sql);
    if (limit != null) {
        sql.append(" LIMIT ").append(limit);
    }
    if (offset != null) {
        sql.append(" OFFSET ").append(offset);
    }
    
    Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
    try {
        PreparedStatement ps = conn.prepareStatement(
            sql.toString(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        bindParams(ps, bindings);
        // MySQL: ps.setFetchSize(Integer.MIN_VALUE); 启用流式
        // Postgres: ps.setFetchSize(1000);
        ps.setFetchSize(1000);
        ResultSet rs = ps.executeQuery();
        
        Spliterator<T> spliterator = new ResultSetSpliterator<>(rs, mapper, meta);
        return StreamSupport.stream(spliterator, false)
            .onClose(() -> {
                try { rs.close(); ps.close(); } catch (SQLException e) { /* ignore */ }
                TransactionManager.releaseConnection(ctx, dataSourceName, conn);
            });
    } catch (SQLException e) {
        throw new HormException("Failed to stream " + entityType.getName(), e);
    }
}
```

**预期改进**：
- 大数据集导出/ETL 场景内存占用从 O(N) 降到 O(fetchSize)
- 10000 行查询内存占用从 ~10 MB 降到 ~100 KB

**权衡**：
- ✅ 解决 OOM 风险
- ⚠️ 流式查询期间占用连接，需控制事务边界
- ⚠️ 不同数据库 fetchSize 配置不同（MySQL 需 `Integer.MIN_VALUE`，Postgres 用正常值）
- ⚠️ 需用户显式 try-with-resources 关闭 Stream

---

### 5.12 优化项 O12：投影 DTO 自动生成（P2）

**目标**：消除 `SELECT *` 反模式，APT 自动生成投影 record。

**实现**：在 APT 中为每个 `@Entity` 生成 `XxxProjection` record，Query DSL 支持 `.select(...)` 投影到 record。

**代码示例**：

```java
// 用户查询只需部分字段
List<UserListProjection> users = Model.query(User.class)
    .select(UserQueryMeta.ID, UserQueryMeta.NAME, UserQueryMeta.EMAIL)
    .listAs(UserListProjection.class);  // 直接映射到投影 record

// APT 生成的投影 record
public record UserListProjection(Long id, String name, String email) {}
```

**预期改进**：
- 仅查 3 字段（vs 10 字段实体）减少 70% 网络带宽与内存
- 大列表查询内存占用降低 50-70%

**权衡**：
- ✅ 性能与内存双重提升
- ⚠️ APT 复杂度增加，需为每个 `select` 组合生成 record（或运行时动态映射）
- ⚠️ API 设计需考虑向后兼容

---

## 六、实施优先级与路线图

### 6.1 优先级矩阵

| 优化项 | 根因 | 优先级 | 预期收益 | 实施难度 | M10 建议 |
|--------|------|--------|---------|---------|---------|
| O1 Repository 缓存 | R1 | **P0** | FindById -10-15% | 易 | ✅ |
| O2 SQL 模板预生成 | R2 | **P0** | FindById -5-7%, INSERT -5-8% | 易 | ✅ |
| O3 JDBC Batch 插入 | - | **P0** | BatchInsert -50-55% | 中 | ✅ |
| O4 直接 ResultSet Mapper | R3 | **P1** | FindById -15-25%, Query -30% | 中 | ✅ |
| O5 短路空 Cascade | R4 | **P1** | UPDATE/DELETE -3-5% | 易 | ✅ |
| O6 集成 SingleFlight | R5 | **P1** | 并发安全防护 | 中 | ✅ |
| O7 CacheEvent 懒构造 | R6 | **P1** | Cache Hit -30-65% | 易 | ✅ |
| O8 QueryHash 缓存 | R7 | **P1** | 查询缓存 -30-50% | 易 | ✅ |
| O9 升级 JMH 配置 | - | **P1** | 结果可信度 +80% | 易 | ✅ |
| O10 修复事务代理 | R9 | **P2** | aptProxy -99% | 需调查 | ⚠️ 调查 |
| O11 流式查询 | - | **P2** | 大数据集 OOM 防护 | 中 | M11 |
| O12 投影 DTO 生成 | - | **P2** | 内存 -50-70% | 难 | M11 |

### 6.2 M10 路线图建议

#### Phase 1：核心热路径优化（1-2 周）

- ✅ O1 Repository 缓存到 EntityMeta
- ✅ O2 SQL 模板预生成与缓存
- ✅ O5 短路空 Cascade 扫描
- ✅ O7 CacheEvent 懒构造
- ✅ O8 QueryHash 缓存 MessageDigest

**预期效果**：FindById 从 7.56 μs → 5.5-6.0 μs（20-25% 提升），Cache Hit 从 146 ns → 60-80 ns（45-60% 提升）。

#### Phase 2：批处理与缓存安全（1-2 周）

- ✅ O3 实现 JDBC Batch 插入 API
- ✅ O6 集成 SingleFlightLoader 到 DefaultCacheChain

**预期效果**：BatchInsert 从 11.69 ms → 5.5-6.5 ms（与 MyBatis 持平），缓存击穿风险消除。

#### Phase 3：基准与质量（1 周）

- ✅ O9 升级 JMH 基准配置（fork=3, warmup=5×2, measure=10×2, heap=1G）
- ✅ O10 调查事务代理性能异常（如可快速修复则纳入 M10，否则 M11）
- ✅ 重新运行全部基准，更新 13-benchmark-results.md

#### Phase 4（M11 及以后）

- O4 APT 生成直接 ResultSet → Entity Mapper（架构变更，需充分测试）
- O11 流式查询 API
- O12 投影 DTO 自动生成

### 6.3 预期总体效果

完成 M10 Phase 1-3 后的预期性能矩阵：

| 场景 | M9 基线 | M10 预期 | 提升幅度 | vs MyBatis |
|------|--------|---------|---------|-----------|
| FindById | 7.56 μs | 5.0-5.5 μs | 27-34% | 1.4-1.5× 慢（vs 2.1×） |
| INSERT | 12.71 μs | 10.5-11.5 μs | 10-17% | 持平或略慢 |
| UPDATE | 19.08 μs | 17.5-18.0 μs | 5-8% | 4.9-5.0× 慢（vs 5.3×） |
| DELETE | 20.63 μs | 19.0-19.5 μs | 5-8% | 2.6-2.7× 慢（vs 2.6×） |
| Query limit=10 | 12.95 μs | 10.0-11.0 μs | 15-23% | 1.3-1.4× 慢（vs 1.7×） |
| Query limit=100 | 33.23 μs | 28-30 μs | 10-16% | 1.9-2.0× 快（vs 1.7×） |
| BatchInsert 100 | 1.22 ms | 0.55-0.65 ms | 47-55% | 持平 |
| BatchInsert 1000 | 11.69 ms | 5.5-6.5 ms | 44-53% | 持平 |
| Cache Hit | 146 ns | 60-80 ns | 45-59% | — |

**关键目标**：
- FindById 与 MyBatis 差距从 2.1× 缩小到 1.4-1.5×
- BatchInsert 达到与 MyBatis 持平
- Cache Hit 性能提升 50%+
- 缓存击穿风险消除

### 6.4 长期方向（M11+）

1. **直接 ResultSet Mapper（O4）**：彻底消除 Row 中间层，FindById 有望达到 4-5 μs（接近 MyBatis）
2. **流式查询（O11）**：支持百万级数据集导出
3. **投影 DTO（O12）**：消除 `SELECT *`，内存与带宽双优化
4. **N+1 检测插件**：开发期拦截器统计同事务同表查询次数
5. **datasource 模块**：实现 HikariCP 集成 + `rewriteBatchedStatements` 默认开启
6. **StatelessSession 等价物**：`Horm.stateless()` API 绕过缓存与 cascade，专供 ETL

---

## 七、附录

### 7.1 基准测试原始数据

详见 [benchmark-results-2026-07-13.txt](./benchmark-results-2026-07-13.txt)。

### 7.2 关键源码索引

| 模块 | 文件 | 关键方法 |
|------|------|---------|
| core | [Horm.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/Horm.java) | `repository(Class)` L114 |
| core | [Model.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/Model.java) | `find` L159, `save` L94, `cascadePersist` L300 |
| core | [JdbcRepository.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/JdbcRepository.java) | `find` L72, `insert` L134, `update` L161 |
| core | [QueryImpl.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/query/QueryImpl.java) | `list` L216, `dbList` L230 |
| core | [JdbcOperations.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/JdbcOperations.java) | `query` L49, `insert` L85 |
| core | [MetaSupport.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/MetaSupport.java) | `toRow` L64 |
| core | [TransactionManager.java](../holo-horm-core/src/main/java/com/holo/framework/horm/core/TransactionManager.java) | `currentConnection` L92 |
| cache | [DefaultCacheChain.java](../holo-horm-cache/src/main/java/com/holo/framework/horm/cache/DefaultCacheChain.java) | `get` L141, `getAll` L264, `publishEvent` L544 |
| cache | [CaffeineCache.java](../holo-horm-cache/src/main/java/com/holo/framework/horm/cache/CaffeineCache.java) | `get` L241, `put` L253 |
| cache | [SingleFlightLoader.java](../holo-horm-cache/src/main/java/com/holo/framework/horm/cache/SingleFlightLoader.java) | `load` L106, `loadAsync` L156 |
| cache | [CacheKey.java](../holo-horm-cache/src/main/java/com/holo/framework/horm/cache/key/CacheKey.java) | 构造函数 L44 |
| cache | [QueryHash.java](../holo-horm-cache/src/main/java/com/holo/framework/horm/cache/key/QueryHash.java) | `sha256Truncated` L103 |
| meta | [MapperBuilder.java](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/processor/MapperBuilder.java) | `buildMapMethod` L65 |
| meta | [Row.java](../holo-horm-meta/src/main/java/com/holo/framework/horm/meta/Row.java) | `MapRow.getLong` L85 |
| benchmark | [FindByIdBenchmark.java](../holo-horm-benchmark/src/main/java/com/holo/framework/horm/benchmark/FindByIdBenchmark.java) | 全部 |
| benchmark | [CacheBenchmark.java](../holo-horm-benchmark/src/main/java/com/holo/framework/horm/benchmark/CacheBenchmark.java) | 全部 |

### 7.3 行业资料来源汇总

**MyBatis / Hibernate / jOOQ**：
- [MyBatis Executor 详解](https://blog.csdn.net/liuyinghui523/article/details/160795631)
- [Hibernate 批处理](https://docs.hibernate.org/core/3.3/reference/en-US/html/batch.html)
- [Baeldung: Hibernate StatelessSession](https://www.baeldung.com/hibernate-stateless-session)
- [jOOQ Codegen](https://blog.jooq.org/why-you-should-use-jooq-with-code-generation/)
- [Doma2 Annotation Processing](https://docs.domaframework.org/en/2.53.0/annotation-processing/)

**Caffeine / 缓存**：
- [Caffeine W-TinyLFU 详解](https://blog.csdn.net/2401_89214369/article/details/158073385)
- [W-TinyLFU 论文](https://arxiv.org/pdf/1512.00727)
- [缓存三防详解](https://chanjunren.github.io/docs/zettelkasten/backend/caching/cache_penetration_breakdown_avalanche)
- [Redis 雪崩防护](https://redis.io/blog/how-to-tame-the-thundering-herd-problem.md)

**JDBC / 连接池**：
- [MySQL rewriteBatchedStatements](https://blog.csdn.net/tolcf/article/details/52102849)
- [JPA vs JDBC 33× 差距](https://velog.io/@zbnerd/성능-튜닝-1만-건-데이터-삽입-JPA-vs-JDBC-성능-33배-차이의-비밀-15.2s-0.4s)
- [HikariCP 调优](https://oneuptime.com/blog/post/2026-01-25-tune-hikaricp-maximum-throughput-spring-boot/view)
- [High-Performance Java Persistence](http://samples.leanpub.com/high-performance-java-persistence-sample.pdf)

**JMH / 基准测试**：
- [Baeldung JMH 教程](https://www.baeldung.com/java-microbenchmark-harness)
- [JMH Blackhole bug](https://bugs.openjdk.org/browse/CODETOOLS-7901494)
- [TSE 2019: JMH Bad Practices](http://asgaard.ece.ualberta.ca/papers/Journal/TSE_2019_Costa_Whats_Wrong_With_My_Benchmark_Results_Studying_Bad_Practices_in_JMH_Benchmarks.pdf)

**性能反模式**：
- [EF Core N+1 Benchmark](https://codemajesty.tech/blog/ef-core-n-plus-one-problem-postgresql-benchmarks/)
- [EF Core 性能陷阱](https://andresleiva.com/blog/ef-core-performance-pitfalls/)
- [EF Core AsNoTracking/Compiled/Split Queries](https://codingdroplets.com/efcore-10-query-performance-asnotracking-compiled-split-queries)

---

**文档结束**。本文档基于 2026-07-13 的代码状态与基准数据，作为 M10 性能优化的设计与决策依据。建议在实施每个优化项后重新运行 JMH 基准验证效果，并持续更新 [13-benchmark-results.md](./13-benchmark-results.md)。
