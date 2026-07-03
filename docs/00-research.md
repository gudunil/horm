# HORM 技术调研报告

> 本文档为 HORM 框架设计前的技术调研输出，涵盖主流 ORM 框架对比、零反射实现可行性、多数据源适配模式、缓存链设计模式、非数据库数据源适配方案，作为后续架构设计的依据。

---

## 一、主流 ORM 框架架构与核心功能分析

### 1.1 ORM 框架谱系

```
                       ┌─────────────────────── ORM 框架谱系 ───────────────────────┐
                       │                                                              │
   ┌─────────── Active Record 风格 ──────────┐  ┌─────────── Data Mapper 风格 ──────────┐
   │                                          │  │                                       │
   │  Rails ActiveRecord (Ruby)              │  │  Hibernate / JPA (Java)               │
   │  EBean (Java)                           │  │  MyBatis (Java)                       │
   │  jOOQ (Java, DSL)                       │  │  Spring Data JDBC (Java)              │
   │  Laravel Eloquent (PHP)                 │  │  Dapper (C#, micro-ORM)               │
   │  GORM (Go)                              │  │  Entity Framework (C#)                │
   │  Django ORM (Python)                    │  │  SQLAlchemy (Python)                  │
   │                                          │  │                                       │
   └──────────────────────────────────────────┘  └───────────────────────────────────────┘
```

### 1.2 Active Record（Rails）

**核心思想**：实体对象本身承载持久化能力，`entity.save()` / `entity.update()` / `entity.delete()` 直接操作数据库。

**架构特点**：
- 实体继承 `ActiveRecord::Base`，自动获得 CRUD 方法
- 通过 `belongs_to` / `has_many` / `has_one` / `has_and_belongs_to_many` 声明关联
- Convention over Configuration：表名 = 类名复数小写，主键 = `id`
- 动态 finder：`User.find_by_email_and_status(email, status)`
- 作用域（Scope）：可组合的查询片段
- Migration：Ruby DSL 描述 DDL 变更
- Callback：`before_save` / `after_create` 等生命周期钩子
- Validation：声明式校验 `validates :email, presence: true`

**优势**：开发效率高，API 直观，适合 CRUD 密集型应用
**劣势**：实体承载过多职责（违反 SRP），复杂查询性能调优困难，反射开销大（Ruby 动态语言影响较小，Java 上是关键问题）

### 1.3 Hibernate / JPA

**核心思想**：实体为纯 POJO，通过 `EntityManager` 管理持久化，DTO 与实体分离。

**架构特点**：
- 实体标注 `@Entity`、`@Table`、`@Column`，通过反射读取元数据
- Session / EntityManager 作为持久化上下文，跟踪实体脏字段
- 一级缓存（Session）+ 二级缓存（SessionFactory，可插拔）
- HQL/JPQL 跨方言查询语言
- 延迟加载通过代理（CGLIB / ByteBuddy 字节码增强）实现
- N+1 问题、Session 范围、Open Session in View 等典型陷阱

**优势**：标准化（JPA 规范），关联映射强大，跨方言
**劣势**：反射与代理开销大，复杂查询生成 SQL 难控制，学习曲线陡峭，运行时性能调优困难

### 1.4 MyBatis

**核心思想**：SQL Mapper，将 SQL 与对象映射解耦，开发者写 SQL，框架做对象映射。

**架构特点**：
- XML 或注解描述 SQL + ResultMap
- `SqlSession` 执行 SQL，自动映射结果集到对象
- 动态 SQL（`<if>` `<foreach>` `<choose>`）
- 一级缓存（Session）+ 二级缓存（Mapper 级别，可插拔）
- 插件机制（Interceptor，可拦截 SQL 执行）
- 几乎零反射（启动时解析 XML，运行时仅 ResultMap 映射）

**优势**：SQL 可控，性能好，适合复杂查询场景，学习成本低
**劣势**：API 偏底层，Active Record 风格缺失，代码量较大，DTO 与 SQL 强耦合

### 1.5 jOOQ

**核心思想**：通过代码生成器，从数据库 schema 生成类型安全的 DSL，以 Java 代码写 SQL。

**架构特点**：
- 编译期/构建期生成 `Tables`、`Records`、`POJOs` 等元模型类
- 查询以链式 DSL 构建：`dsl.selectFrom(USER).where(USER.ID.eq(1)).fetchOne()`
- 生成的代码即元数据，运行时零反射
- 支持标准 SQL、方言特性、DDL、存储过程
- Active Record 风格的 `UpdatableRecord`（实体可自己 `store()` / `delete()`）

**优势**：类型安全、零反射、SQL 表达力强、编译期错误检查
**劣势**：代码生成依赖 schema，复杂业务映射需手写，商业版才有高级特性（如 SQL 转换）

### 1.6 Spring Data JDBC

**核心思想**：简化版 ORM，无 Session、无代理、无延迟加载，实体为纯 POJO。

**架构特点**：
- 实体标注 `@Table` / `@Id`，通过反射读取（但仅启动时一次）
- Repository 接口自动实现，方法名派生查询
- 无代理：关联对象直接聚合加载（aggregate root 概念）
- 简单直接，避免 Hibernate 复杂性

**优势**：简单、性能尚可、与 Spring 生态深度集成
**劣势**：功能相对简单，复杂场景需补充

### 1.7 对比矩阵

| 框架 | API 风格 | 元数据获取 | 运行时反射 | 关联加载 | 缓存 | 跨数据源 | 学习成本 |
|------|---------|----------|----------|---------|------|---------|---------|
| Rails ActiveRecord | Active Record | 约定+反射 | 高 | 代理/懒加载 | 内置 | 否 | 低 |
| Hibernate/JPA | Data Mapper | 注解+反射 | 高 | 代理+懒加载 | L1+L2 | 否 | 高 |
| MyBatis | SQL Mapper | XML+启动反射 | 极低 | 手动 join | L1+L2 | 否 | 低 |
| jOOQ | DSL+Active Record | 代码生成 | **零** | 手动 | 弱 | 否 | 中 |
| Spring Data JDBC | Repository | 注解+反射 | 中 | 聚合根加载 | 弱 | 否 | 低 |

### 1.8 HORM 的定位

HORM 选择 **Active Record + jOOQ 式零反射 + MyBatis 式可插拔缓存 + 多数据源 SPI** 的混合定位：
- API 体验对标 Rails Active Record（`user.save()` 直观）
- 元数据通过 APT 编译期生成，运行时零反射（对标 jOOQ）
- 缓存链可插拔组合（对标 MyBatis 二级缓存 + 业务自定义）
- 数据源通过 SPI 抽象，支持 SQL/NoSQL/REST/文件等（独有特性）

---

## 二、零反射 ORM 实现方案调研

### 2.1 反射开销的实证

Java 反射的主要开销：
- `Method#invoke`：JIT 优化后约比直接调用慢 5-10 倍
- `Field#get/set`：JIT 优化后约慢 3-8 倍
- `Class#forName` + 元数据扫描：启动开销，单次 1-10ms 不等
- `MethodHandle`：接近原生调用，但需要缓存

主流 ORM 反射使用情况：
- Hibernate：`@Entity` 元数据反射读取（启动时一次性），运行时通过 ByteBuddy 生成代理避免反射调用 setter/getter
- MyBatis：ResultMap 反射实例化对象，setter 反射调用（每行结果集都触发）
- jOOQ：**零反射**，所有元数据通过代码生成器生成 Java 类

### 2.2 零反射的实现路径

#### 方案 A：Annotation Processor (APT) 生成元数据（推荐）

**原理**：编译期扫描 `@Entity` 注解，生成对应的元数据类和 Mapper 类，运行时直接调用生成类的方法，无反射。

**生成内容示例**：
```java
// 用户实体
@Entity(table = "users")
public class User {
    @Id private Long id;
    @Column private String email;
    @Column(name = "created_at") private Instant createdAt;
}

// 编译期生成（com/holo/framework/horm/generated/UserMeta.java）
public final class UserMeta {
    public static final String TABLE = "users";
    public static final FieldDescriptor<Long> ID = FieldDescriptor.of("id", Long.class, true);
    public static final FieldDescriptor<String> EMAIL = FieldDescriptor.of("email", String.class, false);
    public static final FieldDescriptor<Instant> CREATED_AT = FieldDescriptor.of("created_at", Instant.class, false);

    public static Mapper<User> mapper() {
        return UserMapper.INSTANCE;
    }
}

// 编译期生成（UserMapper.java）
final class UserMapper implements Mapper<User> {
    static final UserMapper INSTANCE = new UserMapper();

    @Override
    public User map(Row row) {
        User u = new User();
        u.setId(row.getLong("id"));
        u.setEmail(row.getString("email"));
        u.setCreatedAt(row.getInstant("created_at"));
        return u;
    }

    @Override
    public Row toRow(User u) {
        Row row = Row.create();
        row.setLong("id", u.getId());
        row.setString("email", u.getEmail());
        row.setInstant("created_at", u.getCreatedAt());
        return row;
    }
}
```

**优势**：完全零反射、类型安全、编译期错误检查、IDE 可跳转
**劣势**：编译期生成增加构建时间（通常 1-3 秒）、调试时需理解生成代码

#### 方案 B：Lambda Metafactory 运行时生成

**原理**：运行时通过 `LambdaMetafactory` 将反射 `MethodHandle` 转换为函数式接口实例，调用接近原生速度。

**示例**：
```java
MethodHandle handle = lookup.unreflect(setterMethod);
BiConsumer<Object, Object> setter = (BiConsumer<Object, Object>) LambdaMetafactory.metafactory(
    lookup, "accept", ...).getTarget().invokeExact();
```

**优势**：运行时生成，无编译期依赖
**劣势**：首次生成有开销、调试困难、不是严格零反射

#### 方案 C：字节码生成（ByteBuddy / ASM）

**原理**：运行时生成实现接口的字节码类，方法体直接调用目标方法。

**优势**：运行时生成，性能接近原生
**劣势**：复杂、运行时生成类增加 ClassLoader 压力、与 AOT（GraalVM Native Image）不兼容

### 2.3 HORM 的选择：方案 A（APT）+ 方案 B 降级

**主路径**：APT 编译期生成 `EntityMeta`、`Mapper`、`FieldAccessor`，运行时零反射
**降级路径**：对动态代理场景（如非 HORM 实体），使用 `LambdaMetafactory` 生成访问器，性能接近原生
**禁用**：任何运行时反射的字段读写

### 2.4 业界参考实现

- **jOOQ**：构建期代码生成，全类型安全 DSL
- **Dagger**：编译期依赖注入生成代码，零反射
- **Immutables**：编译期生成不可变值类
- **Record Builder**：为 Java `record` 生成 Builder
- **Lombok**：通过 AST 修改生成代码（非标准 APT，但思路类似）

HORM 的 APT 实现复用 JavaPoet 库（Square 出品，业界标准），与 Lombok、Dagger 同源技术栈。

---

## 三、多数据源适配模式调研

### 3.1 设计模式选项

#### 模式 A：统一 Connection 抽象

**原理**：定义 `Connection` 接口，所有数据源实现该接口。
```java
public interface Connection {
    Row execute(Query query);
    BatchResult executeBatch(List<Query> queries);
    void commit();
    void rollback();
}
```

**优势**：调用方代码统一
**劣势**：抽象层次被迫降低（SQL 与 KV 的差异难以用统一接口表达）

#### 模式 B：方言 + Session 双层

**原理**：底层 `Session` 抽象数据源特性，上层 `Dialect` 处理语言差异（SQL 方言、NoSQL 查询语言）。

**优势**：分层清晰
**劣势**：NoSQL 与 SQL 差异过大时，方言抽象难以涵盖

#### 模式 C：SPI 适配器 + 通用 Query 模型（HORM 选择）

**原理**：
- `DataSource` SPI 定义数据源生命周期与执行入口
- 通用 `Query` 模型描述意图（CRUD + 条件 + 投影 + 排序 + 分页）
- 各数据源将 `Query` 翻译为自己的原生语句（SQL / MongoDB 查询 / Redis 命令 / HTTP 请求）

```
业务代码 (Active Record)
      │
      ▼
Query 模型（条件、投影、排序、分页、关联）
      │
      ▼  ┌─────────────── SPI 翻译层 ───────────────┐
       │  │                                            │
       │  ├── SqlDataSource ──── SqlTranslator ─── JDBC
       │  ├── MongoDataSource ── MongoTranslator ─ Mongo Driver
       │  ├── RedisDataSource ── RedisTranslator ─ Redisson/Jedis
       │  ├── RestDataSource ─── RestTranslator ─── HTTP Client
       │  └── CustomDataSource (用户实现) ─────────── 任意后端
       │
       ▼
Result 统一模型
```

**优势**：抽象层次合理、扩展性最佳、业务代码与数据源解耦
**劣势**：通用 Query 模型设计难度大，需要平衡表达力与通用性

### 3.2 业界多数据源方案对比

| 框架/规范 | 多数据源支持 | 抽象层次 | 扩展机制 |
|---------|------------|---------|---------|
| JPA/Hibernate | 否（仅 JDBC） | 高 | Dialect 内部扩展 |
| MyBatis | 多 DataSource，但仍是 JDBC | 中 | 插件机制 |
| Spring Data | 是（JPA / Mongo / Redis / JDBC 各自独立） | 高 | 各模块独立 |
| Jakarta NoSQL | 是（统一 NoSQL 抽象） | 中 | 标准 SPI |
| Micronaut Data | 是（编译期生成） | 高 | 注解驱动 |
| EclipseLink | NoSQL + SQL 双支持 | 中 | EIS 适配器 |

### 3.3 HORM 的 SPI 设计要点

1. **`DataSource` SPI**：定义 `openSession()` / `getCapabilities()` / `close()`，最小化契约
2. **`Session` SPI**：定义 `execute(Query)` / `executeBatch(List<Query>)` / `transaction(Begin/Commit/Rollback)`
3. **`QueryTranslator` SPI**：将通用 `Query` 翻译为原生命令
4. **`ResultHandler` SPI**：将原生结果转换为 HORM 实体（基于编译期生成的 Mapper）
5. **`Capabilities`**：声明数据源能力（事务、批量、关联、排序、分页），通用层据此降级
6. **`DataSourceRegistry`**：注册中心，多数据源共存，可通过 `@UseDataSource("name")` 或 ThreadLocal 切换

### 3.4 自定义非数据库数据源适配案例

#### 案例一：REST API 数据源

将外部 REST API 暴露为 ORM 数据源，使得 `User.find(1)` 实际发出 `GET /users/1`，`User.where("status", "active").all()` 发出 `GET /users?status=active`。

实现要点：
- `RestDataSource` 持有 OkHttp Client 与 BaseUrl
- `RestTranslator` 将 `Query` 翻译为 HTTP 方法 + URL + Body
- 支持 OData / JSON:API / 自定义协议三种映射模式
- 支持认证（Bearer / Basic / API Key）
- 缓存层透传，可缓存 HTTP 响应

#### 案例二：CSV 文件数据源

将本地 CSV / TSV 文件作为数据源，支持查询、过滤、排序，常用于报表、ETL 场景。

实现要点：
- 启动时读取 CSV Header 推断字段类型
- 数据全量加载到内存或流式读取
- `CsvTranslator` 将 `Query` 翻译为内存过滤逻辑
- 写操作需要重写整个文件（适合只读或低频写）

#### 案例三：Elasticsearch 数据源

将 ES 索引作为数据源，利用 ES 的查询能力。

实现要点：
- `EsTranslator` 将 `Query` 翻译为 ES QueryDSL JSON
- 支持分页（from/size 或 search_after）、高亮、聚合
- 事务不支持（ES 无事务），通过 `Capabilities` 声明降级

#### 案例四：自定义数据源（用户实现 SPI）

```java
@ServiceProvider(DataSource.class)
public class MyRedisDataSource implements DataSource {
    @Override
    public String name() { return "my-redis"; }

    @Override
    public Session openSession() {
        return new MyRedisSession(jedisPool);
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.builder()
            .transaction(false)
            .batch(true)
            .pagination(false)
            .build();
    }
}
```

通过 Java `ServiceLoader` 或 Spring `BeanFactory` 自动注册，零代码侵入。

---

## 四、缓存链系统设计模式调研

### 4.1 缓存模式分类

#### 4.1.1 缓存读写策略

- **Cache-Aside（旁路缓存）**：业务代码先查缓存，未命中再查数据源，并回填缓存。最常用。
- **Read-Through（读穿透）**：业务代码只查缓存，缓存未命中时由缓存层自动从数据源加载。
- **Write-Through（写穿透）**：业务代码写缓存，缓存同步写数据源。
- **Write-Behind（写回）**：业务代码写缓存即返回，缓存异步写数据源。性能最高，但有数据丢失风险。
- **Write-Around（写绕过）**：业务代码直接写数据源，不写缓存。适合只读后不立即访问的数据。

#### 4.1.2 多级缓存架构

业界典型多级缓存：
- **L1**：进程内缓存（Caffeine / Guava），微秒级延迟，容量受限于单机内存
- **L2**：分布式缓存（Redis / Memcached），毫秒级延迟，容量大
- **L3**：远端缓存或数据库内置缓存（如 MySQL Buffer Pool），十毫秒级延迟

### 4.2 业界多级缓存实现对比

| 实现 | 模式 | 一致性 | 扩展性 | 适用场景 |
|------|------|-------|-------|---------|
| Spring Cache | 注解驱动，单层 | 弱 | 中 | 简单场景 |
| Caffeine + Redis（手写） | 任意 | 弱 | 强 | 定制化 |
| JetCache（阿里） | 注解 + 多级 | 中 | 强 | Java 通用 |
| J2Cache | 两级缓存 + 消息同步 | 强 | 强 | 高一致性场景 |
| Redisson BundledCache | 多级 | 中 | 中 | Redis 生态 |

### 4.3 HORM 缓存链设计

HORM 缓存链采用 **责任链模式 + 可组合策略**：

```
┌─────────────── HORM Cache Chain ───────────────┐
│                                                 │
│   请求 ─► L1 (Caffeine)                         │
│            │ miss                                │
│            ▼                                     │
│           L2 (Redis)                            │
│            │ miss                                │
│            ▼                                     │
│           L3 (远端/DB)                          │
│            │                                     │
│            ▼  回填                                │
│           L2 ← L3                               │
│           L1 ← L2                               │
│                                                 │
│   策略层：TTL / LRU / LFU / 写穿透 / 写回         │
│   事件层：命中/未命中/淘汰/失效 广播              │
│   监控层：命中率/延迟 指标暴露                   │
└─────────────────────────────────────────────────┘
```

### 4.4 关键设计要点

1. **缓存键设计**：`entityType:partition:key`（如 `User:shard1:id:123`），支持分片
2. **失效策略**：单条失效、批量失效、模式失效（`User:*`）、TTL 自然失效
3. **一致性保证**：写穿透模式 + 失效广播（Redis Pub/Sub 或 Kafka），最终一致
4. **缓存击穿**：通过 `CacheLoader` 的单飞机制（singleflight）保证同一 key 并发加载只触发一次数据源查询
5. **缓存穿透**：布隆过滤器 + 空值缓存
6. **缓存雪崩**：TTL 加随机抖动、限流降级
7. **可观测性**：每层缓存暴露命中率、延迟、淘汰数等指标（Micrometer）

### 4.5 性能优化策略

- **异步回填**：未命中时数据源查询同步返回，回填异步化
- **预加载**：根据访问模式预测预取热数据
- **批量化**：`getAll(Set<K>)` 批量查询，减少 RTT
- **序列化优化**：默认 Protobuf / Protostuff，可选 JSON / Kryo
- **零拷贝**：L1 直接持有对象引用，避免序列化
- **多级一致性**：写穿透时按 L2 → L1 顺序写入，避免 L1 旧值覆盖

---

## 五、非数据库数据源 ORM 适配调研

### 5.1 已有解决方案

#### 5.1.1 Jakarta NoSQL 规范

Jakarta NoSQL 提供统一 API 抽象 NoSQL 数据库（KeyValue / Document / Column / Graph），但：
- 仍依赖反射读取实体元数据
- 不支持 SQL 数据源统一抽象
- 不支持自定义非 NoSQL 数据源（如 REST、文件）

#### 5.1.2 Spring Data 多模块

Spring Data 提供 JDBC / JPA / MongoDB / Redis / Elasticsearch / Couchbase 等多个独立模块，但：
- 各模块 API 不完全统一
- 不支持自定义数据源扩展
- 反射开销大

#### 5.1.3 Micronaut Data

Micronaut Data 通过编译期生成实现，零反射，但：
- 仅支持 JDBC / JPA / Mongo 几种数据源
- 扩展需要深入框架内部

### 5.2 HORM 的差异化定位

HORM 在以下方面差异化：
1. **真正统一的 Query 模型**：SQL / NoSQL / REST / 文件使用同一套查询 API
2. **能力声明机制**：通过 `Capabilities` 让上层知道数据源能做什么，自动降级
3. **可插拔 SPI**：用户通过实现 `DataSource` + `QueryTranslator` + `ResultHandler` 三个接口即可接入任意数据源
4. **零反射**：编译期生成的 Mapper 适用于所有数据源（不仅 SQL）
5. **缓存链透明**：缓存层对所有数据源生效，业务无感

### 5.3 典型场景验证

| 场景 | 数据源 | HORM 支持 |
|------|-------|----------|
| 用户表 CRUD | MySQL / PG | ✓ SQL DataSource |
| 商品搜索 | Elasticsearch | ✓ ES DataSource |
| 配置读取 | Apollo / Nacos | ✓ 配置中心 DataSource |
| 文件存储 | CSV / Excel | ✓ 文件 DataSource |
| 远端 API | 第三方 REST | ✓ REST DataSource |
| 实时计算 | Kafka 流 | ✓ 流式 DataSource（计划中） |
| 时序数据 | InfluxDB / TDengine | ✓ 时序 DataSource（计划中） |
| 图数据 | Neo4j | ✓ 图 DataSource（计划中） |

---

## 六、调研结论与 HORM 设计原则

### 6.1 设计原则

1. **API 优先 Active Record 风格**：`entity.save()` / `Entity.find(id)` / `Entity.where(...).all()`
2. **零反射优先**：APT 编译期生成元数据，运行时禁用反射字段读写
3. **SPI 优先**：所有可扩展点定义为 SPI（DataSource / Cache / CacheLoader / Validator / Hook）
4. **能力声明优先**：数据源声明能力，上层自动降级，避免硬编码 if-else
5. **可组合优先**：缓存链、验证规则、生命周期钩子均支持链式组合
6. **类型安全优先**：通过编译期生成的元模型类实现类型安全查询 DSL

### 6.2 关键技术选型

| 维度 | 选择 | 理由 |
|------|------|------|
| 元数据生成 | APT + JavaPoet | 编译期、类型安全、零反射、AOT 兼容 |
| 查询 DSL | 编译期生成元模型 + 链式 Builder | jOOQ 式类型安全 + Active Record 简洁性 |
| 数据源 SPI | 通用 Query 模型 + Translator | 抽象层次合理，扩展性强 |
| 缓存链 | 责任链 + 可组合策略 | 灵活组合多级缓存与策略 |
| 事务管理 | SPI + ThreadLocal 上下文 | 与 Spring/Jakarta 事务兼容 |
| 字节码 | 不使用 | AOT/原生镜像友好 |
| 验证 | Jakarta Validation + 自定义 SPI | 标准化 + 可扩展 |
| 迁移工具 | 自研 DSL（参考 Rails/Flyway） | 简洁 + 可嵌入 |

### 6.3 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| APT 增加构建时间 | 增量编译、并行处理、缓存生成结果 |
| 通用 Query 模型表达力不足 | 提供 escape hatch，允许数据源特定扩展 |
| 多数据源事务一致性 | 明确支持水平（多 SQL）与垂直（混合数据源）两种模式，混合模式降级为最佳努力 |
| 缓存一致性问题 | 提供强一致与最终一致两种模式，业务按需选择 |
| 学习曲线 | 提供完善的 Quickstart、Recipe 文档、Migration Guide（从 MyBatis / JPA 迁移） |

---

## 参考资源

- [Rails ActiveRecord 文档](https://guides.rubyonrails.org/active_record_basics.html)
- [Hibernate User Guide](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html)
- [MyBatis 官方文档](https://mybatis.org/mybatis-3/)
- [jOOQ 文档](https://www.jooq.org/doc/latest/manual/)
- [Spring Data JDBC 参考](https://docs.spring.io/spring-data/relational/reference/jdbc.html)
- [Jakarta NoSQL 规范](https://eclipse-ee4j.github.io/jakartaee-tutorial/#nosql)
- [Micronaut Data](https://micronaut-projects.github.io/micronaut-data/latest/guide/)
- [Caffeine 缓存](https://github.com/ben-manes/caffeine)
- [JetCache](https://github.com/alibaba/jetcache)
- [JavaPoet](https://github.com/square/javapoet)
- [Annotation Processing Tool 指南](https://docs.oracle.com/en/java/javase/17/docs/api/javax.annotation.processing/package-summary.html)

---

> 调研结论将作为后续架构设计（[01-architecture.md](./01-architecture.md)）的输入。
