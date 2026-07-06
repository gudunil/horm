[根目录](../../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **holo-horm-datasource**

# Holo :: HORM :: Datasource (数据源 SPI)

## 模块职责

数据源 SPI 与参考实现，定义统一的数据源抽象接口，支持 SQL/NoSQL/REST/File 等多种数据源扩展。当前为规划阶段，仅 POM 已建。

- **parent**: `holo-horm` (1.0.0-SNAPSHOT)
- **artifactId**: `holo-horm-datasource`
- **packaging**: jar

## 规划接口

### DataSource SPI

```java
public interface DataSource {
    String name();
    Session openSession();
    Capabilities capabilities();
    HealthStatus health();
    void close();
}
```

### Session SPI

```java
public interface Session extends AutoCloseable {
    <T> List<T> execute(Query<T> query);
    <T> int executeUpdate(Query<T> query);
    BatchResult executeBatch(List<Query<?>> queries);
    void beginTransaction(TransactionDefinition def);
    void commit();
    void rollback();
}
```

### 规划数据源实现

| 数据源 | 状态 | 说明 |
|--------|------|------|
| SQL (MySQL/PG/Oracle) | 规划中 | JDBC 驱动，核心实现 |
| NoSQL (MongoDB) | 规划中 | MongoDB 驱动 |
| REST (HTTP) | 规划中 | OkHttp 包装 |
| File (CSV/JSON) | 规划中 | 文件读取 |
| Elasticsearch | 规划中 | ES 查询 DSL |

## 当前状态

- **POM**: 已建，依赖定义待补充
- **源代码**: 无
- **目标**: 容纳 `DataSource` / `Session` / `QueryTranslator` / `Capabilities` SPI 接口及参考实现

## 相关文件清单

| 文件路径 | 说明 |
|---------|------|
| `holo-horm/holo-horm-datasource/pom.xml` | 模块 POM（仅骨架） |

## 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-07-06 | 初始文档生成 | 架构扫描 |