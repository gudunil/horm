[根目录](../../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **holo-horm-spring-boot-starter**

# Holo :: HORM :: Spring Boot Starter

## 模块职责

Spring Boot 自动装配，提供 `HormAutoConfiguration`、`HormProperties`、`HormTransactionManager`（PlatformTransactionManager 实现）、`HormMetricsAutoConfiguration`、`HormHealthIndicator` 等集成。当前为规划阶段，仅 POM 已建。

- **parent**: `holo-horm` (1.0.0-SNAPSHOT)
- **artifactId**: `holo-horm-spring-boot-starter`
- **packaging**: jar

## 规划功能

| 功能 | 说明 |
|------|------|
| `HormAutoConfiguration` | 自动装配 HormContext、DataSourceRegistry、CacheChain |
| `HormProperties` | `holo.horm.*` 配置项 |
| `HormTransactionManager` | PlatformTransactionManager 实现 |
| `@Transactional` AOP 织入 | 运行时方法拦截，使用 M4 生成的事务元数据 |
| `HormMetricsAutoConfiguration` | Micrometer 指标注册 |
| `HormHealthIndicator` | Actuator 健康检查 |
| 缓存配置 | 注解式 `@Cacheable`/`@CacheInvalidate`/`@CachePut` |

## 当前状态

- **POM**: 已建
- **源代码**: 无

## 相关文件清单

| 文件路径 | 说明 |
|---------|------|
| `holo-horm/holo-horm-spring-boot-starter/pom.xml` | 模块 POM |

## 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-07-06 | 初始文档生成 | 架构扫描 |