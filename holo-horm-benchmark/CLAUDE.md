[根目录](../../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **holo-horm-benchmark**

# Holo :: HORM :: Benchmark (JMH 性能基准)

## 模块职责

JMH 性能基准测试模块，评估 HORM 框架的 CRUD/查询/缓存/批量操作性能，与手写 JDBC、MyBatis、Hibernate 等对比。

- **parent**: `holo-horm` (1.0.0-SNAPSHOT)
- **artifactId**: `holo-horm-benchmark`
- **packaging**: jar

## 已实现基准测试

| 基准类 | 场景 | 方法数 | 时间单位 |
|--------|------|--------|---------|
| `FindByIdBenchmark` | 按主键查 1 条（4 框架对比） | 4 | μs |
| `CrudBenchmark` | INSERT/UPDATE/DELETE（4 框架对比） | 12 | μs |
| `QueryBenchmark` | 条件查询+排序+分页（10/100 条结果） | 8 | μs |
| `CacheBenchmark` | L1 缓存命中/未命中/无缓存 | 3 | ns |
| `BatchInsertBenchmark` | 批量插入（100/1000 条） | 8 | ms |
| `FindManyBenchmark` | 批量查询 100 ID | 4 | μs |
| `TransactionProxyBenchmark` | APT 代理 vs LambdaMetafactory vs Method.invoke（M8.7） | 3 | ns |

入口类：`com.holo.framework.horm.benchmark.BenchmarkRunner`

## 对比框架

- **HORM**: Active Record + APT 生成 Mapper（零反射）
- **手写 JDBC**: 性能上限基线
- **MyBatis 3.5.16**: 半自动 ORM（XML 映射）
- **Hibernate 6.4.4.Final**: 全自动 ORM（JPA 风格，关闭 L2 Cache）

## 关键设计决策

1. **ByteBuddy 注入时序**：benchmark main 源码必须用 `Model.find(BenchUser.class, id)` 而非 `BenchUser.find(id)`，因为 Java 静态方法在编译期绑定。详见 [09-zero-reflection-optimization.md](../docs/09-zero-reflection-optimization.md)。
2. **共享 H2 schema**：所有框架共享同一 H2 内存库（`jdbc:h2:mem:bench;MODE=MySQL`）与 1000 条预填充数据，保证公平对比。
3. **Hibernate 关闭 L2 Cache**：`hibernate.cache.use_second_level_cache=false`，仅对比 ORM 框架本身开销。
4. **CachedBenchUser 复用 bench_users 表**：只读场景安全，避免额外建表。
5. **批量插入不批量化**：HORM 当前用循环 `repository.save(entity)`，未做 batch 优化（M10 计划），JDBC 用 `addBatch/executeBatch`。
6. **JMH 配置权衡**：`@Fork(1)` 节省时间；缓存基准用 `@Warmup(5,1) @Measurement(10,1)` 提高精度。

## 当前状态

- **POM**: 已建（含 hibernate-core + mybatis 依赖）
- **源代码**: 已完成（M9 Phase 1）
- **文档**: [docs/13-benchmark-results.md](../docs/13-benchmark-results.md)

## 相关文件清单

| 文件路径 | 说明 |
|---------|------|
| `pom.xml` | 模块 POM（含 hibernate/mybatis 依赖） |
| `src/main/java/com/holo/framework/horm/benchmark/BenchmarkRunner.java` | JMH Main 入口 |
| `src/main/java/com/holo/framework/horm/benchmark/FindByIdBenchmark.java` | 按主键查询基准 |
| `src/main/java/com/holo/framework/horm/benchmark/CrudBenchmark.java` | CRUD 基准 |
| `src/main/java/com/holo/framework/horm/benchmark/QueryBenchmark.java` | 条件查询基准 |
| `src/main/java/com/holo/framework/horm/benchmark/CacheBenchmark.java` | 缓存基准 |
| `src/main/java/com/holo/framework/horm/benchmark/BatchInsertBenchmark.java` | 批量插入基准 |
| `src/main/java/com/holo/framework/horm/benchmark/FindManyBenchmark.java` | 批量查询基准 |
| `src/main/java/com/holo/framework/horm/benchmark/entity/BenchUser.java` | HORM 实体 |
| `src/main/java/com/holo/framework/horm/benchmark/entity/CachedBenchUser.java` | 带 @Cached 的 HORM 实体 |
| `src/main/java/com/holo/framework/horm/benchmark/entity/MybatisBenchUser.java` | MyBatis POJO |
| `src/main/java/com/holo/framework/horm/benchmark/entity/HibernateBenchUser.java` | JPA 实体 |
| `src/main/java/com/holo/framework/horm/benchmark/setup/` | 各框架 Setup 类 |
| `src/main/resources/schema.sql` | bench_users 表 DDL |
| `src/main/resources/mybatis/` | MyBatis 配置与 Mapper XML |

## 运行基准

```bash
# 编译
mvn -pl holo-horm-benchmark -am compile -Pskip-enforcer

# 打包可执行 jar
mvn -pl holo-horm-benchmark -am package -Pskip-enforcer -DskipTests

# 运行全部基准
java -jar target/benchmarks.jar

# 运行指定基准
java -jar target/benchmarks.jar FindByIdBenchmark
```

## 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-07-06 | 初始文档生成 | 架构扫描 |
| 2026-07-13 | M9 Phase 1 完成 | 6 个基准类 + Setup + 实体 + MyBatis/Hibernate 对比 |
