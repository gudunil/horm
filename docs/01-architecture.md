# HORM 架构设计

> 本文档描述 HORM 框架的整体架构、模块划分、依赖关系与核心流程。详细模块设计见各专题文档。

---

## 一、设计目标

| 维度 | 目标 |
|------|------|
| **API 风格** | Active Record 风格，对标 Rails ActiveRecord，开箱即用 |
| **性能** | 运行时零反射，CRUD 操作延迟逼近手写 JDBC |
| **扩展性** | 数据源、缓存、验证、生命周期钩子全部 SPI 化 |
| **类型安全** | 查询 DSL 编译期类型检查，参考 jOOQ |
| **多数据源** | SQL / NoSQL / REST / 文件 / 自定义后端统一抽象 |
| **缓存** | 多级缓存链可组合，支持 LRU/TTL/写穿透等策略 |
| **生态** | 与 Spring Boot / Quarkus / Helidon 等主流框架无缝集成 |
| **AOT 友好** | 无运行时字节码生成，兼容 GraalVM Native Image |

---

## 二、整体架构

### 2.1 分层架构

```
┌────────────────────────────────────────────────────────────────────────────┐
│                              业务应用层                                    │
│       （继承 Model 基类，使用 Active Record API 或 Repository API）          │
└────────────────────────────────────┬───────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼───────────────────────────────────────┐
│                          HORM API 层 (holo-horm-core)                       │
│  ┌──────────────┬──────────────┬──────────────┬──────────────┬──────────┐ │
│  │ Active Record│ Query DSL    │ Repository   │ Transaction  │ Validation│ │
│  │ Model 基类   │ 类型安全查询  │ Repository   │ 事务管理      │ 数据校验  │ │
│  └──────────────┴──────────────┴──────────────┴──────────────┴──────────┘ │
│  ┌──────────────┬──────────────┬──────────────┐                            │
│  │ Relation     │ Lifecycle    │ Meta API     │                            │
│  │ 关联关系      │ 生命周期钩子 │ 元数据访问    │                            │
│  └──────────────┴──────────────┴──────────────┘                            │
└────────────────────────────────────┬────────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼───────────────────────────────────────┐
│                 元数据与代码生成层 (holo-horm-meta)                          │
│   ┌─────────────────────┬─────────────────────┬──────────────────────┐    │
│   │ Annotations         │ APT Processor       │ Generated Classes    │    │
│   │ @Entity/@Table/@Id  │ EntityProcessor     │ EntityMeta           │    │
│   │ @Column/@BelongsTo  │ MapperGenerator     │ Mapper               │    │
│   │ @HasMany/@HasOne    │ QueryMetaGenerator  │ FieldAccessor        │    │
│   └─────────────────────┴─────────────────────┴──────────────────────┘    │
└────────────────────────────────────┬────────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼───────────────────────────────────────┐
│                     数据源适配层 (holo-horm-datasource)                      │
│  ┌──────────────────────────────────────────────────────────────────┐     │
│  │                 DataSource SPI (核心抽象)                         │     │
│  │   openSession() / capabilities() / health() / close()            │     │
│  └──────────────────────────────────────────────────────────────────┘     │
│  ┌────────────┬────────────┬────────────┬────────────┬──────────────┐    │
│  │ SQL        │ NoSQL      │ REST       │ File       │ Custom SPI   │    │
│  │ MySQL/PG   │ MongoDB    │ OkHttp     │ CSV/JSON   │ 用户实现     │    │
│  │ Oracle     │ Redis      │ HTTP/2     │ Excel      │              │    │
│  │ SQLite     │ ES (计划)  │            │            │              │    │
│  └────────────┴────────────┴────────────┴────────────┴──────────────┘    │
└────────────────────────────────────┬────────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼───────────────────────────────────────┐
│                       缓存链层 (holo-horm-cache)                             │
│  ┌──────────────────────────────────────────────────────────────────┐     │
│  │              CacheChain (责任链组合)                              │     │
│  │   L1 (Caffeine) → L2 (Redis) → L3 (Remote)                       │     │
│  └──────────────────────────────────────────────────────────────────┘     │
│  ┌────────────┬────────────┬────────────┬────────────┬──────────────┐    │
│  │ Policy     │ Loader     │ Serializer │ Event      │ Monitor      │    │
│  │ LRU/TTL    │ 加载策略    │ 序列化      │ 事件广播    │ Micrometer   │    │
│  └────────────┴────────────┴────────────┴────────────┴──────────────┘    │
└────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 模块依赖关系

```
                    ┌────────────────────┐
                    │  holo-parent-pom   │  (打包规范、插件版本)
                    └─────────┬──────────┘
                              │
                    ┌─────────▼──────────┐
                    │holo-dependency-bom │  (第三方依赖版本)
                    └─────────┬──────────┘
                              │
                    ┌─────────▼──────────┐
                    │   holo-horm        │  (聚合 pom)
                    │   (aggregator)     │
                    └─────────┬──────────┘
                              │
            ┌─────────────────┼─────────────────────┬──────────────┐
            │                 │                     │              │
   ┌────────▼─────┐   ┌──────▼──────┐      ┌──────▼──────┐  ┌────▼────────┐
   │ holo-horm-bom│   │ holo-horm-  │      │ holo-horm-  │  │ holo-horm-  │
   │ (版本清单)   │   │ meta        │      │ codegen     │  │ migration   │
   └──────────────┘   │ (APT)       │      │ (代码生成)  │  │ (DB 迁移)   │
                      └──────┬──────┘      └──────┬──────┘  └──────┬──────┘
                             │                    │                │
                             │             ┌──────▼──────┐         │
                             │             │ holo-horm-  │         │
                             │             │ core        │◄────────┤
                             │             │ (API)       │         │
                             │             └──────┬──────┘         │
                             │                    │                │
                      ┌──────┴────────────────────┴────────────────┴──┐
                      │                                                  │
              ┌───────▼────────┐                              ┌─────────▼───────┐
              │ holo-horm-     │                              │ holo-horm-      │
              │ datasource     │◄─────────────────────────────┤ cache           │
              │ (数据源 SPI)   │                              │ (缓存链)        │
              └───────┬────────┘                              └─────────┬───────┘
                      │                                                 │
                      └─────────────────────┬──────────────────────────┘
                                            │
                                  ┌─────────▼─────────┐
                                  │ holo-horm-spring- │
                                  │ boot-starter      │
                                  └─────────┬─────────┘
                                            │
                          ┌─────────────────┼─────────────────┐
                          │                 │                 │
                  ┌───────▼─────┐   ┌───────▼─────┐   ┌───────▼─────┐
                  │ holo-horm-  │   │ holo-horm-  │   │ holo-horm-  │
                  │ examples    │   │ benchmark   │   │ (业务方)    │
                  └─────────────┘   └─────────────┘   └─────────────┘
```

### 2.3 模块清单

| 模块 | 类型 | 职责 |
|------|------|------|
| `holo-horm-bom` | pom | HORM 内部模块版本清单，业务方 import |
| `holo-horm-meta` | jar | 注解定义 + APT 处理器，编译期生成元数据 |
| `holo-horm-core` | jar | 核心 API：Model、Query、Repository、Transaction、Relation、Validation |
| `holo-horm-datasource` | jar | 数据源 SPI 与参考实现（SQL/NoSQL/REST/File） |
| `holo-horm-cache` | jar | 缓存链系统 |
| `holo-horm-codegen` | jar | 代码生成 CLI（DDL→Entity、Entity→DDL、Migration） |
| `holo-horm-migration` | jar | 数据库迁移工具 |
| `holo-horm-spring-boot-starter` | jar | Spring Boot 自动装配 |
| `holo-horm-examples` | jar | 示例代码 |
| `holo-horm-benchmark` | jar | JMH 性能基准 |

---

## 三、核心抽象

### 3.1 Model 基类（Active Record 入口）

```java
public abstract class Model<T extends Model<T>> {
    protected transient HormContext context;
    protected transient EntityMeta<T> meta;

    public T save() { return context.repository(meta).save(self()); }
    public T update() { return context.repository(meta).update(self()); }
    public boolean delete() { return context.repository(meta).delete(self()); }
    public boolean persist() { /* save or update by id */ }
    public T reload() { /* fetch from datasource */ }

    public static <T extends Model<T>> Query<T> where(Class<T> type, Condition... conditions) {
        return Horm.context().repository(type).where(conditions);
    }
    public static <T extends Model<T>> T find(Class<T> type, Object id) {
        return Horm.context().repository(type).findById(id);
    }
    public static <T extends Model<T>> List<T> all(Class<T> type) { ... }
}
```

### 3.2 Query 模型（数据源无关的查询意图）

```java
public interface Query<T> {
    Query<T> select(String... fields);
    Query<T> where(Condition condition);
    Query<T> join(Relation relation, Condition on);
    Query<T> orderBy(Order... orders);
    Query<T> limit(int limit);
    Query<T> offset(int offset);
    Query<T> forUpdate();

    List<T> all();
    Optional<T> one();
    long count();
    boolean exists();
    int update(Map<String, Object> values);
    int delete();

    // 编译期生成的元模型方法（类型安全 DSL）
    // UserMeta.EMAIL.eq("a@b.com").and(UserMeta.STATUS.eq("active"))
}
```

### 3.3 DataSource SPI

```java
public interface DataSource {
    String name();
    Session openSession();
    Capabilities capabilities();
    HealthStatus health();
    void close();
}

public interface Session extends AutoCloseable {
    <T> List<T> execute(Query<T> query);
    <T> int executeUpdate(Query<T> query);
    <T> int executeDelete(Query<T> query);
    BatchResult executeBatch(List<Query<?>> queries);

    void beginTransaction(TransactionDefinition def);
    void commit();
    void rollback();

    @Override void close();
}

public interface QueryTranslator {
    TranslatedQuery translate(Query<?> query);
}

public interface Capabilities {
    boolean supportsTransaction();
    boolean supportsBatch();
    boolean supportsPagination();
    boolean supportsJoin();
    boolean supportsSort();
    boolean supportsStreaming();
    // ...
}
```

### 3.4 CacheChain SPI

```java
public interface CacheChain extends AutoCloseable {
    <K, V> Optional<V> get(K key, TypeReference<V> type);
    <K, V> void put(K key, V value, CachePolicy policy);
    <K> void invalidate(K key);
    void invalidateAll();
    CacheStats stats();
}

public interface Cache {
    String name();
    <K, V> Optional<V> get(K key, TypeReference<V> type);
    <K, V> void put(K key, V value, CachePolicy policy);
    <K> void invalidate(K key);
    void invalidateAll();
}
```

### 3.5 Mapper（零反射的对象映射）

```java
public interface Mapper<T> {
    T map(Row row);
    Row toRow(T entity);
    Object getId(T entity);
    void setId(T entity, Object id);
}
```

由 APT 编译期生成实现类，运行时直接调用，无反射。

---

## 四、核心流程

### 4.1 Active Record save() 流程

```
User u = new User();
u.setEmail("a@b.com");
u.save();
```

```
┌──────────────────────────────────────────────────────────────────┐
│ 1. u.save()                                                      │
│       │                                                          │
│       ▼                                                          │
│ 2. Repository.save(u)                                            │
│       │                                                          │
│       ├── Validation 阶段：调用 Validator 校验实体                │
│       │       │                                                  │
│       │       └── 校验失败 → 抛出 ValidationException             │
│       │                                                          │
│       ├── Before Save 钩子：触发 @BeforeSave 回调                │
│       │                                                          │
│       ├── 缓存查询（如果是 update）：查询缓存链获取原值            │
│       │                                                          │
│       ├── Mapper.toRow(u)：编译期生成的 Mapper 转换为 Row         │
│       │                                                          │
│       ├── Transaction 上下文：加入当前事务（如有）                │
│       │                                                          │
│       ├── DataSource.openSession().executeUpdate(Query)          │
│       │       │                                                  │
│       │       ├── SQL DataSource：QueryTranslator 翻译为 SQL     │
│       │       │       │                                          │
│       │       │       └── JDBC PreparedStatement 执行            │
│       │       │                                                  │
│       │       ├── Mongo DataSource：翻译为 insert/update 文档    │
│       │       │                                                  │
│       │       └── REST DataSource：翻译为 POST/PUT 请求          │
│       │                                                          │
│       ├── 生成主键回填（如自增 ID）：Mapper.setId(u, generatedId) │
│       │                                                          │
│       ├── 缓存更新：put(u.getId(), u) 到 CacheChain              │
│       │                                                          │
│       └── After Save 钩子：触发 @AfterSave 回调                  │
│                                                                  │
│ 3. 返回 u（含已生成的 id）                                       │
└──────────────────────────────────────────────────────────────────┘
```

### 4.2 Query 查询流程（含缓存链）

```
User.where(EMAIL.eq("a@b.com")).one()
```

```
1. 构建类型安全 Query：UserMeta.EMAIL.eq("a@b.com")
       │
       ▼
2. Repository.where(...).one()
       │
       ├── 构建缓存键：User:email:a@b.com
       │
       ├── CacheChain.get(key)
       │       │
       │       ├── L1 (Caffeine) 命中 → 直接返回
       │       │
       │       ├── L1 未命中，L2 (Redis) 命中 → 回填 L1，返回
       │       │
       │       └── L2 未命中，进入数据源查询
       │
       ├── DataSource.openSession().execute(Query)
       │       │
       │       ├── QueryTranslator.translate(Query) → TranslatedQuery
       │       │
       │       └── 执行原生命令（SQL / Mongo Query / HTTP 请求）
       │
       ├── ResultHandler 处理结果：Mapper.map(Row) → User
       │
       ├── CacheChain.put(key, user, policy=TTL_30m)
       │       │
       │       ├── L2 写入 Redis
       │       └── L1 写入 Caffeine
       │
       └── 返回 Optional<User>
```

### 4.3 多数据源切换流程

```
@UseDataSource("mongo")
List<User> users = User.where(STATUS.eq("active")).all();
```

```
1. @UseDataSource AOP 拦截
       │
       ▼
2. DataSourceContext.push("mongo")
       │
       ▼
3. HormContext.currentDataSource()
       │
       ▼
4. DataSourceRegistry.get("mongo") → MongoDataSource
       │
       ▼
5. 后续 CRUD 操作路由到 MongoDataSource
       │
       ▼
6. 方法返回时 DataSourceContext.pop()
```

---

## 五、关键设计决策

### 5.1 为什么选择 APT 而非字节码生成？

- **AOT/原生镜像兼容**：APT 编译期生成代码，无运行时类生成，GraalVM Native Image 友好
- **类型安全**：生成的元模型类是普通 Java 类，编译器/IDE 完全感知
- **调试友好**：生成代码可读、可断点、可单步
- **依赖轻量**：仅依赖 JDK + JavaPoet，无 ASM/ByteBuddy 运行时依赖

### 5.2 为什么 Query 模型不直接生成 SQL？

- **数据源无关**：Query 描述意图，由各数据源的 `QueryTranslator` 翻译为原生命令
- **可优化**：通用 Query 模型允许做查询优化（如条件合并、N+1 检测）
- **可组合**：Query 是值对象，可链式组合、可缓存、可序列化

### 5.3 为什么缓存链使用责任链而非树形结构？

- **简单性**：链式结构线性易理解，调试容易
- **顺序明确**：L1 → L2 → L3 顺序天然清晰
- **可插拔**：任意位置插入/移除 Cache 节点
- **降级友好**：某层 Cache 不可用时自动跳过

### 5.4 为什么不直接复用 Spring Cache？

- **多级组合需求**：Spring Cache 单层抽象，多级需手写
- **数据源感知**：HORM 缓存需要感知实体元数据（如分区、关联）
- **强一致性可选**：HORM 缓存支持写穿透 + 失效广播，远超 Spring Cache 表达力
- **可独立使用**：HORM 不强依赖 Spring

---

## 六、与生态的集成

### 6.1 Spring Boot 集成

- `HormAutoConfiguration`：基于 `@AutoConfiguration` 自动装配 `HormContext`、`DataSourceRegistry`、`CacheChain`
- `HormProperties`：`holo.horm.*` 配置项
- `HormTransactionManager`：实现 `PlatformTransactionManager`，与 `@Transactional` 兼容
- `HormMetricsAutoConfiguration`：自动注册 Micrometer 指标
- `HormHealthIndicator`：暴露 Actuator 健康检查

### 6.2 Quarkus / Helidon 集成（计划中）

通过扩展点（`HormContextBuilder`）支持非 Spring 环境，未来可加 Quarkus Extension。

### 6.3 数据库迁移工具集成

- 内置 `holo-horm-migration`，参考 Flyway/Rails Migration
- 启动时自动执行迁移（可关闭）
- 与 `holo-horm-codegen` 联动：迁移脚本可生成对应的 Entity 类

---

## 七、可观测性

### 7.1 指标

通过 Micrometer 暴露：
- `horm.query.count{entity,operation}` 查询计数
- `horm.query.duration{entity,operation}` 查询延迟
- `horm.cache.hits{level,entity}` 缓存命中
- `horm.cache.misses{level,entity}` 缓存未命中
- `horm.cache.evictions{level,entity}` 缓存淘汰
- `horm.datasource.connections{datasource}` 数据源连接数

### 7.2 日志

- 默认 SLF4J，可配置日志级别（DEBUG 打印 SQL，TRACE 打印参数）
- 集成 P6Spy（可选）提供 SQL 拦截与统计

### 7.3 分布式追踪

通过 Micrometer Tracing 自动注入 traceId 到 Query 与缓存键，便于跨数据源追踪。

---

## 八、安全考量

### 8.1 SQL 注入防护

- 所有 SQL 参数化，杜绝字符串拼接
- Query 模型不允许直接嵌入原生 SQL 片段
- 转义回退：原生 SQL 路径强制使用 PreparedStatement 参数

### 8.2 敏感数据保护

- `@Sensitive` 注解：标记字段在日志/缓存中自动脱敏
- 集成 Jasypt：配置文件加密
- 缓存键不含敏感字段值，改用 hash

### 8.3 多租户隔离

- `TenantContext` 通过 ThreadLocal 或 Reactor Context 传递
- 自动注入租户条件到所有 Query
- 缓存键自动包含租户 ID

详见 [07-performance-security.md](./07-performance-security.md)。

---

## 九、下一步

| 文档 | 内容 | 状态 |
|------|------|------|
| [02-zero-reflection.md](./02-zero-reflection.md) | 零反射实现细节 | 已实现（M1-M3） |
| [03-multi-datasource.md](./03-multi-datasource.md) | 多数据源适配 | 规划中（M5） |
| [04-cache-chain.md](./04-cache-chain.md) | 缓存链系统 | 规划中（M6） |
| [05-active-record.md](./05-active-record.md) | Active Record API | 部分实现（M1-M3） |
| [06-extension-features.md](./06-extension-features.md) | 扩展特性 | 规划中 |
| [07-performance-security.md](./07-performance-security.md) | 性能与安全 | 持续更新 |
