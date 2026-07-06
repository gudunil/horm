[根目录](../../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **holo-horm-benchmark**

# Holo :: HORM :: Benchmark (JMH 性能基准)

## 模块职责

JMH 性能基准测试模块，用于评估 HORM 框架的 CRUD 性能、查询 DSL 性能、缓存链吞吐等关键指标，与手写 JDBC、MyBatis、Hibernate 等对比。当前为规划阶段，仅 POM 已建。

- **parent**: `holo-horm` (1.0.0-SNAPSHOT)
- **artifactId**: `holo-horm-benchmark`
- **packaging**: jar

## 规划基准测试

| 基准 | 说明 |
|------|------|
| CRUD 吞吐 | 单表插入/查询/更新/删除 |
| 查询 DSL | 条件查询/排序/分页/关联 |
| 缓存链 | 各级缓存命中率/延迟 |
| 零反射对比 | HORM vs MyBatis vs Hibernate |
| 批量操作 | 批量插入/批量查询 |

## 当前状态

- **POM**: 已建
- **源代码**: 无

## 相关文件清单

| 文件路径 | 说明 |
|---------|------|
| `holo-horm/holo-horm-benchmark/pom.xml` | 模块 POM |

## 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-07-06 | 初始文档生成 | 架构扫描 |