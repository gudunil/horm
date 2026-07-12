# HORM - Holo Object-Relational Mapping

> Holo 组织自研 ORM 框架，采用 Active Record 风格、零反射、多数据源适配、可组合缓存链设计。

## 核心特性

- **Active Record 风格 API**：对标 Rails ActiveRecord，`entity.save()` 直观易用
- **零反射设计**：基于 APT 编译期生成元数据与 Mapper，运行时无反射，性能逼近手写 JDBC
- **多数据源适配**：统一抽象 SQL/NoSQL/REST/File 数据源，支持自定义 SPI 扩展
- **可组合缓存链**：L1（Caffeine）/ L2（Redis）/ L3（远端）多级缓存，支持 LRU/TTL/写穿透等策略任意组合
- **类型安全 DSL**：编译期生成的元模型类提供类型安全查询 DSL，编译期错误检查
- **AOT 兼容**：无运行时字节码生成，兼容 GraalVM Native Image
- **生态集成**：与 Spring Boot 深度集成，未来支持 Quarkus / Helidon

## 快速开始

### 添加依赖

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

### 启用 APT 处理器

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

### 定义实体

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String email;

    @Column(name = "created_at")
    private Instant createdAt;

    // getters/setters ...
}
```

### 使用

```java
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.query.Order;

// 创建
User user = new User();
user.setEmail("a@b.com");
user.save();                      // INSERT，主键回填

// 查询
User found = Model.find(User.class, 1L);

// 条件查询（类型安全 DSL）
List<User> actives = Model.query(User.class)
    .where(UserQueryMeta.EMAIL.like("%@b.com"))
    .orderBy(UserQueryMeta.CREATED_AT, Order.DESC)
    .limit(10)
    .list();

// 关联预加载（Eager JOIN）
List<User> users = Model.query(User.class)
    .fetch(UserQueryMeta.ORDERS)
    .list();
```

> **静态方法调用规范**：业务源码中应使用 `Model.find(User.class, id)` 而非 `User.find(id)`，避免 Java 静态方法编译期绑定到 fallback。详见 [docs/09-zero-reflection-optimization.md](./docs/09-zero-reflection-optimization.md)。

## 模块结构

```
holo-horm/
├── holo-horm-bom/                       # HORM 模块版本清单 BOM
├── holo-horm-meta/                      # 注解定义 + APT 处理器
├── holo-horm-core/                      # 核心 API（Model、Query、Repository、Transaction）
├── holo-horm-datasource/                # 数据源 SPI 与参考实现
├── holo-horm-cache/                     # 缓存链系统
├── holo-horm-codegen/                   # 代码生成工具 CLI
├── holo-horm-migration/                 # 数据库迁移工具
├── holo-horm-spring-boot-starter/       # Spring Boot 自动装配
├── holo-horm-examples/                  # 示例代码
├── holo-horm-benchmark/                 # JMH 性能基准
└── docs/                                # 技术文档
```

## 文档

完整文档位于 [`docs/`](./docs/README.md)，包括：

- [快速上手](./docs/10-quickstart.md) — 5 分钟跑通
- [用户指南](./docs/11-user-guide.md) — 完整 API 参考
- [迁移指南](./docs/12-migration-guide.md) — 从 MyBatis/Hibernate 迁移
- [性能基准报告](./docs/13-benchmark-results.md) — JMH 基准设计
- [AOT / GraalVM](./docs/14-aot-graalvm.md) — Native Image 兼容性
- [技术调研报告](./docs/00-research.md)
- [架构设计](./docs/01-architecture.md)
- [零反射实现方案](./docs/02-zero-reflection.md)
- [多数据源适配](./docs/03-multi-datasource.md)
- [缓存链系统](./docs/04-cache-chain.md)
- [Active Record API](./docs/05-active-record.md)
- [扩展特性](./docs/06-extension-features.md)
- [性能与安全](./docs/07-performance-security.md)
- [方言适配](./docs/08-dialect-adaptation.md)
- [零反射优化（M8.7）](./docs/09-zero-reflection-optimization.md)
- [架构图源文件](./docs/diagrams/)

## 版本

- **当前版本**：1.0.0-SNAPSHOT
- **状态**：M1-M8.7 已完成，M9 性能基准与文档完善进行中
- **里程碑**：M1-M8.7 ✅ / M9 🚧
- **预计 GA**：2026 Q4

详见 [开发计划与里程碑](./docs/07-performance-security.md#六开发计划与里程碑)。

## 构建与校验

```bash
# 安装依赖
mvn -f ../holo-parent-pom/pom.xml install
mvn -f ../holo-dependency-bom/pom.xml install

# 校验聚合 POM
mvn -f pom.xml validate -Pskip-enforcer

# 编译
mvn -f pom.xml compile -Pskip-enforcer
```

## 许可证

Apache License, Version 2.0
