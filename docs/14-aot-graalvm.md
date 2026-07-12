# HORM AOT / GraalVM Native Image 兼容性

> 本文详述 HORM 框架对 GraalVM Native Image 的兼容性分析、配置说明与验证步骤。
>
> **实现状态**：M8.7 已完成零反射优化；M9 Phase 3 提供 native-image 配置模板。实际 Native Image 构建需通过 Tracing Agent 补充完整配置。

---

## 一、Native Image 原理

### 1.1 GraalVM Native Image 工作方式

GraalVM Native Image 在构建时执行 closed-world 分析，将 Java 应用预先编译为独立可执行文件：

| 特性 | JVM 模式 | Native Image 模式 |
|------|---------|------------------|
| 启动时间 | 0.5 - 2 s | 10 - 50 ms |
| 内存占用 | 200 - 500 MB | 30 - 80 MB |
| 运行时反射 | 支持 | 需配置 reflect-config.json |
| 运行时字节码生成 | 支持 | 不支持（默认） |
| 类加载 | 按需 | 构建期固定 |
| 动态代理 | 支持 | 需配置 proxy-config.json |

### 1.2 ORM 框架在 Native Image 下的挑战

| 挑战 | 传统 ORM（Hibernate） | HORM |
|------|---------------------|------|
| 运行时反射 | 大量 `Class.forName` / `Field.get` / `Method.invoke` | M8.7 已消除 |
| 运行时字节码生成 | CGLIB / ByteBuddy 代理 | M8.7 改用 APT 编译期生成 |
| 动态代理 | JDK Proxy | M8.7 改用 APT 子类代理 |
| ServiceLoader | 支持 | HORM 大量使用（需配置 resource-config.json） |
| 资源文件 | 视情况 | entities.idx / transactions.idx / META-INF/services |

---

## 二、HORM AOT 兼容性分析

### 2.1 已消除的反射点（M8.7）

| # | 文件 | 原反射调用 | M8.7 方案 |
|---|------|-----------|----------|
| R1 | `EntityMetaRegistry` | `Class.forName()` + `Method.invoke()` | `ServiceLoader<EntityMetaProvider>` |
| R2 | `TransactionAdvisorRegistry` | `Class.forName()` + `Field.get()` | `ServiceLoader<TransactionAdvisorProvider>` |
| R3 | `TransactionInterceptor` | `method.invoke(target, args)` | APT 代理子类直接调用 + LambdaMetafactory 降级 |
| R4 | `CaffeineCache` | `Class.forName()` | 工厂方法 + `NoClassDefFoundError` |
| R5 | `RedisCache` | `Class.forName()` | 工厂方法 + `NoClassDefFoundError` |
| R7 | `HormTransactionalBeanPostProcessor` | `Proxy.newProxyInstance()` | APT 代理子类 |
| R8 | `HormTransactionalBeanPostProcessor` | `getDeclaredMethods()` + `isAnnotationPresent()` | APT 代理子类 |

### 2.2 保留的反射点（不可避免）

| # | 文件 | 反射调用 | 保留原因 |
|---|------|---------|---------|
| R6 | `TypeReference` | `getGenericSuperclass()` | Java 泛型擦除唯一标准方案（Jackson/Guava/Spring 均采用） |

### 2.3 ServiceLoader 依赖

HORM 通过 `ServiceLoader` 发现以下 SPI：

| SPI 接口 | 模块 | 发现位置 |
|---------|------|---------|
| `EntityMetaProvider` | meta | `META-INF/services/com.holo.framework.horm.meta.EntityMetaProvider` |
| `TransactionAdvisorProvider` | meta | `META-INF/services/com.holo.framework.horm.meta.TransactionAdvisorProvider` |
| `TransactionProxyFactory` | meta | `META-INF/services/com.holo.framework.horm.meta.TransactionProxyFactory` |
| `MigrationExecutor` | migration | `META-INF/services/com.holo.framework.horm.core.MigrationExecutor` |

GraalVM Native Image 原生支持 `ServiceLoader`，但需在 `resource-config.json` 中声明资源模式。

### 2.4 资源文件

| 资源 | 模块 | 用途 |
|------|------|------|
| `META-INF/horm/entities.idx` | meta/core | 实体索引（兼容回退） |
| `META-INF/horm/transactions.idx` | meta/core | 事务顾问索引（兼容回退） |
| `META-INF/services/*` | 各模块 | ServiceLoader 配置 |
| `META-INF/spring/*.imports` | starter | Spring Boot 自动装配 |

### 2.5 ByteBuddy 与 Native Image

HORM 使用 ByteBuddy 在编译完成后注入 Active Record 静态助手（`User.find(id)` 等）。**此操作发生在 Maven 构建期**，不涉及运行时字节码生成，因此与 Native Image 兼容。

---

## 三、配置说明

### 3.1 native-image.properties

每个模块在 `src/main/resources/META-INF/native-image/com.holo.framework/<module>/native-image.properties` 中声明初始化时机：

**meta 模块**：
```properties
Args = --initialize-at-build-time=com.holo.framework.horm.meta,\
  --initialize-at-build-time=com.holo.framework.horm.meta.annotation
```

**core 模块**：
```properties
Args = --initialize-at-build-time=com.holo.framework.horm.core,\
  --initialize-at-build-time=com.holo.framework.horm.meta
```

**cache 模块**：
```properties
Args = --initialize-at-build-time=com.holo.framework.horm.cache
```

**starter 模块**：
```properties
Args = --initialize-at-build-time=com.holo.framework.horm,\
  --initialize-at-build-time=com.holo.framework.horm.starter
```

### 3.2 resource-config.json

**core 模块**（`holo-horm-core/src/main/resources/META-INF/native-image/com.holo.framework/holo-horm-core/resource-config.json`）：

```json
{
  "resources": {
    "includes": [
      {"pattern": "META-INF/horm/entities.idx"},
      {"pattern": "META-INF/horm/transactions.idx"},
      {"pattern": "META-INF/services/com.holo.framework.horm.meta.EntityMetaProvider"},
      {"pattern": "META-INF/services/com.holo.framework.horm.meta.TransactionAdvisorProvider"},
      {"pattern": "META-INF/services/com.holo.framework.horm.meta.TransactionProxyFactory"},
      {"pattern": "META-INF/services/com.holo.framework.horm.core.MigrationExecutor"}
    ]
  }
}
```

**starter 模块**（`holo-horm-spring-boot-starter/src/main/resources/META-INF/native-image/com.holo.framework/holo-horm-spring-boot-starter/resource-config.json`）：

```json
{
  "resources": {
    "includes": [
      {"pattern": "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"},
      {"pattern": "META-INF/services/com.holo.framework.horm.meta.TransactionProxyFactory"},
      {"pattern": "META-INF/services/com.holo.framework.horm.meta.TransactionAdvisorProvider"}
    ]
  }
}
```

### 3.3 reflect-config.json（最小集）

由于 M8.7 已消除绝大多数反射，仅需配置 `TypeReference`：

```json
[
  {
    "name": "com.holo.framework.horm.cache.TypeReference",
    "methods": [{"name": "<init>", "parameterTypes": []}]
  }
]
```

**实际部署时**，必须用 Tracing Agent 补充业务实体相关反射（如 Spring Boot 的 `BeanInfo` 等）。

---

## 四、验证步骤

### 4.1 准备 GraalVM

```bash
# 安装 GraalVM JDK 17
sdk install java 17.0.10-graal

# 安装 native-image 组件
gu install native-image

# 验证
native-image --version
```

### 4.2 使用 Tracing Agent 收集配置

在 JVM 模式下运行应用，让 agent 自动收集反射/资源/动态代理配置：

```bash
# 启动应用（带 agent）
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image \
     -jar target/myapp.jar

# 触发业务流量（curl / 测试用例）
curl http://localhost:8080/users/1
curl http://localhost:8080/users
# ... 覆盖所有业务路径

# 优雅停止应用（Ctrl+C 或 kill -15），agent 会自动写入配置
```

### 4.3 构建 Native Image

**Maven Spring Boot 插件方式**（推荐）：

```bash
mvn -Pnative package
```

**手动方式**：

```bash
# 编译 + 打包
mvn clean package -Pskip-enforcer

# Native Image 构建
native-image -jar target/myapp.jar target/myapp \
    -H:ConfigurationFileDirectories=src/main/resources/META-INF/native-image \
    --initialize-at-build-time=com.holo.framework.horm \
    --no-fallback

# 运行
./target/myapp
```

### 4.4 验证启动时间与内存

```bash
# 测量启动时间
time ./target/myapp

# 测量内存占用
./target/myapp &
RSS=$(ps -o rss= -p $!)
echo "Memory: $((RSS / 1024)) MB"
```

预期：
- 启动时间 < 100 ms
- 内存占用 < 100 MB

### 4.5 验证 HORM 功能完整性

```bash
# CRUD
curl -X POST http://localhost:8080/users -H 'Content-Type: application/json' -d '{"email":"a@b.com"}'
curl http://localhost:8080/users/1
curl -X PUT http://localhost:8080/users/1 -H 'Content-Type: application/json' -d '{"email":"new@b.com"}'
curl -X DELETE http://localhost:8080/users/1

# 查询
curl 'http://localhost:8080/users?email=*@holo.dev&limit=10'

# 事务
curl -X POST http://localhost:8080/users/batch
```

确认无 `ClassNotFoundException` / `NoSuchMethodException` 等错误。

---

## 五、已知限制

### 5.1 TypeReference 反射

`TypeReference` 通过 `getGenericSuperclass()` 获取泛型类型信息，是 Java 生态处理泛型擦除的标准方案（Jackson/Guava/Spring 均采用）。在 Native Image 下需在 `reflect-config.json` 中显式声明。

### 5.2 ByteBuddy 注入时序

HORM 通过 ByteBuddy 在 `target/classes` 编译完成后注入 Active Record 静态助手。此操作发生在 Maven 构建期，对 Native Image 无影响。但**业务源码中应使用 `Model.find(Class, id)` 风格**，避免编译期绑定到 `Model.find(Object)` fallback。

### 5.3 LambdaMetafactory 降级

对于 APT 无法生成代理的场景（final 类/方法、第三方类），HORM 使用 `LambdaMetafactory` 桥接。Native Image 原生支持 `LambdaMetafactory`，但性能略低于 APT 代理（~3.1 ns/op vs ~1.2 ns/op）。

### 5.4 RedisCache L2 Stub

`RedisCache` 当前为 stub 实现，未启动真实 Redis 实例验证。生产 Native Image 部署若需 L2 缓存，需补充 Redisson 的 native-image 配置（参考 Redisson 官方文档）。

### 5.5 Spring Boot AOT 处理

Spring Boot 3.x 自带 AOT 处理（`spring-boot-maven-plugin:process-aot`），会生成额外的 Bean 注册代码。HORM 的 starter 模块已与 Spring Boot AOT 兼容，但需在 `application.yml` 中启用：

```yaml
spring:
  main:
    lazy-initialization: false     # Native Image 不支持懒加载
```

### 5.6 Flyway 迁移

Flyway 在 Native Image 下需额外配置（`reflect-config.json` 包含 Flyway 内部类）。建议：
- 使用 Spring Boot 的 Flyway 自动配置（已内置 native-image 支持）
- 或参考 [Flyway GraalVM 文档](https://documentation.red-gate.com/fd/graalvm-native-image-184127545.html)

---

## 六、故障排查

### 6.1 `ClassNotFoundException: com.holo.framework.horm.meta.generated.XxxMeta`

**原因**：APT 生成的伴随类未被 Native Image 包含。

**修复**：
1. 确认 `mvn compile` 已执行，`target/generated-sources/annotations/` 下有生成文件
2. 检查 `resource-config.json` 是否包含 `META-INF/services/com.holo.framework.horm.meta.EntityMetaProvider`
3. 用 Tracing Agent 重新收集配置

### 6.2 `NoSuchMethodException: <init>`

**原因**：构造器反射未配置。

**修复**：在 `reflect-config.json` 中添加对应类的 `<init>` 方法：

```json
[
  {
    "name": "com.example.entity.User",
    "methods": [{"name": "<init>", "parameterTypes": []}]
  }
]
```

### 6.3 `ServiceLoader: No service provider found`

**原因**：`META-INF/services` 资源未被包含。

**修复**：在 `resource-config.json` 中添加对应的 `META-INF/services/*` 模式。

### 6.4 `@Transactional` 方法未被代理

**原因**：APT 未生成代理子类（可能是类被标记为 `final`）。

**修复**：
1. 移除 `final` 修饰符
2. 重新编译，检查 `target/generated-sources/annotations/` 下是否有 `XxxTransactionProxy` 文件
3. 若必须用 final 类，会降级到 LambdaMetafactory（Native Image 兼容）

### 6.5 启动时报 `entities.idx` 找不到

**原因**：资源未被包含到 Native Image。

**修复**：在 `resource-config.json` 中添加：

```json
{"pattern": "META-INF/horm/.*"}
```

---

## 七、参考

- [02-zero-reflection.md](./02-zero-reflection.md) — 零反射实现方案
- [09-zero-reflection-optimization.md](./09-zero-reflection-optimization.md) — M8.7 反射消除细节
- [GraalVM Native Image 文档](https://www.graalvm.org/latest/reference-manual/native-image/)
- [Spring Boot AOT](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#aot)
- [10-quickstart.md](./10-quickstart.md) — 快速上手
