# HORM 多数据源适配设计

> 本文档详述 HORM 数据源 SPI 设计、参考实现（SQL/NoSQL/REST/文件）、自定义数据源扩展规范、能力声明机制与多数据源切换流程。

---

## 一、设计目标

1. **统一抽象**：所有数据源通过 `DataSource` SPI 暴露，业务代码无需感知数据源类型
2. **能力声明**：数据源通过 `Capabilities` 声明支持的功能（事务、批量、分页等），上层自动降级
3. **可扩展**：用户实现 SPI 三件套即可接入任意后端
4. **多数据源共存**：通过 `DataSourceRegistry` 管理多个数据源，支持按注解/上下文切换
5. **零侵入**：业务代码不感知数据源切换

---

## 二、核心 SPI

### 2.1 `DataSource` 接口

```java
public interface DataSource extends AutoCloseable {

    /** 数据源唯一标识，注册到 Registry 时使用 */
    String name();

    /** 打开一个会话（线程不安全，每次操作或每个事务一个 Session） */
    Session openSession();

    /** 声明数据源能力，决定上层行为（是否支持事务、批量等） */
    Capabilities capabilities();

    /** 健康检查，供 Actuator / 监控调用 */
    HealthStatus health();

    /** 关闭数据源，释放资源 */
    @Override
    void close();
}
```

### 2.2 `Session` 接口

```java
public interface Session extends AutoCloseable {

    /** 执行查询，返回实体列表 */
    <T> List<T> execute(Query<T> query);

    /** 执行更新（UPDATE），返回受影响行数 */
    <T> int executeUpdate(Query<T> query);

    /** 执行删除（DELETE），返回受影响行数 */
    <T> int executeDelete(Query<T> query);

    /** 执行插入（INSERT），返回生成的主键（如有） */
    <T> Object executeInsert(Query<T> query);

    /** 批量执行 */
    BatchResult executeBatch(List<Query<?>> queries);

    /** 流式执行，适合大数据量 */
    <T> Stream<T> stream(Query<T> query);

    /** 事务管理 */
    void beginTransaction(TransactionDefinition definition);
    void commit();
    void rollback();
    boolean isInTransaction();

    @Override
    void close();
}
```

### 2.3 `QueryTranslator` 接口

```java
public interface QueryTranslator {

    /**
     * 将通用 Query 翻译为数据源原生命令
     * @return TranslatedQuery 包含原生命令与参数
     */
    TranslatedQuery translate(Query<?> query);

    /** 数据源能力，用于 QueryTranslator 注册时声明适配能力 */
    Capabilities capabilities();
}

public final class TranslatedQuery {
    private final String command;        // SQL / MongoDB JSON / HTTP URL
    private final List<Object> arguments; // 参数
    private final Map<String, Object> metadata; // 额外元数据（HTTP headers 等）
    // ...
}
```

### 2.4 `ResultHandler` 接口

```java
public interface ResultHandler<T> {

    /**
     * 将数据源原生结果转换为实体列表
     * @param nativeResult 原生结果（ResultSet / Document / HttpResponse 等）
     * @param mapper 编译期生成的 Mapper，零反射映射
     */
    List<T> handle(Object nativeResult, Mapper<T> mapper);
}
```

### 2.5 `Capabilities` 接口

```java
public final class Capabilities {
    private final boolean transaction;
    private final boolean batch;
    private final boolean pagination;
    private final boolean join;
    private final boolean sort;
    private final boolean streaming;
    private final boolean generatedId;
    private final boolean complexCondition; // OR、嵌套条件
    // ...

    public static CapabilitiesBuilder builder() { return new CapabilitiesBuilder(); }

    /** 不支持任何高级特性的最小能力集 */
    public static Capabilities minimal() { return builder().build(); }

    /** 完整 SQL 能力集 */
    public static Capabilities fullSql() { return builder()
        .transaction(true).batch(true).pagination(true).join(true)
        .sort(true).streaming(true).generatedId(true).complexCondition(true)
        .build(); }
}
```

### 2.6 `DataSourceRegistry` 接口

```java
public interface DataSourceRegistry {

    /** 注册数据源 */
    void register(DataSource dataSource);

    /** 默认数据源 */
    DataSource defaultDataSource();

    /** 按名称获取 */
    DataSource get(String name);

    /** 当前上下文中的数据源（受 @UseDataSource / ThreadLocal 影响） */
    DataSource current();

    /** 所有已注册的数据源名称 */
    Set<String> names();
}
```

---

## 三、参考实现

### 3.1 SQL 数据源

#### 3.1.1 架构

```
SqlDataSource
    │
    ├── ConnectionPool (HikariCP / Druid)
    │
    ├── SqlSession
    │     │
    │     ├── SqlQueryTranslator
    │     │     │
    │     │     ├── 将 Query 翻译为参数化 SQL
    │     │     ├── Dialect 处理方言差异（分页、类型、保留字）
    │     │     └── 生成 PreparedStatement 参数列表
    │     │
    │     └── JdbcResultHandler
    │           └── ResultSet → Row → Mapper.map(Row) → 实体
    │
    └── Dialect
          ├── MysqlDialect
          ├── PostgresDialect
          ├── OracleDialect
          ├── SqliteDialect
          └── H2Dialect
```

#### 3.1.2 方言抽象

```java
public interface Dialect {
    String name();

    /** 分页 SQL 包装 */
    String paginate(String sql, int offset, int limit);

    /** 主键生成策略 */
    String identityColumn();

    /** 类型映射（Java 类型 ↔ SQL 类型） */
    int sqlType(Class<?> javaType);

    /** 保留字检查 */
    boolean isReserved(String identifier);

    /** UPSERT 支持（INSERT ON CONFLICT / INSERT ON DUPLICATE KEY） */
    Optional<String> upsert(Query<?> query);

    /** 批量 INSERT 支持形式 */
    BatchInsertSyntax batchInsertSyntax();
}
```

#### 3.1.3 SQL 翻译示例

通用 Query：
```java
User.where(UserQueryMeta.EMAIL.eq("a@b.com"))
    .orderBy(UserQueryMeta.ID.desc())
    .limit(10)
    .offset(20)
    .all();
```

翻译为 MySQL SQL：
```sql
SELECT id, email, created_at
FROM users
WHERE email = ?
ORDER BY id DESC
LIMIT 10 OFFSET 20
```

翻译为 Oracle SQL（12c+）：
```sql
SELECT id, email, created_at
FROM users
WHERE email = :1
ORDER BY id DESC
OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY
```

### 3.2 MongoDB 数据源

#### 3.2.1 实现

```java
public class MongoDataSource implements DataSource {
    private final MongoClient client;
    private final String database;
    private final MongoQueryTranslator translator;

    @Override
    public Session openSession() {
        return new MongoSession(client.getDatabase(database), translator);
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.builder()
            .transaction(true)  // MongoDB 4.0+ 多文档事务
            .batch(true)
            .pagination(true)
            .join(false)        // MongoDB 无 join，关联通过聚合管道实现
            .sort(true)
            .streaming(true)
            .generatedId(false) // 客户端生成 ObjectId
            .complexCondition(true)
            .build();
    }
}
```

#### 3.2.2 查询翻译

通用 Query：
```java
User.where(UserQueryMeta.EMAIL.eq("a@b.com"))
    .orderBy(UserQueryMeta.ID.desc())
    .limit(10)
    .all();
```

翻译为 MongoDB 命令：
```javascript
db.users.find({ email: "a@b.com" })
         .sort({ _id: -1 })
         .limit(10)
```

### 3.3 Redis 数据源

Redis 作为 KV 存储，能力受限：

```java
public class RedisDataSource implements DataSource {
    @Override
    public Capabilities capabilities() {
        return Capabilities.builder()
            .transaction(true)  // MULTI/EXEC
            .batch(true)        // Pipeline
            .pagination(false)
            .join(false)
            .sort(true)         // SORT 命令
            .streaming(false)
            .generatedId(true)  // INCR
            .complexCondition(false) // 仅支持 key 精确匹配
            .build();
    }
}
```

实体在 Redis 中的存储形式：
- **Hash**：`HSET user:123 id 123 email "a@b.com" created_at 1234567890`
- **JSON**：`SET user:123 '{"id":123,"email":"a@b.com"}'`
- **索引**：`SADD user:email:index a@b.com 123`（支持二级索引查询）

### 3.4 REST 数据源

#### 3.4.1 实现

```java
public class RestDataSource implements DataSource {
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final RestProtocol protocol; // OData / JSON:API / Custom

    @Override
    public Capabilities capabilities() {
        return Capabilities.builder()
            .transaction(false)
            .batch(false)
            .pagination(true)  // 通过 cursor / offset
            .join(false)
            .sort(true)        // query param sort=-id
            .streaming(false)
            .generatedId(true) // 服务端生成
            .complexCondition(false)
            .build();
    }
}
```

#### 3.4.2 REST 翻译示例

通用 Query：
```java
User.where(UserQueryMeta.EMAIL.eq("a@b.com"))
    .orderBy(UserQueryMeta.ID.desc())
    .limit(10)
    .all();
```

翻译为 OData：
```
GET /users?$filter=email eq 'a@b.com'&$orderby=id desc&$top=10
```

翻译为 JSON:API：
```
GET /users?filter[email]=a@b.com&sort=-id&page[size]=10
```

### 3.5 CSV 文件数据源

```java
public class CsvDataSource implements DataSource {
    @Override
    public Capabilities capabilities() {
        return Capabilities.builder()
            .transaction(false)
            .batch(false)
            .pagination(true)
            .join(false)
            .sort(true)         // 内存排序
            .streaming(true)
            .generatedId(true)  // 自增行号
            .complexCondition(true)
            .build();
    }
}
```

CSV 数据源启动时全量加载到内存，所有查询在内存中执行。适合只读或低频写场景（写入需重写整个文件）。

---

## 四、能力声明与自动降级

### 4.1 能力检查机制

`Query` 执行前，Repository 会检查数据源 `Capabilities`：

```java
public <T> List<T> execute(Query<T> query) {
    DataSource ds = dataSourceRegistry.current();
    Capabilities caps = ds.capabilities();

    if (query.hasPagination() && !caps.supportsPagination()) {
        // 自动降级：内存分页
        return memoryPaginate(query, ds);
    }

    if (query.hasJoin() && !caps.supportsJoin()) {
        // 自动降级：应用层 join（N+1 检测 + 批量预加载）
        return applicationJoin(query, ds);
    }

    if (query.hasComplexCondition() && !caps.supportsComplexCondition()) {
        // 自动降级：简单条件查询 + 内存过滤
        return memoryFilter(query, ds);
    }

    return ds.openSession().execute(query);
}
```

### 4.2 降级策略表

| 能力 | 不支持时降级 |
|------|------------|
| Pagination | 全量查询 + 内存分页 + 警告日志 |
| Join | 应用层 join，自动 N+1 优化（批量预加载） |
| Transaction | 跳过事务边界，记录警告 |
| Sort | 全量查询 + 内存排序 |
| Batch | 顺序单条执行 |
| Streaming | 全量加载到内存 |
| GeneratedId | 客户端生成（UUID / Snowflake） |
| ComplexCondition | 简单条件 + 内存过滤 |

降级日志中明确标注，便于发现性能问题。

---

## 五、多数据源切换

### 5.1 切换方式

#### 方式一：注解驱动（推荐）

```java
@Service
public class UserService {

    @UseDataSource("mysql-primary")
    public User findFromPrimary(Long id) {
        return User.find(User.class, id);
    }

    @UseDataSource("mysql-replica")
    public List<User> searchOnReplica(String keyword) {
        return User.where(UserQueryMeta.EMAIL.like(keyword)).all();
    }

    @UseDataSource("mongo")
    public List<User> findByLog(String email) {
        return User.where(UserQueryMeta.EMAIL.eq(email)).all();
    }
}
```

通过 AOP 拦截 `@UseDataSource`，在方法进入时压栈 `DataSourceContext`，方法退出时弹栈。

#### 方式二：编程式

```java
try (DataSourceContext.Scope scope = DataSourceContext.use("mongo")) {
    User user = User.find(User.class, 1L);
    // ... 操作 mongo 数据源
}
// 自动弹栈
```

#### 方式三：实体绑定（默认数据源）

```java
@Entity(table = "users", dataSource = "mysql-primary")
public class User extends Model<User> { ... }

@Entity(table = "user_logs", dataSource = "mongo")
public class UserLog extends Model<UserLog> { ... }
```

`User` 默认走 mysql-primary，`UserLog` 默认走 mongo。无需显式切换。

### 5.2 优先级

数据源选择优先级（从高到低）：
1. 方法级 `@UseDataSource`
2. 类级 `@UseDataSource`
3. `DataSourceContext` 编程式
4. 实体 `@Entity(dataSource = ...)`
5. 默认数据源（`DataSourceRegistry.defaultDataSource()`）

---

## 六、自定义数据源扩展指南

### 6.1 实现步骤

要接入一个新数据源（如 Elasticsearch、Kafka、Excel），实现以下三件套：

1. **`DataSource`**：实现数据源生命周期与 Session 创建
2. **`QueryTranslator`**：将通用 Query 翻译为原生命令
3. **`ResultHandler`**：将原生结果转换为实体（基于 Mapper）

### 6.2 示例：自定义 Elasticsearch 数据源

```java
@ServiceProvider(DataSource.class)
public class EsDataSource implements DataSource {
    private final RestHighLevelClient client;
    private final String name;

    public EsDataSource(String name, RestHighLevelClient client) {
        this.name = name;
        this.client = client;
    }

    @Override
    public String name() { return name; }

    @Override
    public Session openSession() {
        return new EsSession(client, new EsQueryTranslator(), new EsResultHandler());
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.builder()
            .transaction(false)
            .batch(true)
            .pagination(true)
            .join(false)
            .sort(true)
            .streaming(true) // scroll API
            .generatedId(true) // ES 自动生成
            .complexCondition(true)
            .build();
    }

    @Override
    public HealthStatus health() {
        try {
            return client.ping() ? HealthStatus.UP : HealthStatus.DOWN;
        } catch (Exception e) {
            return HealthStatus.down(e.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}

public class EsQueryTranslator implements QueryTranslator {
    @Override
    public TranslatedQuery translate(Query<?> query) {
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        for (Condition c : query.conditions()) {
            translateCondition(bool, c);
        }
        SearchSourceBuilder source = new SearchSourceBuilder()
            .query(bool)
            .from(query.offset())
            .size(query.limit());
        for (Order o : query.orders()) {
            source.sort(o.field(), SortOrder.valueOf(o.direction().name()));
        }
        return TranslatedQuery.of(source.toString(), List.of(), Map.of());
    }

    private void translateCondition(BoolQueryBuilder bool, Condition c) {
        switch (c.operator()) {
            case EQ    -> bool.must(QueryBuilders.termQuery(c.field(), c.value()));
            case LIKE  -> bool.must(QueryBuilders.wildcardQuery(c.field(), "*" + c.value() + "*"));
            case GT    -> bool.must(QueryBuilders.rangeQuery(c.field()).gt(c.value()));
            case IN    -> bool.must(QueryBuilders.termsQuery(c.field(), (Collection<?>) c.value()));
            // ...
        }
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.builder()
            .transaction(false).batch(true).pagination(true)
            .join(false).sort(true).streaming(true).build();
    }
}

public class EsResultHandler<T> implements ResultHandler<T> {
    @Override
    public List<T> handle(Object nativeResult, Mapper<T> mapper) {
        SearchResponse response = (SearchResponse) nativeResult;
        List<T> result = new ArrayList<>(response.getHits().getHits().length);
        for (SearchHit hit : response.getHits()) {
            Row row = Row.fromMap(hit.getSourceAsMap());
            result.add(mapper.map(row));
        }
        return result;
    }
}
```

### 6.3 注册到 Registry

#### 方式一：Java ServiceLoader

在 `META-INF/services/com.holo.framework.horm.datasource.DataSource` 中声明实现类全名。HORM 启动时自动加载并通过无参构造器实例化（适合简单场景）。

#### 方式二：Spring Bean

```java
@Bean
public DataSource esDataSource() {
    return new EsDataSource("es", esClient());
}
```

`HormAutoConfiguration` 自动收集所有 `DataSource` 类型的 Bean 注册到 Registry。

#### 方式三：编程式

```java
HormContext.builder()
    .dataSource(new EsDataSource("es", esClient()))
    .dataSource(new MongoDataSource("mongo", mongoClient()))
    .build();
```

### 6.4 扩展 SPI 接口（高级）

除了三件套，HORM 还提供以下可选 SPI：

| SPI | 用途 |
|-----|------|
| `SchemaInitializer` | 启动时初始化 schema（建表、创建索引） |
| `MigrationProvider` | 提供迁移脚本 |
| `HealthIndicator` | 健康检查扩展 |
| `MetricsCollector` | 指标采集扩展 |
| `TransactionParticipant` | 事务参与者（用于 XA / Saga） |

---

## 七、事务管理

### 7.1 事务模型

HORM 提供两种事务模式：

#### 模式一：本地事务（单数据源）

```java
try (Transaction tx = Horm.transaction()) {
    User u = new User();
    u.setEmail("a@b.com");
    u.save();

    Order o = new Order();
    o.setUserId(u.getId());
    o.save();

    tx.commit();
}  // 自动 rollback if exception
```

#### 模式二：分布式事务（多数据源）

HORM 不强加分布式事务框架，而是提供 `TransactionParticipant` SPI，支持接入：
- **XA**：通过 Atomikos / Bitronix
- **Saga**：通过 Seata Saga 模式
- **最终一致**：通过 Outbox 模式 + 事件总线

### 7.2 与 Spring 事务集成

`HormTransactionManager` 实现 `PlatformTransactionManager`：

```java
@Transactional
public void createUserWithOrder() {
    User u = new User();
    u.setEmail("a@b.com");
    u.save();

    Order o = new Order();
    o.setUserId(u.getId());
    o.save();
}
```

`@Transactional` 自动管理 HORM Session 生命周期与提交/回滚。

### 7.3 多数据源事务

```java
@UseDataSource("mysql")
@Transactional
public void writeToMysql(User u) { u.save(); }

@UseDataSource("mongo")
@Transactional
public void writeToMongo(UserLog log) { log.save(); }

public void writeBoth() {
    // 跨数据源事务：通过 HormMultiTransaction
    try (MultiTransaction mt = Horm.multiTransaction()) {
        mt.enlist("mysql", () -> writeToMysql(user));
        mt.enlist("mongo", () -> writeToMongo(log));
        mt.commit(); // 两阶段提交（如果支持）或最佳努力
    }
}
```

---

## 八、连接池与资源管理

### 8.1 连接池集成

| 连接池 | 适用场景 |
|-------|---------|
| HikariCP | 默认，性能最佳 |
| Druid | 阿里系，监控完善 |
| c3p0 | 遗留系统 |
| Tomcat JDBC | Tomcat 部署 |

通过 `ConnectionPoolProvider` SPI 抽象，业务方按需选择。

### 8.2 Session 生命周期

- **Request Scope**：HTTP 请求内复用（Spring 自动管理）
- **Transaction Scope**：事务内复用，事务结束自动关闭
- **Operation Scope**：单次操作打开（无事务场景）

`Session` 实现引用计数，确保多线程安全。

---

## 九、健康检查与监控

### 9.1 健康检查

`DataSource.health()` 返回 `HealthStatus`，包含：
- 状态（UP / DOWN / DEGRADED）
- 延迟（ms）
- 详情（连接数、错误率等）

Spring Boot Actuator 自动暴露在 `/actuator/health` 端点。

### 9.2 指标

通过 Micrometer 暴露：
- `horm.datasource.connections.active{datasource}`
- `horm.datasource.connections.pending{datasource}`
- `horm.query.duration{datasource,entity,operation}`
- `horm.query.errors{datasource,entity,operation}`

### 9.3 慢查询日志

阈值可配置，超过阈值自动记录：
```
WARN  h.orm.datasource.slow - Slow query [mysql-primary] User.where(EMAIL.eq('?'))
       SQL: SELECT id, email, created_at FROM users WHERE email = ?
       Duration: 1523ms, Threshold: 1000ms
```

---

## 十、测试策略

### 10.1 单元测试

- `QueryTranslator` 单元测试：输入 Query，断言翻译结果
- `ResultHandler` 单元测试：输入模拟结果，断言实体映射

### 10.2 集成测试

使用 Testcontainers 启动真实数据库：
```java
@Testcontainers
class MySqlDataSourceIT {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Test
    void shouldCrudOnMysql() {
        DataSource ds = new SqlDataSource(mysql.getJdbcUrl(), ...);
        // 测试 CRUD
    }
}
```

### 10.3 兼容性测试矩阵

| 数据源 | 版本 | 测试覆盖 |
|-------|------|---------|
| MySQL | 5.7 / 8.0 / 8.4 | ✓ |
| PostgreSQL | 12 / 14 / 16 | ✓ |
| Oracle | 19c / 21c | ✓ |
| SQLite | 3.x | ✓ |
| H2 | 2.x | ✓ |
| MongoDB | 5.0 / 6.0 / 7.0 | ✓ |
| Redis | 6.x / 7.x | ✓ |

---

## 十一、参考资源

- [JDBC 4.3 规范](https://docs.oracle.com/javase/8/docs/api/java/sql/package-summary.html)
- [MongoDB Java Driver](https://www.mongodb.com/docs/drivers/java/sync/current/)
- [HikariCP](https://github.com/brettwooldridge/HikariCP)
- [Druid](https://github.com/alibaba/druid)
- [OData 协议](https://www.odata.org/)
- [JSON:API 规范](https://jsonapi.org/)
- [Seata 文档](https://seata.io/zh-cn/docs/overview/what-is-seata.html)
