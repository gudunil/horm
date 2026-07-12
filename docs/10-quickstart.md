# HORM 5 分钟快速上手

> 本文帮助你以最短时间跑通 HORM 的核心流程：添加依赖、定义实体、CRUD、条件查询、Spring Boot 集成。

> **前置要求**：JDK 17+、Maven 3.6.3+、IDE 启用 APT（IntelliJ IDEA 勾选 `Build → Execution → Deployment → Compiler → Annotation Processors → Enable annotation processing`）。

---

## 1. 添加依赖

在项目 `pom.xml` 中导入 HORM BOM 并引入 Spring Boot Starter：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.holo.framework</groupId>
            <artifactId>holo-horm-bom</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.holo.framework</groupId>
        <artifactId>holo-horm-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

若不使用 Spring Boot，可直接引入 `holo-horm-core`：

```xml
<dependency>
    <groupId>com.holo.framework</groupId>
    <artifactId>holo-horm-core</artifactId>
</dependency>
```

## 2. 启用 APT 处理器

HORM 通过编译期注解处理器（APT）生成元数据与 Mapper，必须在 `maven-compiler-plugin` 中显式声明：

```xml
<build>
    <plugins>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.holo.framework</groupId>
                        <artifactId>holo-horm-meta</artifactId>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

编译时会在 `<实体包>.generated` 下生成 `UserMeta`、`UserMapper`、`UserQueryMeta` 三个伴随类。

## 3. 定义实体

```java
package com.example.entity;

import java.time.Instant;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.Id;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.core.Model;

@Entity(table = "users")
public class User extends Model<User> {

    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String email;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

约定：
- 表名优先级：`@Table.name` > `@Entity.table` > 类名 snake_case
- 列名优先级：`@Column.name` > 字段名 snake_case
- 主键策略：`@GeneratedValue` > `@Id.strategy`
- 继承 `Model<T>`（CRTP 模式，泛型参数为自身类型）

## 4. CRUD 操作

```java
import com.holo.framework.horm.core.Model;

// INSERT
User user = new User();
user.setEmail("alice@holo.dev");
user.setCreatedAt(Instant.now());
user.save();                    // INSERT，主键回填到 user.id

// SELECT BY ID
User found = Model.find(User.class, 1L);

// UPDATE
found.setEmail("alice-new@holo.dev");
found.save();                   // UPDATE

// DELETE
found.delete();                 // DELETE FROM users WHERE id = ?
```

> **注意**：由于 Java 静态方法编译期绑定，建议在工具/服务类中使用 `Model.find(User.class, id)` 风格而非 `User.find(id)`。后者仅在 ByteBuddy 注入完成后重新编译的源码中可用。详见 [09-zero-reflection-optimization.md](./09-zero-reflection-optimization.md)。

## 5. 条件查询（类型安全 DSL）

APT 生成的 `UserQueryMeta` 提供类型安全的字段常量，配合 `Model.query()` 链式构造查询：

```java
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.query.Order;
import com.example.entity.generated.UserQueryMeta;
import java.util.List;

List<User> actives = Model.query(User.class)
    .where(UserQueryMeta.EMAIL.like("%@holo.dev"))
    .orderBy(UserQueryMeta.CREATED_AT, Order.DESC)
    .limit(10)
    .list();

long count = Model.query(User.class)
    .where(UserQueryMeta.EMAIL.like("%@holo.dev"))
    .count();
```

支持的 `ComparableField` 操作（按字段类型自动启用）：
- `eq` / `ne` — 等值/不等
- `gt` / `lt` / `ge` / `le` — 比较
- `between(a, b)` — 范围
- `like(pattern)` — 模糊匹配
- `in(values)` — IN 列表
- `isNull()` / `notNull()` — 空值判断

## 6. Spring Boot 集成

`application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/demo
    username: root
    password: secret
    driver-class-name: com.mysql.cj.jdbc.Driver

holo:
  horm:
    migration:
      auto-on-startup: true     # 启动时自动执行 Flyway 迁移
```

`@EnableHorm` 启用事务代理：

```java
@SpringBootApplication
@EnableHorm
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}

@Service
public class UserService {
    @Transactional
    public void register(String email) {
        User user = new User();
        user.setEmail(email);
        user.setCreatedAt(Instant.now());
        user.save();
    }
}
```

`@Transactional` 由 APT 在编译期生成代理子类（M8.7 实现），运行时零反射。

## 7. 下一步

| 想了解 | 阅读 |
|--------|------|
| Active Record 完整 API | [11-user-guide.md](./11-user-guide.md) |
| 关联关系（@BelongsTo / @HasMany / ...） | [05-active-record.md](./05-active-record.md) |
| 缓存链配置 | [04-cache-chain.md](./04-cache-chain.md) |
| 多数据源路由 | [03-multi-datasource.md](./03-multi-datasource.md) |
| 从 MyBatis/Hibernate 迁移 | [12-migration-guide.md](./12-migration-guide.md) |
| 性能基准 | [13-benchmark-results.md](./13-benchmark-results.md) |
| GraalVM Native Image | [14-aot-graalvm.md](./14-aot-graalvm.md) |

---

## 常见问题

**Q: 编译时报"找不到 UserQueryMeta"？**
A: 先执行 `mvn compile` 让 APT 生成伴随类；IDE 中勾选 APT 选项并 rebuild。

**Q: `User.find(id)` 抛 UnsupportedOperationException？**
A: 这是 ByteBuddy 注入时序问题。改用 `Model.find(User.class, id)`（详见 [09-zero-reflection-optimization.md](./09-zero-reflection-optimization.md#5-activer record-静态助手注入时序)）。

**Q: H2 内存库测试如何配置？**
A: `jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1`，与 HORM 默认 MySQL 方言兼容。
