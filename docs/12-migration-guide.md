# HORM 迁移指南

> 本文帮助开发者从 MyBatis、Hibernate/JPA、Spring Data JPA 等主流 ORM 框架迁移到 HORM。

> **适用版本**：HORM 1.0.0-SNAPSHOT（M1-M8.7 已完成）

---

## 一、概念映射表

| 概念 | MyBatis | Hibernate / JPA | HORM |
|------|---------|----------------|------|
| 实体定义 | POJO + XML 映射 | `@Entity` + `@Table` | `@Entity` + `@Table` + `extends Model<T>` |
| 主键 | XML `<id>` + `useGeneratedKeys` | `@Id` + `@GeneratedValue` | `@Id` + `GenerationType` |
| 字段映射 | `<result>` | `@Column` | `@Column` |
| 查询 | XML `<select>` / 注解 SQL | HQL / Criteria / JPQL | 类型安全 DSL（`XxxQueryMeta`） |
| 事务 | Spring `@Transactional` | Spring `@Transactional` | `@Transactional`（APT 编译期代理） |
| 关联 | XML `<association>`/`<collection>` | `@ManyToOne`/`@OneToMany` | `@BelongsTo`/`@HasMany`/`@HasOne`/... |
| 缓存 | 无内置 | L2 Cache (EHCache 等) | 缓存链（L1 Caffeine + L2 Redis stub） |
| 迁移 | 无 | 无内置 | Flyway + DSL |
| 代码生成 | MyBatis Generator | 无 | APT 编译期生成 |
| 运行时反射 | 无 | 大量 | 零 |

---

## 二、实体定义对照

### 2.1 MyBatis → HORM

**MyBatis（POJO + XML）**：

```java
// User.java
public class User {
    private Long id;
    private String email;
    private Instant createdAt;
    // getters/setters
}
```

```xml
<!-- UserMapper.xml -->
<mapper namespace="com.example.UserMapper">
    <resultMap id="userMap" type="com.example.User">
        <id property="id" column="id"/>
        <result property="email" column="email"/>
        <result property="createdAt" column="created_at"/>
    </resultMap>
</mapper>
```

**HORM**：

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String email;

    @Column(name = "created_at")
    private Instant createdAt;

    // getters/setters
}
```

无需 XML 映射文件。APT 在编译期生成 `UserMeta`/`UserMapper`/`UserQueryMeta`。

### 2.2 Hibernate / JPA → HORM

**Hibernate**：

```java
@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "users")
public class User {
    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.Column(nullable = false, length = 128)
    private String email;

    @jakarta.persistence.Column(name = "created_at")
    private Instant createdAt;
}
```

**HORM**：

```java
@com.holo.framework.horm.meta.annotation.Entity(table = "users")
public class User extends Model<User> {
    @com.holo.framework.horm.meta.annotation.Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @com.holo.framework.horm.meta.annotation.Column(nullable = false, length = 128)
    private String email;

    @com.holo.framework.horm.meta.annotation.Column(name = "created_at")
    private Instant createdAt;
}
```

主要差异：
1. 注解包名不同（`com.holo.framework.horm.meta.annotation` vs `jakarta.persistence`）
2. 必须继承 `Model<T>`（CRTP）
3. 必须启用 APT 处理器（见 [10-quickstart.md](./10-quickstart.md)）

---

## 三、查询对照

### 3.1 按主键查询

**MyBatis**：

```java
User user = sqlSession.selectOne("com.example.UserMapper.findById", 1L);
```

**Hibernate**：

```java
User user = session.find(User.class, 1L);
// 或
User user = session.byId(User.class).load(1L);
```

**HORM**：

```java
User user = Model.find(User.class, 1L);
```

### 3.2 条件查询

**MyBatis（XML）**：

```xml
<select id="findByDomain" resultMap="userMap">
    SELECT * FROM users
    WHERE email LIKE #{pattern}
    ORDER BY created_at DESC
    LIMIT #{limit}
</select>
```

```java
List<User> users = sqlSession.selectList("com.example.UserMapper.findByDomain",
    Map.of("pattern", "%@holo.dev", "limit", 10));
```

**Hibernate（HQL）**：

```java
List<User> users = session.createQuery(
        "FROM User WHERE email LIKE :pattern ORDER BY createdAt DESC LIMIT :limit",
        User.class)
    .setParameter("pattern", "%@holo.dev")
    .setParameter("limit", 10)
    .getResultList();
```

**HORM（类型安全 DSL）**：

```java
List<User> users = Model.query(User.class)
    .where(UserQueryMeta.EMAIL.like("%@holo.dev"))
    .orderBy(UserQueryMeta.CREATED_AT, Order.DESC)
    .limit(10)
    .list();
```

HORM 的优势：
- 字段引用编译期检查（拼错字段名编译失败）
- 类型不匹配编译失败（如 `EMAIL.eq(123)`）
- 无 SQL 注入风险（值通过 `?` 绑定）

### 3.3 批量查询

**MyBatis**：

```xml
<select id="findByIds" resultMap="userMap">
    SELECT * FROM users WHERE id IN
    <foreach item="id" collection="list" open="(" separator="," close=")">
        #{id}
    </foreach>
</select>
```

```java
List<User> users = sqlSession.selectList("findByIds", List.of(1L, 2L, 3L));
```

**Hibernate**：

```java
List<User> users = session.byMultipleIds(User.class)
    .multiLoad(List.of(1L, 2L, 3L));
```

**HORM**：

```java
Map<Object, User> users = Model.findMany(User.class, List.of(1L, 2L, 3L));
// 若启用缓存，自动批量命中 L1 + 缺失 key 一次性 SELECT ... WHERE id IN (?,?,...)
```

### 3.4 INSERT / UPDATE / DELETE

**MyBatis**：

```xml
<insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO users (email, created_at) VALUES (#{email}, #{createdAt})
</insert>
<update id="update">
    UPDATE users SET email=#{email} WHERE id=#{id}
</update>
<delete id="deleteById">
    DELETE FROM users WHERE id=#{id}
</delete>
```

```java
sqlSession.insert("insert", user);
sqlSession.update("update", user);
sqlSession.delete("deleteById", 1L);
```

**Hibernate**：

```java
session.persist(user);
session.merge(user);
session.remove(user);
```

**HORM**：

```java
user.save();          // INSERT（id 为空）或 UPDATE（id 不为空）
user.delete();        // DELETE FROM users WHERE id = ?
```

### 3.5 批量 UPDATE / DELETE

**MyBatis**：

```xml
<update id="updateEmailDomain">
    UPDATE users SET email = CONCAT('x', email) WHERE id &lt; #{threshold}
</update>
```

**Hibernate（HQL）**：

```java
int affected = session.createMutationQuery(
        "UPDATE User SET email = :email WHERE id < :threshold")
    .setParameter("email", "new@holo.dev")
    .setParameter("threshold", 100L)
    .executeUpdate();
```

**HORM**：

```java
int affected = Model.update(User.class)
    .set("email", "new@holo.dev")
    .where(UserQueryMeta.ID.lt(100L))
    .execute();

int deleted = Model.delete(User.class)
    .where(UserQueryMeta.ID.lt(100L))
    .execute();
```

---

## 四、事务对照

### 4.1 Spring 风格声明式事务

**MyBatis / Hibernate**：

```java
@Service
public class UserService {
    @Transactional
    public void register(String email) { ... }
}
```

**HORM**：

```java
@Service
public class UserService {
    @com.holo.framework.horm.core.transaction.Transactional
    public void register(String email) { ... }
}
```

注：HORM 的 `@Transactional` 是独立注解（`com.holo.framework.horm.core.transaction.Transactional`），不依赖 Spring `PlatformTransactionManager`。APT 在编译期为带注解的类生成代理子类（M8.7 实现），运行时零反射。

### 4.2 编程式事务

**MyBatis / Spring**：

```java
transactionTemplate.execute(status -> {
    userMapper.insert(user);
    return user.getId();
});
```

**Hibernate**：

```java
session.beginTransaction();
try {
    session.persist(user);
    session.getTransaction().commit();
} catch (Exception e) {
    session.getTransaction().rollback();
}
```

**HORM**：

```java
Horm.tx(() -> {
    user.save();
    return user.getId();
});
```

---

## 五、关联关系对照

### 5.1 概念对照

| MyBatis | Hibernate / JPA | HORM |
|---------|----------------|------|
| `<association>` | `@ManyToOne` | `@BelongsTo` |
| `<collection>` | `@OneToMany` | `@HasMany` |
| `<association>` (1:1) | `@OneToOne` | `@HasOne` |
| 中间表 + `<collection>` | `@ManyToMany` | `@HasAndBelongsToMany` |
| 嵌套 + `<collection>` | `@OneToMany` + `@ManyToOne` | `@HasManyThrough` |

### 5.2 关联定义示例

**Hibernate**：

```java
@jakarta.persistence.Entity
public class User {
    @Id private Long id;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    private List<Order> orders;
}

@jakarta.persistence.Entity
public class Order {
    @Id private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
}
```

**HORM**：

```java
@Entity
public class User extends Model<User> {
    @Id private Long id;

    @HasMany(foreignKey = "user_id", cascade = CascadeType.ALL)
    private List<Order> orders;
}

@Entity
public class Order extends Model<Order> {
    @Id private Long id;

    @BelongsTo(foreignKey = "user_id", references = "id")
    private List<User> users;        // 注意：HORM 统一 List 语义（R5）
}
```

关键差异：
1. `@BelongsTo` 返回 `List<User>`（同父兄弟列表），不是单个 `User`（HORM R5 统一 List 语义）
2. `foreignKey` 显式声明，无 `mappedBy` 反向映射
3. 默认不加载关联，需显式 `fetch()` 触发 eager JOIN

### 5.3 关联查询

**Hibernate**：

```java
List<User> users = session.createQuery(
        "SELECT DISTINCT u FROM User u LEFT JOIN FETCH u.orders", User.class)
    .getResultList();
```

**HORM**：

```java
List<User> users = Model.query(User.class)
    .fetch(UserQueryMeta.ORDERS)        // LEFT JOIN orders ON orders.user_id = users.id
    .list();
```

---

## 六、缓存对照

### 6.1 Hibernate L2 Cache

```java
@jakarta.persistence.Entity
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class User { ... }
```

```properties
hibernate.cache.use_second_level_cache=true
hibernate.cache.region.factory_class=org.hibernate.cache.ehcache.EhCacheRegionFactory
```

### 6.2 HORM 缓存链

```java
@Entity
@Cached(levels = {CacheLevel.L1},
        policy = @CachePolicy(ttl = "30m", writeStrategy = WriteStrategy.THROUGH))
public class User extends Model<User> { ... }
```

```java
CachePolicy policy = CachePolicy.builder()
    .ttl(Duration.ofMinutes(30))
    .maxEntries(10_000)
    .build();
CaffeineCache l1 = new CaffeineCache("user-l1", policy);
DefaultCacheChain chain = new DefaultCacheChain(l1);
HormContext ctx = new HormContext(dataSourceProvider, chain);
HormContext.install(ctx);
```

HORM 缓存链特性：
- 多级组合（L1 + L2 + ...）
- 自动上层回填
- 事务提交后才写入（afterCommit 钩子）
- 批量加载（`findMany` 一次性查缺失 key）

---

## 七、常见迁移陷阱

### 7.1 POJO 无法直接复用

MyBatis 的 POJO 没有 `extends Model<T>`，无法使用 Active Record 方法。需要：
1. 为每个实体添加 `extends Model<T>`（CRTP）
2. 添加 HORM 注解（`@Entity`/`@Id`/`@Column`）
3. 删除原 MyBatis XML 映射文件

### 7.2 `@Transactional` 包名不同

Spring 的 `@Transactional` 是 `org.springframework.transaction.annotation.Transactional`，HORM 是 `com.holo.framework.horm.core.transaction.Transactional`。两者不可互换。

如需集成 Spring 事务管理器（`PlatformTransactionManager`），未来版本将提供适配器，目前请使用 HORM 独立事务。

### 7.3 关联字段必须是 `List`

HORM R5 规则要求所有关联字段（包括 `@BelongsTo` / `@HasOne`）必须是 `List<T>`。从 Hibernate 迁移时，需要把 `private User user;` 改为 `private List<User> users;`，并使用 `users.get(0)` 访问单个父实体。

### 7.4 查询构建器不可复用

HORM 的 `Query<T>` 是 mutable builder，每次查询必须新建：

```java
// ❌ 错误：q 被复用，状态会累积
Query<User> q = Model.query(User.class).where(UserQueryMeta.ID.gt(0L));
q.list();
q.where(UserQueryMeta.EMAIL.like("%@holo.dev")).list();

// ✅ 正确
Model.query(User.class).where(UserQueryMeta.ID.gt(0L)).list();
Model.query(User.class).where(UserQueryMeta.EMAIL.like("%@holo.dev")).list();
```

### 7.5 实体类不能是 `final`

APT 通过生成子类实现事务代理（M8.7）。`final` 类/方法会触发降级到 LambdaMetafactory 桥接（性能略低）。

### 7.6 静态方法调用风格

由于 Java 静态方法编译期绑定 + ByteBuddy 注入时序，业务源码中应使用 `Model.find(User.class, id)` 而非 `User.find(id)`。详见 [09-zero-reflection-optimization.md](./09-zero-reflection-optimization.md)。

### 7.7 多数据源事务隔离

HORM 每个数据源维护独立事务栈，**不支持 XA 分布式事务**。从 Hibernate 迁移时，若使用了 JTA 事务，需要拆分为独立事务或自行实现补偿机制。

### 7.8 字段扫描不递归

HORM 仅扫描直接声明的字段，不递归父类。继承层次较深的实体需要把字段都放在 `@Entity` 类自身（或考虑使用组合而非继承）。

---

## 八、迁移步骤建议

### 8.1 评估阶段

1. 列出所有实体类，确认每个类可以添加 `extends Model<T>`
2. 列出所有自定义 SQL（XML / 注解），评估是否可以用 HORM DSL 表达
3. 列出所有关联关系，转换 `@OneToMany` → `@HasMany` 等
4. 评估缓存需求，决定是否启用 HORM 缓存链

### 8.2 试点阶段

1. 选择一个独立模块（无复杂关联）作为试点
2. 添加 HORM 依赖 + APT 配置
3. 实体改造：添加 `@Entity` + `extends Model<T>`
4. 编译验证 APT 生成伴随类
5. 用 HORM 重写该模块的查询
6. 跑通单元测试 + 集成测试

### 8.3 全面迁移

1. 按依赖顺序逐个迁移实体（先无关联的，后有关联的）
2. 删除原 MyBatis XML / Hibernate 配置
3. 统一事务注解（替换为 HORM `@Transactional`）
4. 验证性能（参考 [13-benchmark-results.md](./13-benchmark-results.md)）
5. 配置缓存链（若需要）

### 8.4 混合共存期

迁移过程中可以共存：
- HORM 与 MyBatis 共享同一数据源
- HORM 实体与 MyBatis POJO 映射同一张表
- 事务各自独立（注意 HORM `@Transactional` 与 Spring `@Transactional` 不要叠加）

---

## 九、迁移检查清单

- [ ] JDK 升级到 17+
- [ ] Maven 升级到 3.6.3+
- [ ] IDE 启用 APT
- [ ] `pom.xml` 添加 `holo-horm-bom` + `holo-horm-spring-boot-starter`
- [ ] `maven-compiler-plugin` 添加 `annotationProcessorPaths`
- [ ] 每个实体添加 `@Entity` + `extends Model<T>`
- [ ] 删除 MyBatis XML / Hibernate `persistence.xml`
- [ ] 查询替换为 `Model.query(Class).where(...)` DSL
- [ ] 事务注解替换为 `com.holo.framework.horm.core.transaction.Transactional`
- [ ] 关联注解替换（`@OneToMany` → `@HasMany` 等）
- [ ] 配置 `application.yml`（数据源 + `holo.horm.*`）
- [ ] 单元测试 + 集成测试通过
- [ ] 性能基准对比（参考 [13-benchmark-results.md](./13-benchmark-results.md)）

---

## 参考

- [10-quickstart.md](./10-quickstart.md) — 5 分钟快速上手
- [11-user-guide.md](./11-user-guide.md) — 完整用户指南
- [05-active-record.md](./05-active-record.md) — Active Record 设计
- [09-zero-reflection-optimization.md](./09-zero-reflection-optimization.md) — 零反射原理
