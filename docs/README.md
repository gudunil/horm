# HORM 文档导航

> HORM（Holo Object-Relational Mapping）是 Holo 组织自研的 ORM 框架，采用 Active Record 风格、零反射、多数据源适配、可组合缓存链设计。

---

## 文档总览

| # | 文档 | 内容 | 适用读者 |
|---|------|------|---------|
| 00 | [技术调研报告](./00-research.md) | 主流 ORM 框架对比、零反射方案、多数据源模式、缓存链设计、非数据库数据源适配 | 架构师、设计决策者 |
| 01 | [架构设计](./01-architecture.md) | 整体架构、模块划分、依赖关系、核心流程、关键设计决策 | 全体 |
| 02 | [零反射实现方案](./02-zero-reflection.md) | APT 元数据生成、Mapper 生成、字段访问器、降级机制、AOT 兼容 | 核心开发者 |
| 03 | [多数据源适配](./03-multi-datasource.md) | DataSource SPI、SQL/NoSQL/REST/File 实现、能力声明、自定义扩展 | 核心开发者、扩展开发者 |
| 04 | [缓存链系统](./04-cache-chain.md) | 多级缓存链、策略组合、键设计、一致性保证、性能优化 | 全体 |
| 05 | [Active Record API](./05-active-record.md) | Model 基类、查询 DSL、关联关系、Scope、批量操作、验证、生命周期钩子 | 业务开发者 |
| 06 | [扩展特性](./06-extension-features.md) | 类型安全、事务并发、迁移工具、日志监控、异常机制、代码生成、框架集成 | 全体 |
| 07 | [性能与安全](./07-performance-security.md) | 性能优化、安全考量、AOT 兼容、容量规划、开发计划与里程碑 | 架构师、运维 |
| - | [架构图源文件](./diagrams/) | PlantUML 架构图源文件 | 全体 |

---

## 快速导航

### 我想要...

- **了解整体设计** → [01-architecture.md](./01-architecture.md)
- **了解技术选型依据** → [00-research.md](./00-research.md)
- **使用 Active Record API** → [05-active-record.md](./05-active-record.md)
- **理解零反射实现** → [02-zero-reflection.md](./02-zero-reflection.md)
- **接入自定义数据源** → [03-multi-datasource.md](./03-multi-datasource.md) 第六章
- **配置多级缓存** → [04-cache-chain.md](./04-cache-chain.md) 第九章
- **使用事务** → [06-extension-features.md](./06-extension-features.md) 第二章
- **数据库迁移** → [06-extension-features.md](./06-extension-features.md) 第三章
- **了解开发计划** → [07-performance-security.md](./07-performance-security.md) 第六章

### 按角色查看

| 角色 | 推荐阅读顺序 |
|------|------------|
| 架构师 | 00 → 01 → 02 → 03 → 04 → 07 |
| 核心开发者 | 01 → 02 → 03 → 04 → 06 |
| 业务开发者 | 01 → 05 → 06（事务章节） → 04（缓存使用章节） |
| 扩展开发者 | 03 → 02 → 06（扩展点总览） |
| 运维 | 01（架构） → 04（缓存监控） → 06（日志监控） → 07（容量规划） |

---

## 项目结构

```
holo-horm/
├── pom.xml                              # 聚合 pom，继承 holo-parent-pom
├── README.md                            # 项目说明
├── docs/                                # 文档目录
│   ├── README.md                        # 本文件
│   ├── 00-research.md                   # 技术调研
│   ├── 01-architecture.md               # 架构设计
│   ├── 02-zero-reflection.md            # 零反射实现
│   ├── 03-multi-datasource.md           # 多数据源适配
│   ├── 04-cache-chain.md                # 缓存链系统
│   ├── 05-active-record.md              # Active Record API
│   ├── 06-extension-features.md         # 扩展特性
│   ├── 07-performance-security.md       # 性能与安全
│   └── diagrams/                        # 架构图源文件
│       ├── architecture.puml            # 整体架构图
│       ├── module-dependencies.puml     # 模块依赖图
│       ├── save-flow.puml               # save 流程图
│       ├── cache-chain.puml             # 缓存链流程图
│       └── datasource-spi.puml          # 数据源 SPI 类图
│
├── holo-horm-bom/                       # HORM 模块版本清单 BOM
├── holo-horm-meta/                      # 注解定义 + APT 处理器
├── holo-horm-core/                      # 核心 API（Model、Query、Repository、Transaction）
├── holo-horm-datasource/                # 数据源 SPI 与参考实现
├── holo-horm-cache/                     # 缓存链系统
├── holo-horm-codegen/                   # 代码生成工具 CLI
├── holo-horm-migration/                 # 数据库迁移工具
├── holo-horm-spring-boot-starter/       # Spring Boot 自动装配
├── holo-horm-examples/                  # 示例代码
└── holo-horm-benchmark/                 # JMH 性能基准
```

---

## 快速开始

### 1. 添加依赖

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

### 2. 启用 APT 处理器

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

### 3. 定义实体

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

### 4. 使用

```java
// 创建
User user = new User();
user.setEmail("a@b.com");
user.save();

// 查询
User found = User.find(User.class, 1L);

// 条件查询
List<User> actives = User.where(User.class, UserQueryMeta.EMAIL.like("%@b.com"))
    .orderBy(UserQueryMeta.CREATED_AT.desc())
    .all();
```

详细用法见 [05-active-record.md](./05-active-record.md)。

---

## 版本与状态

- **当前版本**：1.0.0-SNAPSHOT
- **状态**：开发中（M3 已完成）
- **已发布 Tag**：v1.0.0-M1 / v1.0.0-M2 / v1.0.0-M3
- **预计 GA**：2027-07

详见 [07-performance-security.md 第六章](./07-performance-security.md#六开发计划与里程碑)。

---

## 反馈与贡献

- 提交 issue：[GitHub Issues](https://github.com/holo/holo-horm/issues)
- 提交 PR：[GitHub Pull Requests](https://github.com/holo/holo-horm/pulls)
- 邮件讨论：holo-framework@holo.example.com
