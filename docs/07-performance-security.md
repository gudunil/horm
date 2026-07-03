# HORM 性能优化与安全考量

> 本文档详述 HORM 框架的性能优化策略、安全考量、AOT 兼容性、容量规划与基准测试方案。

---

## 一、性能优化策略

### 1.1 性能目标

| 指标 | 目标 |
|------|------|
| 单条 findById（命中缓存） | < 1μs |
| 单条 findById（未命中缓存） | < 100μs（本地 SQL） |
| 列表查询（100 条） | < 1ms（本地 SQL） |
| 批量 INSERT（1000 条） | < 50ms（本地 SQL） |
| 启动时间（100 实体） | < 100ms（元数据注册） |
| 编译期 APT 增量 | < 1s（10 实体改动） |
| 内存占用（10K 实体） | < 50MB |

### 1.2 零反射性能优势

| 操作 | Hibernate 反射 | HORM APT 生成 | 提升 |
|------|---------------|--------------|------|
| 实例化 | 200ns（Constructor.newInstance） | 5ns（new） | 40x |
| 字段读 | 8ns（Field.get 缓存） | 1.5ns（Lambda 方法引用） | 5x |
| 字段写 | 9ns（Field.set 缓存） | 1.5ns（Lambda 方法引用） | 6x |
| 元数据查询 | 30ns（HashMap 反射元数据） | 1ns（静态常量） | 30x |

### 1.3 编译期生成优化

- **静态常量替换**：字段名、列名、表名在生成代码中为 `static final` 常量，JIT 内联
- **switch 分发**：`setField/getField` 使用 `switch` 编译为 tableswitch，O(1)
- **方法引用**：字段访问器使用 `LambdaMetafactory`，接近原生调用
- **避免装箱**：基础类型字段直接使用 `long`/`int`，避免 `Long`/`Integer` 装箱

### 1.4 SQL 优化

#### 预编译语句复用

```java
// HORM 自动复用 PreparedStatement
PreparedStatement ps = connection.prepareStatement("SELECT ...");
// 多次执行仅 setXxx + execute
```

#### 批量执行

```java
// 批量 INSERT 用单条多 VALUES 语句
// INSERT INTO users (email) VALUES (?), (?), (?)
// 比 100 条独立 INSERT 快 5-10 倍
```

#### IN 列表分块

避免过大的 IN 列表导致 SQL 性能下降：

```java
// 配置 holo.horm.query.max-in-clause-size=500
// 自动分块：WHERE id IN (1..500) OR id IN (501..1000)
```

#### N+1 自动检测与优化

```java
// 触发 N+1 检测
List<User> users = User.all(User.class);
users.forEach(u -> u.getOrders().size());  // N 次 SELECT

// 推荐方式
List<User> users = User.all(User.class).include(User::getOrders).all();
// 1 次 SELECT users + 1 次 SELECT orders WHERE user_id IN (...)
```

#### 索引提示

```java
User.where(User.class, ...)
    .hint("USE INDEX(idx_email)")
    .all();
```

### 1.5 缓存优化

详见 [04-cache-chain.md](./04-cache-chain.md)。

### 1.6 连接池调优

| 参数 | 推荐值 | 说明 |
|------|-------|------|
| maximumPoolSize | CPU 核数 × 2 ~ 10 | 过大反而拖累 DB |
| minimumIdle | 同 maximumPoolSize | 避免空闲连接抖动 |
| connectionTimeout | 30s | 获取连接超时 |
| idleTimeout | 10min | 空闲连接超时 |
| maxLifetime | 30min | 连接最大生命周期 |
| leakDetectionThreshold | 60s | 连接泄漏检测 |

### 1.7 JIT 优化友好

- 避免虚方法调用过度（合理使用 final）
- 热点方法控制在 35 字节码以内（JIT 内联阈值）
- 避免大方法（C2 编译阈值）
- 使用 `@HotSpotIntrinsicCandidate` 标注关键方法（仅 JDK 内部可用，参考）

### 1.8 GC 友好

- 实体对象尽量小（关联懒加载）
- Row 使用对象池避免频繁创建
- 大查询结果使用流式处理
- 缓存值使用原生类型数组（如 long[] 而非 List<Long>）

---

## 二、安全考量

### 2.1 SQL 注入防护

**核心原则**：所有 SQL 参数化，杜绝字符串拼接。

HORM Query 模型不允许直接嵌入原生 SQL 片段。原生 SQL 逃生舱强制使用 `PreparedStatement` 参数：

```java
// 安全
User.findByNativeSql(
    "SELECT * FROM users WHERE email = ? AND status = ?",
    mapper,
    email, status
);

// 不安全（HORM 不允许，会抛出异常）
User.findByNativeSql("SELECT * FROM users WHERE email = '" + email + "'");
```

### 2.2 敏感数据保护

#### `@Sensitive` 注解

```java
@Entity(table = "users")
public class User extends Model<User> {
    @Column
    @Sensitive(type = SensitiveType.EMAIL)  // 自动脱敏
    private String email;

    @Column
    @Sensitive(type = SensitiveType.MASK, maskChar = '*', keepPrefix = 3, keepSuffix = 4)
    private String phone;

    @Column
    @Sensitive(type = SensitiveType.ENCRYPT, algorithm = "AES", key = "${holo.horm.encryption.key}")
    private String idCard;

    @Column
    @JsonIgnore  // 序列化时跳过
    private String password;
}
```

- 日志输出时自动脱敏（email → `a***@b.com`）
- JSON 序列化时按注解规则
- 缓存键不含敏感字段值，改用 SHA-256 hash
- 加密字段在数据源中加密存储，读取时自动解密

#### 字段级权限

```java
@FieldLevel(requiredPermission = "user:read:sensitive")
private String idCard;
```

通过 `FieldAccessController` 控制字段访问，未授权时返回 null 或抛出异常。

### 2.3 多租户隔离

详见 [06-extension-features.md](./06-extension-features.md) 第十章。

### 2.4 数据源凭证管理

- 配置文件中的密码使用 Jasypt 加密
- 生产环境从 Vault / KMS 获取凭证
- 不在日志中打印凭证

### 2.5 审计日志

```java
@Entity(table = "users")
@Audited  // 自动记录变更历史
public class User extends Model<User> { ... }

// 每次更新自动写入审计表
// audit_log: id, entity_type, entity_id, operation, before, after, operator, timestamp
```

### 2.6 软删除

```java
@Entity(table = "users")
@SoftDelete(column = "deleted_at")  // 软删除字段
public class User extends Model<User> { ... }

// 默认查询自动过滤 deleted_at IS NULL
List<User> users = User.all(User.class);
// → SELECT * FROM users WHERE deleted_at IS NULL

// 强制查询包含已删除
List<User> all = User.unscoped().withDeleted().all();

// 删除（自动软删除）
user.delete();
// → UPDATE users SET deleted_at = NOW() WHERE id = ?

// 真正删除
user.destroy();
// → DELETE FROM users WHERE id = ?
```

### 2.7 数据加密

#### 传输层加密

- JDBC SSL/TLS
- MongoDB TLS
- Redis TLS
- REST 数据源 HTTPS

#### 静态加密

- 字段级加密（`@Sensitive(type = ENCRYPT)`）
- 数据库 TDE（Transparent Data Encryption）
- 应用层加密 + 数据库加密双层防护

### 2.8 资源限制

防止恶意查询拖垮系统：

```yaml
holo:
  horm:
    security:
      max-query-result-size: 10000          # 单次查询最大返回
      max-in-clause-size: 500               # IN 列表最大长度
      max-batch-size: 1000                  # 批量操作最大
      query-timeout: 30s                    # 查询超时
      slow-query-threshold: 1s              # 慢查询阈值
      max-text-field-length: 1MB            # 文本字段最大长度
      forbidden-patterns:                   # 禁止的查询模式
        - "LIKE '%'"                        # 全表 LIKE
```

### 2.9 安全审计

```yaml
holo:
  horm:
    audit:
      enabled: true
      log-level: INFO
      operations: [INSERT, UPDATE, DELETE]
      entities: [User, Order, Payment]  # 仅敏感实体
      include-fields: [id, email, amount]
```

---

## 三、AOT / GraalVM Native Image 兼容

### 3.1 兼容性要点

- **无运行时字节码生成**：HORM 全部代码编译期生成
- **避免反射**：实体访问通过 APT 生成的 Mapper，无反射
- **资源文件可达**：`META-INF/horm/entities.idx` 在 native-image 配置中注册
- **ServiceLoader 可达**：所有 SPI 实现类在 native-image 配置中注册
- **动态代理**：仅在 Spring AOP 场景使用，已通过 Spring native 支持

### 3.2 Native Image 配置

HORM 自动提供 native-image.properties：

```
Args = --initialize-at-build-time=com.holo.framework.horm.generated \
       --initialize-at-build-time=com.holo.framework.horm.meta \
       -H:ResourceConfigurationResources=META-INF/native-image/com.holo.framework/holo-horm/resource-config.json \
       -H:ReflectionConfigurationResources=META-INF/native-image/com.holo.framework/holo-horm/reflection-config.json
```

### 3.3 验证

HORM CI 中验证 native image 构建：

```bash
mvn -Pnative package
./target/holo-horm-examples  # 直接执行 native binary
```

---

## 四、容量规划

### 4.1 数据源连接数

| 数据源类型 | 推荐连接数 | 备注 |
|----------|----------|------|
| MySQL/PG | 20-100 | 根据 DB max_connections 调整 |
| MongoDB | 50-200 | 连接池默认 100 |
| Redis | 50-200 | Redisson 默认 64 |
| REST | 50-200 | OkHttp 默认 5/路由，需调大 |

### 4.2 缓存容量

| 缓存层 | 推荐容量 | 备注 |
|-------|---------|------|
| L1 (Caffeine) | 10000-100000 条 / 实体 | 受限于 JVM 堆 |
| L2 (Redis) | 无上限 | 受限于 Redis 内存 |

### 4.3 内存估算

单个实体对象内存占用估算：
- User（5 字段）：~200 字节
- 10K 实体：~2MB
- 100K 实体：~20MB
- L1 缓存 + 实体对象：~50MB（10K 实体场景）

---

## 五、基准测试

### 5.1 JMH 基准

HORM 内置 JMH 基准测试套件：

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class FindByIdBenchmark {

    @Benchmark
    public User holoHormCacheHit() {
        return User.find(User.class, 1L);  // 命中 L1
    }

    @Benchmark
    public User holoHormCacheMiss() {
        return User.find(User.class, randomId());  // 未命中
    }

    @Benchmark
    public User jdbcRaw() throws SQLException {
        // 手写 JDBC 对照组
    }

    @Benchmark
    public User hibernate() {
        // Hibernate 对照组
    }

    @Benchmark
    public User mybatis() {
        // MyBatis 对照组
    }
}
```

### 5.2 基准场景

| 场景 | 描述 |
|------|------|
| findById 命中 | L1 缓存命中 |
| findById 未命中 | 缓存未命中，查询数据源 |
| 列表查询（10 条） | 中等结果集 |
| 列表查询（100 条） | 较大结果集 |
| 批量 INSERT（1000 条） | 批量插入 |
| 复杂条件查询 | 多条件 + 排序 + 分页 |
| 关联查询（N+1 优化） | JOIN 与 IN 预加载对比 |

### 5.3 性能预期（本地 MySQL，JDK 17）

| 场景 | HORM | Hibernate | MyBatis | 手写 JDBC |
|------|------|----------|--------|----------|
| findById 缓存命中 | 0.5μs | 5μs | - | - |
| findById 缓存未命中 | 80μs | 350μs | 90μs | 60μs |
| 列表查询 100 条 | 800μs | 2500μs | 900μs | 700μs |
| 批量 INSERT 1000 条 | 30ms | 200ms | 35ms | 25ms |

实际数据以基准测试输出为准。

---

## 六、开发计划与里程碑

### 6.1 路线图

```
   M1 基础架构 (4 周)        M2 核心功能 (6 周)          M3 高级特性 (4 周)
   ─────────────────         ─────────────────          ─────────────────
   2026-07 ~ 2026-08         2026-08 ~ 2026-09          2026-10 ~ 2026-11

   ├─ APT 元数据生成          ├─ Active Record API        ├─ 关联关系
   ├─ Model 基类              ├─ Query DSL                ├─ Scope
   ├─ Mapper 接口             ├─ SQL DataSource           ├─ 验证
   ├─ EntityMeta 注册         ├─ MySQL/PG 方言            ├─ 生命周期钩子
   ├─ 基础 CRUD               ├─ 事务管理                 ├─ 软删除
   ├─ 编译期校验              ├─ Spring Boot Starter      ├─ 审计日志
   └─ 单元测试框架            └─ 基础示例                  └─ 多租户

            ▼                            ▼                            ▼

   M4 缓存链 (3 周)           M5 迁移与代码生成 (3 周)     M6 多数据源扩展 (4 周)
   ──────────────             ──────────────────         ──────────────────
   2026-11 ~ 2026-12          2027-01                    2027-02 ~ 2027-03

   ├─ Cache SPI               ├─ Migration DSL            ├─ MongoDB DataSource
   ├─ L1 Caffeine 实现        ├─ 迁移执行器               ├─ Redis DataSource
   ├─ L2 Redis 实现           ├─ Codegen CLI              ├─ REST DataSource
   ├─ 缓存链组合              ├─ Maven 插件               ├─ CSV DataSource
   ├─ 失效广播                └─ DDL ↔ Entity 互转        └─ 自定义 SPI 文档
   ├─ 防穿透/雪崩/击穿
   └─ 监控指标

            ▼                            ▼                            ▼

   M7 性能与稳定 (3 周)       M8 生态集成 (4 周)           M9 GA 发布 (2 周)
   ──────────────             ──────────────              ──────────────
   2027-04                    2027-05 ~ 2027-06           2027-07

   ├─ JMH 基准套件            ├─ Spring Data Page 集成     ├─ 文档完善
   ├─ 性能调优                ├─ Quarkus Extension         ├─ Quickstart 模板
   ├─ 内存优化                ├─ Helidon 集成              ├─ 迁移指南
   ├─ AOT 兼容验证            ├─ 反应式 API（基础）        ├─ API 文档
   ├─ 压力测试                ├─ 监控 Dashboard            ├─ 1.0.0 GA
   └─ 长跑测试                └─ 灰度场景验证              └─ Maven Central
```

### 6.2 里程碑详述

#### M1：基础架构（2026-07-04 ~ 2026-08-15）

**目标**：跑通编译期生成与基础 CRUD

**交付物**：
- `holo-horm-meta` 模块完成 APT 处理器，能解析 `@Entity` 生成 `EntityMeta` 与 `Mapper`
- `holo-horm-core` 模块完成 `Model` 基类与基础 CRUD
- 单元测试覆盖核心元数据生成

**验收标准**：
- 给定 `@Entity` 类，APT 能生成 `XxxMeta` 与 `XxxMapper` 类
- `entity.save()` 能通过 JDBC 写入数据库
- `Entity.find(User.class, 1L)` 能从数据库读取实体
- 编译期错误（字段名错、类型不匹配）能正确报告

#### M2：核心功能（2026-08-15 ~ 2026-09-30）

**目标**：Active Record 完整功能可用

**交付物**：
- 完整的 Query DSL（条件、排序、分页、聚合）
- SQL DataSource 完整实现，支持 MySQL/PG 方言
- 事务管理（编程式 + Spring 声明式）
- Spring Boot Starter 自动装配
- 基础示例（CRUD、查询、事务）

**验收标准**：
- 复杂查询（多条件 + JOIN + 排序 + 分页）能在 MySQL/PG 上正确执行
- `@Transactional` 注解工作正常
- Spring Boot 应用启动后能直接使用 HORM
- 集成测试覆盖主要场景

#### M3：高级特性（2026-10-01 ~ 2026-11-15）

**目标**：Active Record 高级特性

**交付物**：
- 关联关系（BelongsTo / HasOne / HasMany / HasAndBelongsToMany）
- Scope（默认 Scope、链式组合）
- 验证（声明式 + 自定义 + 分组）
- 生命周期钩子（方法覆盖 + 注解 + 观察者）
- 软删除、审计日志
- 多租户支持

#### M4：缓存链（2026-11-15 ~ 2026-12-15）

**目标**：完整的多级缓存链

**交付物**：
- Cache SPI 与 L1（Caffeine）、L2（Redis）实现
- 缓存链组合、失效广播、防穿透/雪崩/击穿
- 注解驱动（@Cacheable / @CacheInvalidate / @CachePut）
- Micrometer 指标
- Grafana Dashboard 模板

#### M5：迁移与代码生成（2027-01-01 ~ 2027-01-31）

**交付物**：
- Migration DSL（Java + SQL 文件）
- 迁移执行器（命令行 + 启动时自动）
- Codegen CLI（DDL→Entity、Entity→DDL、迁移脚本生成）
- Maven 插件

#### M6：多数据源扩展（2027-02-01 ~ 2027-03-15）

**交付物**：
- MongoDB DataSource
- Redis DataSource
- REST DataSource
- CSV DataSource
- 自定义 SPI 完整文档与示例

#### M7：性能与稳定（2027-03-15 ~ 2027-04-30）

**交付物**：
- JMH 基准套件
- 性能调优报告
- AOT/GraalVM Native Image 验证
- 压力测试、长跑测试
- 内存与 GC 优化

#### M8：生态集成（2027-05-01 ~ 2027-06-30）

**交付物**：
- Spring Data Page 集成
- Quarkus Extension
- Helidon 集成
- 反应式 API（基础）
- 监控 Dashboard

#### M9：GA 发布（2027-07-01 ~ 2027-07-31）

**交付物**：
- 完善的文档（Quickstart、User Guide、Migration Guide）
- 1.0.0 GA 版本
- Maven Central 发布

### 6.3 团队配置建议

| 角色 | 人数 | 工作内容 |
|------|------|---------|
| 框架架构师 | 1 | 整体设计、关键技术决策 |
| 核心开发 | 2-3 | APT、Core、DataSource、Cache 实现 |
| 测试工程师 | 1 | 测试套件、基准测试、兼容性验证 |
| 文档工程师 | 1 | 文档撰写、示例代码、Quickstart |

### 6.4 风险评估

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| APT 复杂度超预期 | 中 | 高 | 早期原型验证，预留 buffer |
| 多数据源抽象表达力不足 | 中 | 高 | 通用 Query 模型 + 逃生舱 |
| 性能不达预期 | 低 | 高 | 持续基准测试，早期发现 |
| Spring 兼容性问题 | 低 | 中 | 紧跟 Spring Boot 版本 |
| Native Image 兼容性 | 中 | 中 | M7 阶段专项验证 |
| 团队人力不足 | 中 | 高 | 优先级排序，砍非核心 |

### 6.5 验收标准

每里程碑验收标准：
- 所有交付物完成
- 单元测试覆盖率 > 80%
- 集成测试通过率 100%
- 性能基准达标
- 文档同步更新
- Code Review 通过

### 6.6 发布节奏

- 每两周一个 Sprint
- 每月一个 SNAPSHOT 版本
- 每里程碑一个 Beta 版本
- M9 结束发布 1.0.0 GA
- 后续按 SemVer 维护（1.0.x patch、1.x minor、2.0 major）

---

## 七、参考资源

- [Hibernate Performance Tuning](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#performance)
- [jOOQ Performance](https://www.jooq.org/doc/latest/manual/sql-building/performance/)
- [GraalVM Native Image](https://www.graalvm.org/native-image/)
- [JMH](https://openjdk.org/projects/code-tools/jmh/)
- [Micrometer](https://micrometer.io/)
- [OWASP SQL Injection Prevention](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html)
- [Spring Boot Native](https://docs.spring.io/spring-boot/docs/current/reference/html/native-image.html)
