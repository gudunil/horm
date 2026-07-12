# HORM 用户指南

> 本文是 HORM 完整用户指南，覆盖 Active Record API、Query DSL、事务管理、缓存链、关联关系、多数据源、方言适配与迁移。

> **实现状态**：M1-M8.7 已完成。本文档中所有 API 示例均采用 `Model.find(Class, id)` / `Model.query(Class).where(...)` 风格，与 ByteBuddy 注入时序兼容。

---

## 一、Active Record API

### 1.1 实体定义

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String email;

    @Column(name = "created_at")
    private Instant createdAt;

    @Version
    private int version;              // 乐观锁（M4）

    // getters/setters ...
}
```

### 1.2 实例方法

| 方法 | 说明 |
|------|------|
| `save()` | INSERT 或 UPDATE（根据 id 是否为空） |
| `saveWith(relations)` | 级联保存指定关联 |
| `delete()` | DELETE 当前实体 |
| `deleteWith(relations)` | 级联删除指定关联 |
| `isPersisted()` | `id != null` |
| `reload()` | 从数据库重新加载 |

### 1.3 静态查询方法

```java
// 按主键查找
User user = Model.find(User.class, 1L);

// 批量查找
Map<Object, User> users = Model.findMany(User.class, List.of(1L, 2L, 3L));

// 全部
List<User> all = Model.all(User.class);

// 计数
long total = Model.count(User.class);

// 查询构建器
Query<User> q = Model.query(User.class);

// 批量更新
int affected = Model.update(User.class)
    .set("email", "x@y.com")
    .where(UserQueryMeta.ID.eq(1L))
    .execute();

// 批量删除
int deleted = Model.delete(User.class)
    .where(UserQueryMeta.ID.lt(10L))
    .execute();
```

### 1.4 静态方法调用规范

HORM 通过 ByteBuddy 在编译完成后将 `User.find(id)` / `User.query()` 等便捷静态方法注入到实体类字节码中。但由于 Java 静态方法在编译期绑定，**业务源码中推荐使用 `Model.find(User.class, id)` 风格**，避免编译期绑定到 `Model` 的 fallback 方法。

| 推荐 ✅ | 不推荐 ❌ |
|--------|----------|
| `Model.find(User.class, 1L)` | `User.find(1L)`（仅在 APT 注入完成后重新编译可用） |
| `Model.query(User.class).where(...)` | `User.where(...)`（API 不存在） |
| `Model.all(User.class)` | `User.all()`（同上） |

---

## 二、Query DSL

### 2.1 基础查询

```java
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.query.Order;
import com.example.entity.generated.UserQueryMeta;

List<User> result = Model.query(User.class)
    .where(UserQueryMeta.EMAIL.like("%@holo.dev"))
    .orderBy(UserQueryMeta.CREATED_AT, Order.DESC)
    .limit(10)
    .offset(20)
    .list();
```

### 2.2 条件组合

```java
import static com.holo.framework.horm.meta.query.Conditions.*;

// AND（默认）
Model.query(User.class)
    .where(UserQueryMeta.EMAIL.like("%@holo.dev"),
           UserQueryMeta.ID.gt(100L))
    .list();

// 显式 AND / OR
Model.query(User.class)
    .where(or(
        UserQueryMeta.EMAIL.eq("a@holo.dev"),
        UserQueryMeta.EMAIL.eq("b@holo.dev")
    ))
    .list();

// 嵌套组合
Model.query(User.class)
    .where(UserQueryMeta.ID.gt(100L),
           or(UserQueryMeta.EMAIL.like("%@holo.dev"),
              UserQueryMeta.EMAIL.like("%@example.com")))
    .list();
```

### 2.3 字段类型与操作矩阵

| 字段类型 | 支持操作 |
|---------|---------|
| `LongField` / `IntegerField` / `BigDecimalField` / `InstantField` | eq, ne, gt, lt, ge, le, between, in, isNull, notNull |
| `StringField` | eq, ne, like, in, isNull, notNull |
| `BooleanField` / `EnumField` | eq, ne, in, isNull, notNull（无比较语义） |

### 2.4 单条查询

```java
Optional<User> first = Model.query(User.class)
    .where(UserQueryMeta.EMAIL.eq("a@holo.dev"))
    .findFirst();              // 强制 LIMIT 1

boolean exists = Model.query(User.class)
    .where(UserQueryMeta.EMAIL.eq("a@holo.dev"))
    .exists();
```

### 2.5 关联预加载（Eager JOIN）

```java
// 假设 User 有 @HasMany(foreignKey = "user_id") List<Order> orders
List<User> users = Model.query(User.class)
    .fetch(UserQueryMeta.ORDERS)
    .list();                    // LEFT JOIN orders ON orders.user_id = users.id
```

---

## 三、事务管理

### 3.1 编程式事务

```java
import com.holo.framework.horm.core.Horm;

Horm.tx(() -> {
    User u = new User();
    u.setEmail("x@y.com");
    u.save();

    Order o = new Order();
    o.setUserId(u.getId());
    o.save();
    return o.getId();
});
```

### 3.2 声明式事务（Spring Boot + APT 代理）

```java
@Service
public class UserService {
    @Transactional(timeout = 5, rollbackFor = {IllegalStateException.class})
    public Long register(String email) {
        User u = new User();
        u.setEmail(email);
        u.save();
        return u.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void audit(Long userId) { ... }
}
```

支持的事务属性：
- `propagation`: `REQUIRED` (默认) / `REQUIRES_NEW`
- `isolation`: `DEFAULT` / `READ_UNCOMMITTED` / `READ_COMMITTED` / `REPEATABLE_READ` / `SERIALIZABLE`
- `timeout`: 秒
- `rollbackFor` / `noRollbackFor`: 异常类数组
- `readOnly`: 布尔

### 3.3 乐观锁

实体标注 `@Version` 字段后，UPDATE/DELETE 自动添加 `WHERE version = ?` 子句：

```java
@Entity
public class User extends Model<User> {
    @Id private Long id;
    @Version private int version;
    // ...
}

User u = Model.find(User.class, 1L);  // version=5
u.setEmail("new");

// UPDATE users SET email=?, version=6 WHERE id=? AND version=5
// 若受影响行数为 0，抛 OptimisticLockException
u.save();
```

---

## 四、缓存链

### 4.1 启用 L1 缓存

```java
import com.holo.framework.horm.cache.CaffeineCache;
import com.holo.framework.horm.cache.DefaultCacheChain;
import com.holo.framework.horm.cache.CachePolicy;
import com.holo.framework.horm.core.HormContext;
import java.time.Duration;

CachePolicy policy = CachePolicy.builder()
    .ttl(Duration.ofMinutes(30))
    .maxEntries(10_000)
    .build();

CaffeineCache l1 = new CaffeineCache("user-l1", policy);
DefaultCacheChain chain = new DefaultCacheChain(l1);

HormContext ctx = new HormContext(dataSourceProvider, chain);
HormContext.install(ctx);
```

### 4.2 标注实体为可缓存

```java
import com.holo.framework.horm.meta.annotation.CacheLevel;
import com.holo.framework.horm.meta.annotation.CachePolicy;
import com.holo.framework.horm.meta.annotation.Cached;
import com.holo.framework.horm.meta.annotation.WriteStrategy;

@Entity
@Cached(levels = {CacheLevel.L1},
        policy = @CachePolicy(ttl = "30m", maxEntries = 5000,
                              writeStrategy = WriteStrategy.THROUGH))
public class User extends Model<User> { ... }
```

### 4.3 缓存策略

| `WriteStrategy` | 行为 |
|-----------------|------|
| `AROUND`（默认） | 写穿透到 DB，不写缓存；查询不缓存 |
| `THROUGH` | 写穿透到 DB + 缓存；查询走缓存（单表、无 JOIN、无投影时） |
| `BEHIND` | 异步写（M6 预留，未实现） |

### 4.4 批量加载

```java
Map<Object, User> users = Model.findMany(User.class, List.of(1L, 2L, 3L));
// 命中 L1 直接返回；未命中通过 SELECT ... WHERE id IN (?,?,...) 批量回填
```

---

## 五、关联关系

### 5.1 五种关联类型

| 注解 | 关系 | 外键位置 | 示例 |
|------|------|---------|------|
| `@BelongsTo` | N:1 | 子表 | `Order belongsTo User`（order.user_id → users.id） |
| `@HasOne` | 1:1 | 子表 | `User hasOne Profile`（profile.user_id → users.id） |
| `@HasMany` | 1:N | 子表 | `User hasMany Order`（order.user_id → users.id） |
| `@HasAndBelongsToMany` | M:N | 中间表 | `User hasAndBelongsToMany Tag`（user_tags 中间表） |
| `@HasManyThrough` | 1:N 通过 | 中间表 | `User hasManyThrough Order → Item` |

### 5.2 定义关联

```java
@Entity
public class User extends Model<User> {
    @Id private Long id;

    @HasOne(foreignKey = "user_id")
    private List<Profile> profiles;

    @HasMany(foreignKey = "user_id", cascade = CascadeType.ALL)
    private List<Order> orders;

    @HasAndBelongsToMany(
        joinTable = "user_tags",
        foreignKey = "user_id",
        associationForeignKey = "tag_id"
    )
    private List<Tag> tags;

    // getters/setters ...
}
```

### 5.3 级联保存/删除

```java
User u = new User();
u.setEmail("parent@holo.dev");

Order o = new Order();
o.setAmount(BigDecimal.TEN);
u.getOrders().add(o);

u.saveWith("orders");          // 先保存 User 回填 id，再保存 Order 用 user_id
u.deleteWith("orders");        // 先删 Order，再删 User
```

### 5.4 关联查询

```java
List<User> users = Model.query(User.class)
    .fetch(UserQueryMeta.ORDERS)             // LEFT JOIN
    .innerJoin(UserQueryMeta.PROFILES)       // INNER JOIN
    .where(UserQueryMeta.ID.gt(10L))
    .list();
```

---

## 六、多数据源

### 6.1 编程式注册

```java
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.SimpleDataSourceProvider;

Horm.install("default", new SimpleDataSourceProvider(defaultDs));
Horm.install("archive", new SimpleDataSourceProvider(archiveDs));
```

### 6.2 实体绑定数据源

```java
@Entity(table = "users", dataSource = "archive")
public class ArchivedUser extends Model<ArchivedUser> { ... }
```

`JdbcRepository` 根据 `EntityMeta.dataSource()` 自动选择 `DataSourceProvider`。

### 6.3 Spring Boot 多数据源

```yaml
holo:
  horm:
    datasources:
      default:
        url: jdbc:mysql://localhost:3306/main
        username: root
        password: secret
      archive:
        url: jdbc:postgresql://localhost:5432/archive
        username: postgres
        password: pgpass
        dialect: postgresql
```

### 6.4 事务隔离

每个数据源维护独立的事务栈（`Map<String, Deque<TransactionStatus>>`），跨数据源操作各自独立事务。**不支持 XA 分布式事务**。

---

## 七、方言适配

### 7.1 自动检测

HORM 根据 JDBC URL 自动检测方言：

| URL 模式 | 方言 |
|---------|------|
| `jdbc:mysql://...` | `MySqlDialect` |
| `jdbc:postgresql://...` | `PostgresDialect` |
| `jdbc:h2:...;MODE=MySQL` | `H2Dialect`（MySQL 兼容） |
| `jdbc:h2:...;MODE=PostgreSQL` | `H2Dialect`（PostgreSQL 兼容） |

### 7.2 显式指定

```yaml
holo:
  horm:
    datasources:
      default:
        url: jdbc:db2://localhost:50000/sample
        dialect: mysql              # 强制使用 MySQL 方言
```

### 7.3 方言差异点

| 能力 | MySQL | PostgreSQL | H2 |
|------|-------|-----------|-----|
| 主键生成 | `AUTO_INCREMENT` | `GENERATED ... AS IDENTITY` | `AUTO_INCREMENT` / `IDENTITY` |
| 分页 | `LIMIT ?, ?` | `LIMIT ? OFFSET ?` | `LIMIT ? OFFSET ?` |
| 标识符引用 | `` ` `` | `"` | `"` |
| 批量插入 | 多 VALUES | 多 VALUES | 多 VALUES |

---

## 八、数据库迁移

### 8.1 DSL 风格

```java
public class V001__CreateUsers extends Migration {
    @Override
    public void up(Schema schema) {
        schema.create("users", t -> {
            t.bigIncrements("id");
            t.string("email", 128).nullable(false);
            t.timestamp("created_at").nullable(true);
            t.unique("email");
        });
    }

    @Override
    public void down(Schema schema) {
        schema.drop("users");
    }
}
```

### 8.2 纯 SQL 迁移

`src/main/resources/db/migration/V002__AddUserIndex.sql`：

```sql
CREATE INDEX idx_users_email ON users(email);
```

### 8.3 Spring Boot 自动迁移

```yaml
holo:
  horm:
    migration:
      auto-on-startup: true       # 启动时调用 Flyway.migrate()
```

### 8.4 命令行

```bash
java -jar holo-horm-migration.jar migrate
java -jar holo-horm-migration.jar status
java -jar holo-horm-migration.jar rollback --to=1
```

> Flyway 社区版不支持 undo migration，`rollback` 仅在测试中使用。

---

## 九、Spring Boot 集成

### 9.1 自动装配

`holo-horm-spring-boot-starter` 通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册：

- `HormAutoConfiguration` — 单数据源自动装配
- `HormMultiDataSourceAutoConfiguration` — 多数据源
- `HormMigrationAutoConfiguration` — Flyway 自动迁移
- `HormTransactionalBeanPostProcessor` — `@Transactional` 代理织入

### 9.2 配置属性

```yaml
holo:
  horm:
    auto-on-startup: true                  # 默认 false
    migration:
      auto-on-startup: true
      locations: classpath:db/migration
      baseline-on-migrate: true
    datasources:
      default:
        url: jdbc:mysql://localhost:3306/demo
        username: root
        password: secret
        driver-class-name: com.mysql.cj.jdbc.Driver
        dialect: mysql
```

### 9.3 `@EnableHorm`

```java
@SpringBootApplication
@EnableHorm
public class App { ... }
```

启用 `@Transactional` 运行时代理（基于 M8.7 APT 编译期生成代理子类，零反射）。

---

## 十、扩展点

| 扩展点 | SPI 接口 | 模块 |
|--------|---------|------|
| 数据源 | `DataSourceProvider` | core |
| 缓存层 | `Cache` | cache |
| 方言 | `Dialect` | core |
| 迁移渲染器 | `SchemaRenderer` | migration |
| 事务代理工厂 | `TransactionProxyFactory` | meta |
| 实体元数据 | `EntityMetaProvider` | meta |
| 事务顾问 | `TransactionAdvisorProvider` | meta |

所有扩展点通过 `META-INF/services/<接口全限定名>` + `ServiceLoader` 发现。

---

## 参考文档

- [05-active-record.md](./05-active-record.md) — Active Record 完整设计
- [04-cache-chain.md](./04-cache-chain.md) — 缓存链设计
- [03-multi-datasource.md](./03-multi-datasource.md) — 多数据源 SPI
- [08-dialect-adaptation.md](./08-dialect-adaptation.md) — 方言适配
- [09-zero-reflection-optimization.md](./09-zero-reflection-optimization.md) — 零反射优化
- [12-migration-guide.md](./12-migration-guide.md) — 从 MyBatis/Hibernate 迁移
