[根目录](../../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **holo-horm-examples**

# Holo :: HORM :: Examples

## 模块职责

HORM 框架的示例代码，展示 Active Record 使用方式、查询 DSL、事务管理、缓存链、多数据源与数据库迁移等特性。

- **parent**: `holo-horm` (1.0.0-SNAPSHOT)
- **artifactId**: `holo-horm-examples`
- **packaging**: jar

## 当前状态

- **POM**: 已建并配置 APT processor
- **Spring Boot 主应用**: `HormExamplesApplication`
- **示例实体**: `User`、`Order`、`Product`、`ArchivedOrder`
- **前端页面**: `static/index.html`
- **框架对比接口**: `UserController`、`JdbcTemplateController`、`MyBatisPlusController`、`JpaController`
- **性能对比测试**: `FrameworkComparisonBenchmark`
- **独立示例**: `CacheExample`、`MultiDataSourceExample`
- **集成测试**: `UserCrudIntegrationTest`、`CacheExampleTest`

## 目录结构

```
holo-horm-examples
├── pom.xml
├── src
│   ├── main
│   │   ├── java/com/holo/framework/horm/examples
│   │   │   ├── HormExamplesApplication.java
│   │   │   ├── cache/CacheExample.java
│   │   │   ├── config/SampleDataInitializer.java
│   │   │   ├── controller/UserController.java
│   │   │   ├── controller/JdbcTemplateController.java
│   │   │   ├── controller/MyBatisPlusController.java
│   │   │   ├── controller/JpaController.java
│   │   │   ├── controller/ProductController.java
│   │   │   ├── datasource/MultiDataSourceExample.java
│   │   │   ├── dto/UserDto.java
│   │   │   ├── entity/
│   │   │   │   ├── User.java
│   │   │   │   ├── Order.java
│   │   │   │   ├── Product.java
│   │   │   │   ├── ArchivedOrder.java
│   │   │   │   ├── MpUser.java
│   │   │   │   └── JpaUser.java
│   │   │   ├── mapper/MpUserMapper.java
│   │   │   ├── repository/JpaUserRepository.java
│   │   │   └── service/UserService.java
│   │   └── resources
│   │       ├── application.yml
│   │       ├── static/index.html
│   │       └── db/migration/V001__init_schema.sql
│   └── test/java/com/holo/framework/horm/examples
│       ├── UserCrudIntegrationTest.java
│       ├── FrameworkComparisonBenchmark.java
│       └── cache/CacheExampleTest.java
```

## 运行方式

### 数据库配置

默认使用 **MySQL**（数据库 `horm_examples`，用户名/密码 `root/root`）：

```yaml
spring.datasource.url: jdbc:mysql://localhost:3306/horm_examples?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
spring.datasource.username: root
spring.datasource.password: root
spring.datasource.driver-class-name: com.mysql.cj.jdbc.Driver
```

启动前需先创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS horm_examples CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 编译并运行测试

测试使用 H2 内存库，不受本地 MySQL 环境影响：

```bash
mvn -f holo-horm-examples/pom.xml test -Pskip-enforcer
```

### 启动 Spring Boot 示例应用（MySQL）

```bash
mvn -pl holo-horm-examples -am spring-boot:run -Pskip-enforcer
```

应用启动后访问：

- `http://localhost:8080/` — **前端控制台页面**（用户管理、下单）
- `GET http://localhost:8080/api/users` — 用户列表
- `POST http://localhost:8080/api/users` — 创建用户（JSON body: `{"email":"a@b.com","nickname":"Alice"}`）
- `GET http://localhost:8080/api/users/{id}` — 用户详情
- `PUT http://localhost:8080/api/users/{id}` — 更新昵称
- `DELETE http://localhost:8080/api/users/{id}` — 删除用户
- `POST http://localhost:8080/api/users/{id}/orders` — 下单（JSON body: `{"productId":1,"amount":2}`）
- `http://localhost:8080/h2-console` — H2 控制台（仅在 `h2` profile 启用）

### 框架对比接口

同一 `users` 表，分别用四种方式实现 CRUD，便于横向对比：

| 路径 | 实现方式 |
|------|---------|
| `/api/users` | HORM Active Record |
| `/api/jdbc/users` | Spring JdbcTemplate（手写 SQL） |
| `/api/mybatis/users` | MyBatis-Plus |
| `/api/jpa/users` | Spring Data JPA |

请求/响应格式一致：

```bash
# 创建
curl -X POST http://localhost:8080/api/jdbc/users \
  -H 'Content-Type: application/json' \
  -d '{"email":"jdbc@example.com","nickname":"JdbcUser"}'

# 列表
curl http://localhost:8080/api/mybatis/users

# 更新
curl -X PUT http://localhost:8080/api/jpa/users/1 \
  -H 'Content-Type: application/json' \
  -d '{"nickname":"Updated"}'
```

### 性能对比测试

运行 `FrameworkComparisonBenchmark` 测试，输出四框架在 CREATE / LIST / UPDATE / DELETE 上的吞吐量和延迟：

```bash
mvn -f holo-horm-examples/pom.xml test -Pskip-enforcer -Dtest=FrameworkComparisonBenchmark
```

示例输出（H2 内存库、并发 4、每操作 200 次）：

```
Framework            Operation  Count        Total(ms)    Avg(ms)      Throughput(op/s)
-----------------------------------------------------------
horm                 CREATE     200          780.98       3.9049       256.09
horm                 LIST       200          202.91       1.0146       985.64
horm                 UPDATE     200          92.31        0.4615       2166.70
horm                 DELETE     200          53.12        0.2656       3764.78
-----------------------------------------------------------
jdbc                 CREATE     200          781.52       3.9076       255.91
jdbc                 LIST       200          199.94       0.9997       1000.31
jdbc                 UPDATE     200          40.69        0.2034       4915.77
jdbc                 DELETE     200          28.53        0.1427       7010.14
-----------------------------------------------------------
mybatis              CREATE     200          786.05       3.9303       254.44
mybatis              LIST       200          428.69       2.1435       466.53
mybatis              UPDATE     200          41.84        0.2092       4779.89
mybatis              DELETE     200          31.01        0.1550       6450.43
-----------------------------------------------------------
jpa                  CREATE     200          784.01       3.9200       255.10
jpa                  LIST       200          883.55       4.4178       226.36
jpa                  UPDATE     200          85.87        0.4293       2329.23
jpa                  DELETE     200          47.08        0.2354       4247.84
-----------------------------------------------------------
```

### 使用 H2 启动（无需 MySQL）

如需在无 MySQL 环境运行示例应用，可激活 `h2` profile：

```bash
mvn -pl holo-horm-examples -am spring-boot:run -Pskip-enforcer -Dspring-boot.run.profiles=h2
```

### 运行独立示例

```bash
# 缓存链示例
mvn -pl holo-horm-examples -am exec:java -Dexec.mainClass="com.holo.framework.horm.examples.cache.CacheExample" -Pskip-enforcer

# 多数据源示例
mvn -pl holo-horm-examples -am exec:java -Dexec.mainClass="com.holo.framework.horm.examples.datasource.MultiDataSourceExample" -Pskip-enforcer
```

## 示例覆盖特性

| 特性 | 示例位置 |
|------|---------|
| Active Record CRUD | `UserService`、`UserController` |
| 类型安全 Query DSL | `UserService#listUsers` |
| 声明式事务 | `UserService` |
| 乐观锁 | `User` 实体的 `@Version` |
| 关联关系（@BelongsTo / @HasMany） | `Order`、`User` |
| 缓存链（L1 Caffeine） | `Product` 实体、`CacheExample` |
| 多数据源路由 | `ArchivedOrder` 实体、`MultiDataSourceExample` |
| 数据库迁移 | `V001__init_schema.sql` |
| 前端交互页面 | `static/index.html`、`ProductController` |
| 框架对比接口 | `JdbcTemplateController`、`MyBatisPlusController`、`JpaController` |
| 框架性能对比 | `FrameworkComparisonBenchmark` |
| Spring Boot 自动装配 | `HormExamplesApplication`、`application.yml` |

## 相关文件清单

| 文件路径 | 说明 |
|---------|------|
| `holo-horm/holo-horm-examples/pom.xml` | 模块 POM |
| `holo-horm/holo-horm-examples/src/main/java/com/holo/framework/horm/examples/HormExamplesApplication.java` | 启动类 |
| `holo-horm/holo-horm-examples/src/main/resources/application.yml` | 应用配置 |
| `holo-horm/holo-horm-examples/src/main/resources/db/migration/V001__init_schema.sql` | 示例表结构 |
| `holo-horm/holo-horm-examples/src/main/java/com/holo/framework/horm/examples/entity/User.java` | 用户实体 |
| `holo-horm/holo-horm-examples/src/main/java/com/holo/framework/horm/examples/controller/ProductController.java` | 商品列表接口 |
| `holo-horm/holo-horm-examples/src/main/java/com/holo/framework/horm/examples/controller/JdbcTemplateController.java` | JdbcTemplate 对比接口 |
| `holo-horm/holo-horm-examples/src/main/java/com/holo/framework/horm/examples/controller/MyBatisPlusController.java` | MyBatis-Plus 对比接口 |
| `holo-horm/holo-horm-examples/src/main/java/com/holo/framework/horm/examples/controller/JpaController.java` | Spring Data JPA 对比接口 |
| `holo-horm/holo-horm-examples/src/main/java/com/holo/framework/horm/examples/config/SampleDataInitializer.java` | 示例商品初始化 |
| `holo-horm/holo-horm-examples/src/main/resources/static/index.html` | 前端控制台页面 |
| `holo-horm/holo-horm-examples/src/main/java/com/holo/framework/horm/examples/cache/CacheExample.java` | 缓存链示例 |
| `holo-horm/holo-horm-examples/src/main/java/com/holo/framework/horm/examples/datasource/MultiDataSourceExample.java` | 多数据源示例 |
| `holo-horm/holo-horm-examples/src/test/java/com/holo/framework/horm/examples/UserCrudIntegrationTest.java` | 集成测试 |
| `holo-horm/holo-horm-examples/src/test/java/com/holo/framework/horm/examples/FrameworkComparisonBenchmark.java` | 框架性能对比测试 |

## 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-07-06 | 初始文档生成 | 架构扫描 |
| 2026-07-13 | 补充示例框架 | 新增 Spring Boot 主应用、实体、迁移、独立示例与测试 |
| 2026-07-14 | 新增前端页面 | 新增 `static/index.html` 控制台页面、商品接口与示例数据初始化 |
| 2026-07-14 | 切换默认数据库为 MySQL | `application.yml` 默认连接 MySQL，保留 `h2` profile 用于测试与无 MySQL 环境启动 |
| 2026-07-14 | 升级 Flyway 支持 MySQL 8.1 | 全仓 Flyway 升级至 10.15.2，并引入 `flyway-mysql` 依赖 |
| 2026-07-14 | 新增框架对比接口 | 新增 JdbcTemplate、MyBatis-Plus、Spring Data JPA 三套用户 CRUD 接口 |
| 2026-07-14 | 新增框架性能对比测试 | 新增 `FrameworkComparisonBenchmark` 并发压测四框架 CRUD 吞吐量 |
