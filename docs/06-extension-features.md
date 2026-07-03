# HORM 扩展特性

> 本文档详述 HORM 的类型安全机制、事务并发控制、迁移工具、日志监控、异常机制、代码生成工具与主流框架集成方案。

---

## 一、类型安全与编译时检查

### 1.1 类型安全的层次

| 层次 | 机制 | 保障范围 |
|------|------|---------|
| 字段引用 | APT 生成 `XxxQueryMeta` | 字段名拼写、字段所属实体 |
| 类型匹配 | 强类型字段类（`LongField<T>` 等） | 字段值类型 |
| 条件组合 | 类型安全的 `Condition` | 操作符与字段类型匹配 |
| 关联引用 | APT 校验关联字段存在 | 关联外键有效 |
| 实体类型 | Model<T> 泛型自引用 | save/update 返回类型 |

### 1.2 编译期错误示例

```java
// 字段名拼写错误 → 编译失败
User.where(User.class, UserQueryMeta.EMAIL.eq("a@b.com"))
    .and(UserQueryMeta.EMIAL.eq("x@y.com"));  // ✗ EMIAL 不存在

// 类型不匹配 → 编译失败
UserQueryMeta.ID.eq("string");  // ✗ LongField 不能接受 String

// 字段不属于当前实体 → 编译失败
User.where(User.class, OrderQueryMeta.AMOUNT.gt(100.0));  // ✗ 不能用 Order 字段查 User
```

### 1.3 Null 安全

通过 `@Nullness` 注解与 Checker Framework 集成（可选）：

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Id private Long id;

    @NotNull
    @Column
    private String email;

    @Nullable
    @Column
    private String nickname;  // 可空字段
}
```

生成的 Mapper 在 `map(Row)` 时自动处理 null 检查。

### 1.4 Checker Framework 集成（可选）

为高安全要求场景提供编译期 Null 检查：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.checkerframework</groupId>
                <artifactId>checker</artifactId>
                <version>3.42.0</version>
            </path>
        </annotationProcessorPaths>
        <compilerArgs>
            <arg>-processor</arg>
            <arg>org.checkerframework.checker.nullness.NullnessChecker</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

---

## 二、事务管理与并发控制

### 2.1 事务模型

HORM 事务基于 SPI，与数据源解耦：

```java
public interface TransactionManager {
    Transaction begin(TransactionDefinition definition);
    Transaction current();
}

public interface Transaction extends AutoCloseable {
    void commit();
    void rollback();
    boolean isActive();
    void setRollbackOnly();
    TransactionDefinition definition();
    @Override void close();  // 未提交时自动 rollback
}
```

### 2.2 传播行为

支持 Spring 兼容的传播行为：

| 传播行为 | 描述 |
|---------|------|
| REQUIRED | 当前有事务则加入，否则新建（默认） |
| REQUIRES_NEW | 总是新建，挂起当前事务 |
| NESTED | 嵌套事务（保存点） |
| SUPPORTS | 有事务则加入，否则非事务执行 |
| NOT_SUPPORTED | 非事务执行，挂起当前事务 |
| NEVER | 非事务执行，有事务则报错 |
| MANDATORY | 必须有事务，否则报错 |

### 2.3 隔离级别

| 隔离级别 | 描述 | HORM 支持 |
|---------|------|----------|
| READ_UNCOMMITTED | 读未提交 | ✓ |
| READ_COMMITTED | 读已提交 | ✓ |
| REPEATABLE_READ | 可重复读 | ✓ |
| SERIALIZABLE | 串行化 | ✓ |
| SNAPSHOT | 快照隔离（PG） | ✓ |

### 2.4 并发控制

#### 乐观锁

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Id private Long id;
    @Version private Long version;
    private BigDecimal balance;
}

// UPDATE users SET balance = ?, version = version + 1
// WHERE id = ? AND version = ?

try {
    user.update();
} catch (OptimisticLockException e) {
    // 重试或失败
}
```

HORM 提供自动重试机制：

```java
Horm.withRetry(RetryPolicy.builder()
    .maxAttempts(3)
    .backoff(Backoff.exponential(Duration.ofMillis(10), 2.0))
    .retryOn(OptimisticLockException.class)
    .build(),
    () -> user.update());
```

#### 悲观锁

```java
User user = User.where(User.class, UserQueryMeta.ID.eq(1L))
    .forUpdate()  // SELECT ... FOR UPDATE
    .one()
    .orElseThrow();

// 在事务中操作
user.setBalance(user.getBalance().subtract(amount));
user.update();
```

#### 分布式锁

集成 Redisson：

```java
try (Lock lock = Horm.distributedLock("user:1", Duration.ofSeconds(10))) {
    User user = User.find(User.class, 1L);
    user.update();
}
```

### 2.5 死锁检测

开启死锁检测后（默认开发环境启用），SQL 异常被解析：
- MySQL 错误码 1213（死锁）→ 自动重试
- PostgreSQL SQLSTATE 40P01（死锁）→ 自动重试
- Oracle ORA-00060 → 自动重试

### 2.6 事务事件

```java
@Transactional
public void transfer(...) { ... }

// 事务成功后回调
@AfterTransaction(phase = TransactionPhase.AFTER_COMMIT)
public void onTransferSuccess(TransferEvent event) {
    notificationService.notify(event.userId());
}

// 事务回滚后回调
@AfterTransaction(phase = TransactionPhase.AFTER_ROLLBACK)
public void onTransferFailure(TransferEvent event) {
    auditLogService.logFailure(event);
}
```

---

## 三、数据库迁移工具

### 3.1 设计目标

- 类似 Rails Migration 的 Ruby DSL 体验
- 支持正向与回滚
- 支持版本号管理与依赖追踪
- 支持 SQL 文件直写（复杂 DDL）
- 多数据源独立迁移

### 3.2 迁移脚本

```java
// V1__CreateUsersTable.java
public class V1__CreateUsersTable extends Migration {

    @Override
    public void up(Schema schema) {
        schema.createTable("users", t -> {
            t.bigIncrements("id");
            t.string("email", 128).notNull().unique();
            t.string("password", 128).notNull();
            t.string("status", 16).defaultVal("active");
            t.timestamps();
        });

        schema.createIndex("idx_users_status", "users", "status");
    }

    @Override
    public void down(Schema schema) {
        schema.dropTable("users");
    }
}
```

### 3.3 SQL 文件迁移

复杂 DDL 可直接写 SQL：

```sql
-- V2__AddFullTextIndex.sql
CREATE FULLTEXT INDEX idx_users_email_ft ON users(email);

-- V2__AddFullTextIndex.down.sql
DROP INDEX idx_users_email_ft ON users;
```

### 3.4 命令行

```bash
# 执行迁移到最新
java -jar holo-horm-codegen.jar migrate --datasource=mysql-primary

# 回滚最近一次
java -jar holo-horm-codegen.jar rollback --datasource=mysql-primary

# 回滚到指定版本
java -jar holo-horm-codegen.jar rollback --to=V3 --datasource=mysql-primary

# 查看迁移状态
java -jar holo-horm-codegen.jar status --datasource=mysql-primary

# 生成空迁移脚本
java -jar holo-horm-codegen.jar make:create_users_table
```

### 3.5 自动迁移（启动时）

```yaml
holo:
  horm:
    migration:
      enabled: true
      locations: classpath:db/migration
      auto-on-startup: true  # 启动时自动执行
      baseline-on-migrate: true  # 已有数据库首次启用时建立 baseline
      out-of-order: false  # 是否允许乱序迁移
```

### 3.6 多数据源迁移

每个数据源独立的迁移历史表：

```
mysql-primary: schema_migrations (version, applied_at, checksum)
mongo: schema_migrations (version, applied_at, checksum)
```

### 3.7 校验和

每个迁移脚本计算 SHA-256 校验和，启动时验证已应用迁移未被修改：

```
ERROR  h.orm.migration - Migration V1 checksum mismatch!
       Expected: a1b2c3d4...
       Actual:   e5f6g7h8...
       Migration file has been modified after applied. Please create a new migration.
```

---

## 四、日志与监控

### 4.1 日志体系

| 级别 | 输出 |
|------|------|
| ERROR | 异常、致命错误 |
| WARN | 慢查询、N+1 警告、降级提示 |
| INFO | 启动信息、迁移执行 |
| DEBUG | 生成的 SQL/Query、缓存命中 |
| TRACE | 参数值、详细执行流程 |

### 4.2 SQL 日志

```
DEBUG h.orm.datasource.sql - [mysql-primary] executing:
       SELECT id, email, created_at
       FROM users
       WHERE email = ?
       ORDER BY id DESC
       LIMIT 10 OFFSET 20
       Parameters: [a@b.com]
       Duration: 12ms, Rows: 5
```

### 4.3 慢查询日志

```
WARN  h.orm.datasource.slow - Slow query detected
       DataSource: mysql-primary
       Query: SELECT * FROM orders WHERE user_id IN (?, ?, ?, ...)
       Duration: 1523ms (threshold: 1000ms)
       Stacktrace: com.example.OrderService.findBigOrders(OrderService.java:45)
```

慢查询默认阈值 1s，可配置。捕获调用栈便于定位业务代码。

### 4.4 N+1 检测

```
WARN  h.orm.relation.nplus1 - Potential N+1 query
       Association: User.orders
       Triggered 12 times within /api/users
       Suggestion: Use .include(User::getOrders)
       Stacktrace: ...
```

### 4.5 P6Spy 集成

可选集成 P6Spy，提供更细粒度的 SQL 拦截、统计与日志：

```xml
<dependency>
    <groupId>p6spy</groupId>
    <artifactId>p6spy</artifactId>
</dependency>
```

```yaml
holo:
  horm:
    datasource:
      spy:
        enabled: true
        log-format: "%(currentTime) | %(executionTime)ms | %(category) | %(sqlSingleLine)"
```

### 4.6 Micrometer 指标

HORM 自动注册 Micrometer 指标：

```
horm_query_count_total{datasource,entity,operation}
horm_query_duration_seconds{datasource,entity,operation}
horm_query_rows_total{datasource,entity,operation}
horm_query_errors_total{datasource,entity,operation}
horm_cache_hits_total{level,entity}
horm_cache_misses_total{level,entity}
horm_cache_evictions_total{level,entity}
horm_cache_load_duration_seconds{level,entity}
horm_transaction_active_count{datasource}
horm_transaction_commit_total{datasource}
horm_transaction_rollback_total{datasource}
horm_connection_active_count{datasource}
horm_connection_pending_count{datasource}
```

### 4.7 分布式追踪

通过 Micrometer Tracing 自动注入 traceId：
- SQL 日志附带 traceId，便于追踪
- 缓存键附带 traceId（可选）
- 与 Zipkin / Jaeger / Tempo 集成

### 4.8 Actuator 端点

Spring Boot Actuator 集成：
- `/actuator/health` - 数据源健康检查
- `/actuator/metrics/horm.*` - HORM 指标
- `/actuator/horm/datasources` - 数据源列表与状态
- `/actuator/horm/migrations` - 迁移状态
- `/actuator/horm/cache` - 缓存链状态与统计

---

## 五、错误处理与异常机制

### 5.1 异常层次

```
HormException (RuntimeException)
├── ValidationException
│   └── ConstraintViolationException
├── PersistenceException
│   ├── EntityNotFoundException
│   ├── OptimisticLockException
│   ├── PessimisticLockException
│   ├── DuplicateKeyException
│   ├── DataIntegrityException
│   └── StaleObjectStateException
├── TransactionException
│   ├── TransactionSystemException
│   ├── HeuristicCompletionException
│   └── IllegalTransactionStateException
├── DataSourceException
│   ├── DataSourceNotFoundException
│   ├── ConnectionException
│   └── QueryTimeoutException
├── CacheException
│   ├── CacheLoadException
│   ├── CacheSerializationException
│   └── CacheUnavailableException
├── MappingException
│   ├── TypeConversionException
│   └── UnknownFieldException
└── MigrationException
    ├── MigrationChecksumException
    └── MigrationConflictException
```

### 5.2 异常转换

数据源原生异常自动转换为 HORM 异常：

```java
public class SqlExceptionHandler implements ExceptionHandler<SQLException> {
    @Override
    public HormException translate(SQLException e, QueryContext ctx) {
        String sqlState = e.getSQLState();
        int errorCode = e.getErrorCode();

        if ("23000".equals(sqlState) || errorCode == 1062) {
            return new DuplicateKeyException(ctx, e);
        }
        if ("23000".equals(sqlState) || errorCode == 1452) {
            return new DataIntegrityException(ctx, e);
        }
        if (errorCode == 1213) {
            return new DeadlockException(ctx, e);
        }
        return new PersistenceException(ctx, e);
    }
}
```

业务方捕获统一的 HORM 异常：

```java
try {
    user.save();
} catch (DuplicateKeyException e) {
    return "邮箱已存在";
} catch (ValidationException e) {
    return e.errors().toString();
} catch (HormException e) {
    log.error("保存用户失败", e);
    return "系统错误";
}
```

### 5.3 异常上下文

所有 HORM 异常携带诊断上下文：

```java
public class HormException extends RuntimeException {
    private final QueryContext queryContext;
    private final String datasourceName;
    private final String entityName;
    private final Duration elapsed;

    public String detailedMessage() {
        return String.format("[%s] %s, query=%s, elapsed=%dms",
            datasourceName, getMessage(), queryContext.sql(), elapsed.toMillis());
    }
}
```

### 5.4 全局异常处理

提供 Spring `@ControllerAdvice` 友好的异常转换器：

```java
@RestControllerAdvice
public class HormExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<?> handleValidation(ValidationException e) {
        return ResponseEntity.badRequest().body(Map.of(
            "code", "VALIDATION_ERROR",
            "errors", e.errors()
        ));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<?> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "code", "NOT_FOUND",
            "message", e.getMessage()
        ));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<?> handleDuplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "code", "CONFLICT",
            "message", "资源已存在"
        ));
    }
}
```

---

## 六、代码生成工具

### 6.1 功能

| 命令 | 描述 |
|------|------|
| `gen-entity` | 从数据库 schema 生成 Entity 类 |
| `gen-ddl` | 从 Entity 类生成 DDL |
| `gen-migration` | 比较 Entity 与 schema 差异，生成迁移脚本 |
| `gen-dto` | 从 Entity 生成 DTO 与 Mapper |
| `gen-repository` | 生成 Repository 接口骨架 |
| `gen-query` | 强制重新生成 QueryMeta（一般 APT 自动生成） |

### 6.2 从 DDL 生成 Entity

```bash
java -jar holo-horm-codegen.jar gen-entity \
    --jdbc-url=jdbc:mysql://localhost:3306/mydb \
    --username=root \
    --password=*** \
    --tables=users,orders \
    --output=src/main/java/com/example/entity \
    --package=com.example.entity
```

生成结果：

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128, nullable = false, unique = true)
    private String email;

    @Column(length = 128, nullable = false)
    private String password;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // getters/setters
}
```

### 6.3 从 Entity 生成 DDL

```bash
java -jar holo-horm-codegen.jar gen-ddl \
    --entities=com.example.entity.User \
    --output=src/main/resources/db/ddl \
    --dialect=mysql
```

输出 `users.sql`：

```sql
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(128) NOT NULL,
    password VARCHAR(128) NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 6.4 比较生成迁移

```bash
java -jar holo-horm-codegen.jar gen-migration \
    --entities=com.example.entity.* \
    --jdbc-url=jdbc:mysql://localhost:3306/mydb \
    --name=add_user_phone
```

自动检测 schema 差异并生成迁移脚本：

```java
public class V20260704120000__AddUserPhone extends Migration {
    @Override
    public void up(Schema schema) {
        schema.alterTable("users", t -> {
            t.string("phone", 20).nullable();
        });
    }

    @Override
    public void down(Schema schema) {
        schema.alterTable("users", t -> {
            t.dropColumn("phone");
        });
    }
}
```

### 6.5 Maven 插件

提供 Maven 插件便捷调用：

```xml
<plugin>
    <groupId>com.holo.framework</groupId>
    <artifactId>holo-horm-codegen-maven-plugin</artifactId>
    <version>${holo-horm.version}</version>
    <configuration>
        <jdbcUrl>${db.url}</jdbcUrl>
        <username>${db.username}</username>
        <password>${db.password}</password>
        <output>src/main/java/com/example/entity</output>
        <package>com.example.entity</package>
    </configuration>
</plugin>
```

```bash
mvn holo-horm-codegen:gen-entity
mvn holo-horm-codegen:gen-migration -Dname=add_user_phone
```

---

## 七、与主流框架集成

### 7.1 Spring Boot

```xml
<dependency>
    <groupId>com.holo.framework</groupId>
    <artifactId>holo-horm-spring-boot-starter</artifactId>
</dependency>
```

自动装配：
- `HormContext`：核心上下文
- `DataSourceRegistry`：数据源注册中心
- `CacheChain`：缓存链
- `TransactionManager`：事务管理器
- `HormMetricsAutoConfiguration`：Micrometer 指标
- `HormHealthIndicator`：Actuator 健康检查

```yaml
holo:
  horm:
    datasources:
      mysql-primary:
        type: sql
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://localhost:3306/mydb
        username: root
        password: ***
        pool: hikari
        maximum-pool-size: 20
      mongo:
        type: mongo
        uri: mongodb://localhost:27017
        database: mydb
    cache:
      enabled: true
      chain:
        - level: L1
          type: caffeine
          spec: maximumSize=10000,expireAfterWrite=30m
        - level: L2
          type: redis
    migration:
      enabled: true
      auto-on-startup: true
```

### 7.2 Spring 事务集成

```java
@Service
public class UserService {
    @Transactional
    public void createUser(UserDto dto) {
        User user = new User();
        user.setEmail(dto.getEmail());
        user.save();
    }
}
```

`@Transactional` 自动通过 `HormTransactionManager` 管理事务。

### 7.3 Spring Data Page 集成

```java
Page<User> page = User.where(User.class, ...)
    .page(0, 20);
// 自动转换为 Spring Data Page
org.springframework.data.domain.Page<User> springPage = page.toSpringPage();
```

### 7.4 Quarkus（计划中）

通过 Quarkus Extension 集成：
- `@HormRepository` 注解
- Quarkus 原生事务集成
- 原生镜像支持

### 7.5 Helidon（计划中）

通过 Helidon MP / SE 集成，支持 MicroProfile 规范。

### 7.6 与 MyBatis 共存

HORM 与 MyBatis 可在同一应用中并存：
- HORM 操作 `@Entity` 标注的实体
- MyBatis 操作传统 Mapper
- 共享同一 DataSource（连接池）

### 7.7 与 JPA 共存

类似地，HORM 与 JPA 可共存，但建议：
- 同一实体不要同时被 HORM 与 JPA 管理（避免冲突）
- 通过包名或模块划分边界

### 7.8 反应式集成（计划中）

HORM 计划支持反应式 API：

```java
// 当前（同步）
User user = User.find(User.class, 1L);

// 计划中（反应式）
Mono<User> user = User.reactive().find(User.class, 1L);
Flux<User> users = User.reactive().where(UserQueryMeta.STATUS.eq(Status.ACTIVE)).all();
```

反应式 API 将与 R2DBC / Reactive Redis 集成。

---

## 八、多租户支持

### 8.1 三种多租户模式

| 模式 | 描述 | HORM 支持 |
|------|------|----------|
| 独立数据库 | 每租户一个数据库 | ✓（按租户路由数据源） |
| 共享数据库独立 Schema | 每租户独立 schema | ✓（按租户切换 schema） |
| 共享 Schema 字段隔离 | 同表通过 tenant_id 隔离 | ✓（自动注入 tenant_id 条件） |

### 8.2 字段隔离示例

```java
@Entity(table = "users")
@MultiTenant(strategy = TenantStrategy.FIELD, column = "tenant_id")
public class User extends Model<User> { ... }

// 业务代码无感
List<User> users = User.all(User.class);
// → SELECT * FROM users WHERE tenant_id = ?
// tenant_id 由 TenantContext 自动注入
```

### 8.3 租户上下文

```java
try (TenantContext.Scope scope = TenantContext.use("tenant_001")) {
    // 此范围内所有操作自动带 tenant_id = 'tenant_001'
    User user = User.find(User.class, 1L);
}
```

可通过 Web Filter / Interceptor 自动从 JWT 或 Header 提取租户 ID 注入上下文。

---

## 九、国际化

### 9.1 错误消息

```properties
# messages_zh_CN.properties
holo.validation.notBlank=字段 {0} 不能为空
holo.validation.email=邮箱格式不正确
holo.duplicate.email=邮箱已存在
holo.optimistic_lock=数据已被其他用户修改，请刷新后重试

# messages_en.properties
holo.validation.notBlank=Field {0} must not be blank
holo.validation.email=Email format is invalid
holo.duplicate.email=Email already exists
holo.optimistic_lock=Data has been modified by another user, please refresh and retry
```

### 9.2 使用

```java
Horm.messageResolver().locale(Locale.SIMPLIFIED_CHINESE);

try {
    user.save();
} catch (ValidationException e) {
    e.errors().forEach(err -> System.out.println(err.localizedMessage()));
}
```

---

## 十、扩展点总览

| SPI | 用途 |
|-----|------|
| `DataSource` | 自定义数据源 |
| `QueryTranslator` | Query 翻译器 |
| `ResultHandler` | 结果映射 |
| `Cache` | 自定义缓存层 |
| `CacheLoader` | 缓存加载器 |
| `CacheWriter` | 缓存写入器 |
| `TypeConverter` | 类型转换器 |
| `Validator` | 自定义验证器 |
| `CacheEventListener` | 缓存事件监听 |
| `Observer` | 实体观察者 |
| `MigrationProvider` | 迁移脚本提供者 |
| `ExceptionHandler` | 异常转换器 |
| `SchemaInitializer` | Schema 初始化 |
| `MetricsCollector` | 指标采集 |
| `HealthIndicator` | 健康检查 |
| `TransactionParticipant` | 事务参与者 |

通过 `HormContext.builder()` 或 Spring Bean 自动注册。
