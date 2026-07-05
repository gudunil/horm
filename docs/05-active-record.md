# HORM Active Record API 设计

> 本文档详述 HORM Active Record 风格的 API 设计、关联关系映射、查询 DSL、批量操作、数据验证、生命周期钩子等核心特性。

> **实现状态**：本文档为完整 API 愿景。其中 **Model 基类、基础 CRUD、类型安全 Query DSL、关联关系映射** 已在 M1-M3 实现；事务、验证、生命周期钩子、Scope、批量操作、软删除、多租户等特性计划在 M4-M9 实现。

---

## 一、设计目标

1. **直观易用**：对标 Rails ActiveRecord，`entity.save()` 即可持久化
2. **类型安全**：编译期生成的元模型类支持类型安全查询 DSL
3. **零反射**：所有 API 由 APT 生成的元数据驱动
4. **链式表达**：Query、Scope、Validation 均支持链式调用
5. **可组合**：关联、Scope、Callback 可自由组合

---

## 二、Model 基类

### 2.1 基础用法

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String email;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // getters/setters ...
}
```

### 2.2 Active Record 方法

```java
// 创建
User user = new User();
user.setEmail("a@b.com");
user.save();           // INSERT，主键回填
                       // 等价于 User.create(mapOf("email", "a@b.com"))

// 更新
user.setEmail("new@b.com");
user.update();         // UPDATE

// 保存（自动判断 INSERT 或 UPDATE）
user.persist();

// 删除
user.delete();         // DELETE

// 重新加载
user.reload();         // 从数据源重新读取

// 是否持久化
user.isPersisted();    // true if id != null

// 脏字段检查
user.isChanged("email");
user.changes();        // Map<String, Change>

// 回滚到原值
user.rollback();

// 转换为 Map
user.toMap();
user.toJson();         // Jackson 序列化

// 从 Map 构建
User u = User.fromMap(map);
```

### 2.3 静态查询方法

```java
// 按主键查找
User user = User.find(User.class, 1L);

// 按主键批量查找
List<User> users = User.find(User.class, List.of(1L, 2L, 3L));

// 查找全部
List<User> all = User.all(User.class);

// 查找第一条
User first = User.first(User.class);

// 查找最后一条
User last = User.last(User.class);

// 计数
long count = User.count(User.class);

// 是否存在
boolean exists = User.exists(User.class, 1L);

// 通过条件构建器查询
List<User> users = User.where(User.class, UserQueryMeta.EMAIL.eq("a@b.com")).all();

// 排序、分页
List<User> users = User.where(User.class, UserQueryMeta.STATUS.eq("active"))
    .orderBy(UserQueryMeta.CREATED_AT.desc())
    .limit(10)
    .offset(20)
    .all();

// 动态 finder（编译期生成的快捷方法）
User user = User.findByEmail("a@b.com");   // 通过 email 查找
List<User> users = User.findAllByStatus("active");  // 通过 status 查找全部
```

---

## 三、查询 DSL

### 3.1 类型安全条件

APT 为每个实体生成 `XxxQueryMeta` 类，提供类型安全的字段引用与条件构建：

```java
public final class UserQueryMeta {
    public static final LongField<User> ID = LongField.of(User.class, "id");
    public static final StringField<User> EMAIL = StringField.of(User.class, "email");
    public static final InstantField<User> CREATED_AT = InstantField.of(User.class, "createdAt");
    public static final EnumField<User, Status> STATUS = EnumField.of(User.class, "status", Status.class);
}
```

### 3.2 条件构建

```java
// 等于
UserQueryMeta.EMAIL.eq("a@b.com")

// 不等于
UserQueryMeta.EMAIL.ne("a@b.com")

// 大于 / 大于等于 / 小于 / 小于等于
UserQueryMeta.ID.gt(100L);
UserQueryMeta.ID.ge(100L);
UserQueryMeta.ID.lt(100L);
UserQueryMeta.ID.le(100L);

// 范围
UserQueryMeta.ID.between(1L, 100L);

// IN
UserQueryMeta.ID.in(List.of(1L, 2L, 3L));

// LIKE
UserQueryMeta.EMAIL.like("a%@b.com");
UserQueryMeta.EMAIL.startsWith("a");
UserQueryMeta.EMAIL.endsWith("@b.com");
UserQueryMeta.EMAIL.contains("b");

// NULL 检查
UserQueryMeta.EMAIL.isNull();
UserQueryMeta.EMAIL.notNull();

// 字符串专有
UserQueryMeta.EMAIL.equalsIgnoreCase("A@B.COM");

// 枚举
UserQueryMeta.STATUS.eq(Status.ACTIVE);

// JSON 路径（如果数据源支持）
UserQueryMeta.META_PATH.jsonPath("$.role").eq("admin");
```

### 3.3 条件组合

```java
// AND
Condition c1 = UserQueryMeta.EMAIL.eq("a@b.com")
    .and(UserQueryMeta.STATUS.eq(Status.ACTIVE));

// OR
Condition c2 = UserQueryMeta.EMAIL.eq("a@b.com")
    .or(UserQueryMeta.EMAIL.eq("c@d.com"));

// 嵌套
Condition c3 = UserQueryMeta.STATUS.eq(Status.ACTIVE)
    .and(
        UserQueryMeta.EMAIL.eq("a@b.com")
            .or(UserQueryMeta.EMAIL.eq("c@d.com"))
    );

// NOT
Condition c4 = UserQueryMeta.STATUS.eq(Status.ACTIVE)
    .and(Condition.not(UserQueryMeta.EMAIL.like("%test%")));
```

### 3.4 投影、排序、分组

```java
// 投影（仅查询部分字段）
List<User> partial = User.where(User.class, ...)
    .select(UserQueryMeta.ID, UserQueryMeta.EMAIL)
    .all();

// 排序
List<User> users = User.where(User.class, ...)
    .orderBy(UserQueryMeta.CREATED_AT.desc(),
             UserQueryMeta.EMAIL.asc())
    .all();

// 分组与聚合
List<GroupResult> result = User.where(User.class, ...)
    .groupBy(UserQueryMeta.STATUS)
    .having(UserQueryMeta.COUNT.gt(10))
    .select(UserQueryMeta.STATUS, UserQueryMeta.COUNT)
    .aggregate();

// 分页
Page<User> page = User.where(User.class, ...)
    .page(1, 20);   // 第 1 页，每页 20 条
// Page 包含 records, total, totalPages, currentPage
```

### 3.5 JOIN

```java
// 隐式 JOIN（通过关联关系自动 JOIN）
List<User> users = User.where(User.class, UserQueryMeta.ORDERS_COUNT.gt(0))
    .all();  // 通过 @HasMany 关联自动 LEFT JOIN

// 显式 JOIN
List<Tuple> result = User.query(User.class)
    .join(Order.class, UserQueryMeta.ID.eq(OrderQueryMeta.USER_ID))
    .where(OrderQueryMeta.AMOUNT.gt(100.0))
    .select(UserQueryMeta.EMAIL, OrderQueryMeta.AMOUNT)
    .all();
```

### 3.6 子查询

```java
// 子查询作为条件
SubQuery<Long> subQuery = User.query(User.class)
    .select(UserQueryMeta.ID)
    .where(UserQueryMeta.STATUS.eq(Status.ACTIVE))
    .asSubQuery();

List<Order> orders = Order.where(Order.class, OrderQueryMeta.USER_ID.in(subQuery)).all();
```

### 3.7 聚合查询

```java
long count = User.where(User.class, ...).count();
long sum = User.where(User.class, ...).sum(UserQueryMeta.BALANCE);
long avg = User.where(User.class, ...).avg(UserQueryMeta.BALANCE);
long max = User.where(User.class, ...).max(UserQueryMeta.CREATED_AT);
long min = User.where(User.class, ...).min(UserQueryMeta.CREATED_AT);
```

### 3.8 原生 SQL 逃生舱

复杂查询无法用 DSL 表达时，提供逃生舱：

```java
List<User> users = User.findByNativeSql(
    "SELECT * FROM users WHERE MATCH(email) AGAINST(?)",
    rs -> UserMapper.INSTANCE.map(Row.fromResultSet(rs)),
    "keyword"
);
```

注意：原生 SQL 路径仅适用于 SQL 数据源，且无法享受编译期类型检查。

---

## 四、关联关系

### 4.1 关联类型

| 类型 | 注解 | 示例 |
|------|------|------|
| BelongsTo | `@BelongsTo` | Order 属于 User |
| HasOne | `@HasOne` | User 有一个 Profile |
| HasMany | `@HasMany` | User 有多个 Order |
| HasAndBelongsToMany | `@HasAndBelongsToMany` | User 与 Role 多对多 |
| HasManyThrough | `@HasManyThrough` | User 通过 Order 间接关联 Product |

### 4.2 定义关联

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Id private Long id;
    private String email;

    @HasOne(foreignKey = "user_id")
    private Profile profile;

    @HasMany(foreignKey = "user_id")
    private List<Order> orders;

    @HasAndBelongsToMany(
        joinTable = "user_roles",
        foreignKey = "user_id",
        associationForeignKey = "role_id"
    )
    private List<Role> roles;
}

@Entity(table = "orders")
public class Order extends Model<Order> {
    @Id private Long id;
    private Long userId;
    private BigDecimal amount;

    @BelongsTo(foreignKey = "user_id", targetEntity = User.class)
    private User user;

    @HasManyThrough(
        through = OrderItem.class,
        throughForeignKey = "order_id",
        targetForeignKey = "product_id",
        targetEntity = Product.class
    )
    private List<Product> products;
}
```

### 4.3 关联加载策略

```java
// 默认懒加载（访问时触发查询）
User user = User.find(User.class, 1L);
List<Order> orders = user.getOrders();  // 此处触发 SELECT * FROM orders WHERE user_id = 1

// 预加载（避免 N+1）
List<User> users = User.all(User.class)
    .include(User::getOrders)  // 一次性加载所有 orders
    .all();
// SELECT * FROM users;
// SELECT * FROM orders WHERE user_id IN (1, 2, 3, ...);

// 多级预加载
List<User> users = User.all(User.class)
    .include(User::getOrders, Order::getItems)
    .all();

// 嵌套条件预加载
List<User> users = User.all(User.class)
    .include(builder -> builder
        .association(User::getOrders)
        .where(OrderQueryMeta.AMOUNT.gt(100.0)))
    .all();
```

### 4.4 关联操作

```java
User user = User.find(User.class, 1L);

// 添加关联
Order order = new Order();
order.setAmount(new BigDecimal("99.99"));
user.addOrder(order);  // 自动设置 order.userId = user.id 并保存

// 移除关联
user.removeOrder(order);  // 不删除 order，仅断开关联（设置 user_id = null）

// 删除关联
user.destroyOrder(order);  // DELETE order

// 清空关联
user.clearOrders();  // 所有关联 order 的 user_id = null

// 计数
long count = user.countOrders();

// 通过关联查询
List<Order> bigOrders = user.orders(o -> o.where(OrderQueryMeta.AMOUNT.gt(100.0)).all());
```

### 4.5 N+1 检测

启动时（开发/测试环境）开启 N+1 检测器，运行时统计同一请求内的关联查询次数，超过阈值（默认 5）打印警告：

```
WARN  h.orm.relation.nplus1 - Potential N+1 detected on User.orders
      Triggered 12 times within request /api/users
      Consider using .include(User::getOrders)
```

---

## 五、Scope（可复用查询片段）

### 5.1 定义 Scope

```java
@Entity(table = "users")
public class User extends Model<User> {

    public static Query<User> active(Query<User> q) {
        return q.where(UserQueryMeta.STATUS.eq(Status.ACTIVE));
    }

    public static Query<User> verified(Query<User> q) {
        return q.where(UserQueryMeta.EMAIL_VERIFIED.eq(true));
    }

    public static Query<User> recentlyRegistered(Query<User> q) {
        return q.where(UserQueryMeta.CREATED_AT.gt(Instant.now().minus(Duration.ofDays(7))));
    }
}
```

### 5.2 使用 Scope

```java
// 链式组合 Scope
List<User> users = User.scope(User::active)
    .scope(User::verified)
    .scope(User::recentlyRegistered)
    .orderBy(UserQueryMeta.CREATED_AT.desc())
    .limit(10)
    .all();

// 默认 Scope（@DefaultScope 注解，所有查询自动应用）
@DefaultScope(User::active)
public class User extends Model<User> { ... }

// 临时绕过默认 Scope
User.unscoped(User.class).all();
```

---

## 六、批量操作

### 6.1 批量插入

```java
List<User> users = List.of(
    User.create("email", "a@b.com"),
    User.create("email", "c@d.com"),
    User.create("email", "e@f.com")
);

List<User> saved = User.batchInsert(users);  // 单次 INSERT 多 VALUES
// 或分批
List<User> saved = User.batchInsert(users, 100);  // 每 100 条一批
```

### 6.2 批量更新

```java
// 同字段批量更新
int affected = User.where(User.class, UserQueryMeta.STATUS.eq("inactive"))
    .updateAll(Map.of("status", "deleted"));

// 实体批量更新（每实体可能字段不同）
User.batchUpdate(users, 100);
```

### 6.3 批量删除

```java
int affected = User.where(User.class, UserQueryMeta.STATUS.eq("deleted"))
    .deleteAll();
```

### 6.4 批量 upsert

```java
// 依据主键或唯一索引 upsert
User.upsert(users, conflictColumns = List.of("email"));
// 等价于：
// INSERT INTO users (...) VALUES (...)
// ON CONFLICT (email) DO UPDATE SET ...
```

### 6.5 流式处理

```java
// 流式读取，避免一次性加载到内存
try (Stream<User> stream = User.where(User.class, ...).stream()) {
    stream.forEach(u -> {
        // 处理每条记录
    });
}

// 流式批量处理
User.where(User.class, ...).forEachBatch(1000, batch -> {
    // 每批 1000 条
});
```

---

## 七、数据验证

### 7.1 声明式验证

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Id private Long id;

    @Column
    @NotBlank
    @Email
    @Length(max = 128)
    private String email;

    @Column
    @NotBlank
    @Length(min = 8, max = 64)
    @Pattern(regexp = "^(?=.*[A-Z])(?=.*[0-9]).*$", message = "密码必须包含大写字母和数字")
    private String password;

    @Column
    @Min(0)
    @Max(150)
    private Integer age;

    // 自定义验证
    @ValidateWith(UniqueEmailValidator.class)
    private String email;
}

public class UniqueEmailValidator implements Validator<String> {
    @Override
    public boolean isValid(String email, ValidationContext ctx) {
        return User.where(User.class, UserQueryMeta.EMAIL.eq(email)).count() == 0;
    }
}
```

### 7.2 验证 API

```java
User user = new User();
user.setEmail("invalid");

ValidationResult result = user.validate();
if (!result.isValid()) {
    result.errors().forEach(err -> {
        System.out.println(err.field() + ": " + err.message());
    });
}

// 保存前自动验证
try {
    user.save();  // 抛出 ValidationException
} catch (ValidationException e) {
    e.errors().forEach(System.out::println);
}

// 跳过验证（极端场景，慎用）
user.save(ValidationOption.SKIP);
```

### 7.3 验证上下文

支持不同场景的验证规则：

```java
public class User extends Model<User> {
    @NotBlank(groups = {Create.class, Update.class})
    private String email;

    @NotBlank(groups = {Create.class})
    private String password;  // 更新时不强制
}

user.validate(Update.class);  // 仅验证 Update 组
```

### 7.4 业务规则验证

```java
public class Order extends Model<Order> {
    @Override
    protected void beforeSave() {
        if (getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            addError("amount", "金额必须大于 0");
        }
        if (getItems().isEmpty()) {
            addError("items", "订单至少包含一个商品");
        }
    }
}
```

---

## 八、生命周期钩子

### 8.1 内置钩子

| 钩子 | 时机 |
|------|------|
| `beforeValidation` | 验证前 |
| `afterValidation` | 验证后 |
| `beforeSave` | 保存前（INSERT 或 UPDATE） |
| `afterSave` | 保存后 |
| `beforeCreate` | INSERT 前 |
| `afterCreate` | INSERT 后 |
| `beforeUpdate` | UPDATE 前 |
| `afterUpdate` | UPDATE 后 |
| `beforeDestroy` | DELETE 前 |
| `afterDestroy` | DELETE 后 |
| `afterInitialize` | 实例化后 |
| `afterFind` | 查询后 |

### 8.2 使用方式

#### 方式一：方法覆盖

```java
public class User extends Model<User> {
    @Override
    protected void beforeCreate() {
        setCreatedAt(Instant.now());
    }

    @Override
    protected void beforeUpdate() {
        setUpdatedAt(Instant.now());
    }

    @Override
    protected void afterDestroy() {
        // 删除关联数据
        getOrders().forEach(Order::delete);
    }
}
```

#### 方式二：注解

```java
public class User extends Model<User> {
    @BeforeCreate
    private void setTimestamps() {
        setCreatedAt(Instant.now());
    }

    @AfterDestroy
    private void cleanupRelations() {
        Order.where(Order.class, OrderQueryMeta.USER_ID.eq(getId())).deleteAll();
    }
}
```

#### 方式三：观察者（跨实体）

```java
public class UserObserver {
    @AfterCreate
    public void sendWelcomeEmail(User user) {
        emailService.sendWelcome(user.getEmail());
    }

    @AfterDestroy
    public void revokeTokens(User user) {
        tokenService.revokeByUserId(user.getId());
    }
}

// 注册
Horm.registerObserver(User.class, new UserObserver());
```

### 8.3 短路返回

钩子中抛出 `AbortException` 可中断操作：

```java
@Override
protected void beforeDestroy() {
    if (hasActiveOrders()) {
        throw new AbortException("用户存在未完成订单，无法删除");
    }
}
```

---

## 九、事务

### 9.1 编程式事务

```java
try (Transaction tx = Horm.transaction()) {
    User user = new User();
    user.setEmail("a@b.com");
    user.save();

    Order order = new Order();
    order.setUserId(user.getId());
    order.save();

    tx.commit();
}
```

### 9.2 声明式事务（Spring）

```java
@Service
public class UserService {
    @Transactional
    public void createUserWithOrder(UserDto dto) {
        User user = ...;
        user.save();
        Order order = ...;
        order.save();
    }
}
```

### 9.3 事务隔离级别

```java
try (Transaction tx = Horm.transaction(TransactionDefinition.builder()
        .isolation(IsolationLevel.READ_COMMITTED)
        .propagation(Propagation.REQUIRED)
        .timeout(Duration.ofSeconds(30))
        .readOnly(false)
        .build())) {
    // ...
}
```

### 9.4 乐观锁

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Id private Long id;

    @Version
    private Long version;

    // ...
}

// 更新时自动加入 version 检查
// UPDATE users SET email = ?, version = version + 1
// WHERE id = ? AND version = ?
User user = User.find(User.class, 1L);
user.setEmail("new@b.com");
user.update();  // 如果 version 不匹配抛出 OptimisticLockException
```

### 9.5 悲观锁

```java
// SELECT ... FOR UPDATE
User user = User.where(User.class, UserQueryMeta.ID.eq(1L))
    .forUpdate()
    .one()
    .orElseThrow();
```

---

## 十、回调链顺序

完整 save 流程的钩子顺序：

```
1. beforeValidation
2. validation（自动调用 Validator）
3. afterValidation
   │
   ├── 如果验证失败 → 抛出 ValidationException，终止
   │
4. beforeSave
5. beforeCreate / beforeUpdate
   │
6. 缓存查询（如果是 UPDATE，查询原值用于 diff）
   │
7. Mapper.toRow
   │
8. 事务开始（如未在事务中）
   │
9. 数据源执行（INSERT / UPDATE）
   │
10. 主键回填（如果是 INSERT）
   │
11. 事务提交（如果 HORM 管理事务）
   │
12. 缓存更新 / 失效
   │
13. afterCreate / afterUpdate
14. afterSave
   │
15. 观察者回调
   │
16. 事件发布（如启用 ApplicationEventPublisher）
```

---

## 十一、序列化

### 11.1 JSON 序列化

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Id private Long id;

    @JsonProperty("email_address")
    @Column(name = "email")
    private String email;

    @JsonIgnore
    private String password;  // 不序列化

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Instant createdAt;
}

// 输出
// {"id": 1, "email_address": "a@b.com", "createdAt": "2026-07-04 10:00:00"}
```

### 11.2 DTO 转换

```java
@Mapping(target = UserDto.class)
public class User extends Model<User> { ... }

// 自动通过 MapStruct 转换
UserDto dto = user.to(UserDto.class);
List<UserDto> dtos = User.to(users, UserDto.class);
```

---

## 十二、示例：完整 CRUD 场景

```java
// 1. 创建用户
User user = new User();
user.setEmail("a@b.com");
user.setPassword("SecurePass123");
user.setStatus(Status.ACTIVE);
user.save();
// → INSERT INTO users (email, password, status) VALUES (?, ?, ?)

// 2. 查询用户
User found = User.findByEmail("a@b.com");
// → SELECT * FROM users WHERE email = ? LIMIT 1

// 3. 更新
found.setStatus(Status.INACTIVE);
found.update();
// → UPDATE users SET status = ? WHERE id = ?

// 4. 关联查询（预加载）
List<User> activeUsers = User.scope(User::active)
    .include(User::getOrders)
    .all();
// → SELECT * FROM users WHERE status = 'ACTIVE'
// → SELECT * FROM orders WHERE user_id IN (?, ?, ...)

// 5. 复杂查询
Page<User> page = User.scope(User::verified)
    .where(UserQueryMeta.CREATED_AT.between(
        Instant.now().minus(Duration.ofDays(30)),
        Instant.now()))
    .orderBy(UserQueryMeta.CREATED_AT.desc())
    .page(1, 20);

// 6. 批量操作
User.batchUpdate(activeUsers.stream()
    .peek(u -> u.setStatus(Status.INACTIVE))
    .collect(Collectors.toList()));

// 7. 删除
found.delete();
// → DELETE FROM users WHERE id = ?
```

---

## 十三、与 Rails ActiveRecord 对比

| 特性 | Rails AR | HORM |
|------|---------|------|
| Active Record 基类 | ✓ | ✓ |
| 关联（belongs_to / has_many） | ✓ | ✓ |
| 验证（validates） | ✓ | ✓ |
| 回调（before_save 等） | ✓ | ✓ |
| Scope | ✓ | ✓ |
| 委托（delegation） | ✓ | ✓ |
| 序列化（serialize） | ✓ | ✓ |
| 多态关联 | ✓ | 计划中 |
| Single Table Inheritance | ✓ | ✓ |
| Enum | ✓ | ✓ |
| Migration | ✓ | ✓（独立模块） |
| 零反射 | ✗ | ✓ |
| 类型安全 DSL | ✗ | ✓ |
| 多数据源 | ✗ | ✓ |
| AOT 兼容 | N/A | ✓ |

---

## 十四、参考资源

- [Rails ActiveRecord 文档](https://guides.rubyonrails.org/active_record_basics.html)
- [jOOQ 类型安全 DSL](https://www.jooq.org/doc/latest/manual/sql-building/column-expressions/)
- [Jakarta Validation 规范](https://beanvalidation.org/)
- [MapStruct](https://mapstruct.org/)
