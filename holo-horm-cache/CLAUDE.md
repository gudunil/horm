[根目录](../../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **holo-horm-cache**

# Holo :: HORM :: Cache (缓存链系统)

## 模块职责

HORM 多级缓存链系统，采用责任链模式实现可组合的多级缓存。支持 L1（Caffeine 本地缓存）、L2（Redis 分布式缓存 stub）、NoOp 兜底，以及单飞加载、TTL 抖动、事件发布、统计收集等高级特性。

- **parent**: `holo-horm` (1.0.0-SNAPSHOT)
- **artifactId**: `holo-horm-cache`
- **packaging**: jar

## 子包结构

```
com.holo.framework.horm.cache
  ├── Cache                  单层缓存 SPI（get/put/getAll/putAll/invalidate）
  ├── CacheChain             多级链组合接口（append/insertAfter/remove + getAll with batchLoader）
  ├── DefaultCacheChain      默认链实现（逐层查找、上层回填、NullMarker、事件发布）
  ├── CaffeineCache          L1 本地缓存实现（零序列化、TTL/size 淘汰）
  ├── RedisCache             L2 Redis stub 实现（Redisson 可选）
  ├── NoOpCache              无操作兜底
  ├── CachePolicy            缓存策略（TTL/LRU/LFU/W-TinyLFU/写穿透/写回）
  ├── CachePolicyBuilder     策略 Builder
  ├── CacheLevel             层级枚举（L1/L2/L3）
  ├── WriteStrategy          写策略枚举（THROUGH/AROUND/BEHIND）
  ├── EvictionPolicy         淘汰策略枚举（LRU/LFU/W-TinyLFU）
  ├── CacheStats             缓存统计（hits/misses/evictions/expirations/loads）
  ├── CacheEvent / CacheEventType / CacheEventListener  事件系统
  ├── CacheException / CacheLoadException  异常
  ├── Serializer / JdkSerializer  序列化 SPI + JDK 实现
  ├── SingleFlightLoader     单飞加载（缓存击穿防护）
  ├── TtlJitter              TTL 抖动器（缓存雪崩防护）
  ├── NullMarker             空值缓存标记（缓存穿透防护）
  ├── TypeReference          类型引用（反序列化类型恢复）
  └── key/
      ├── CacheKey / CacheKeyBuilder  缓存键构建
      ├── QueryHash           查询哈希
      └── SensitiveHash       敏感字段哈希
```

## 对外接口

### Cache SPI

| 方法 | 说明 |
|------|------|
| `name()` | 缓存名称 |
| `level()` | 缓存层级 |
| `get(K, TypeReference<V>)` | 单键读取 |
| `get(K, TypeReference<V>, Supplier<V>, CachePolicy)` | 读穿透（未命中时加载） |
| `getAll(Set<K>, TypeReference<V>)` | 批量读取 |
| `put(K, V, CachePolicy)` | 写入 |
| `putAll(Map<K,V>, CachePolicy)` | 批量写入 |
| `invalidate(K)` | 单键失效 |
| `invalidateAll(Set<K>)` | 批量失效 |
| `invalidateAll()` | 全量失效 |
| `stats()` | 统计快照 |
| `close()` | 释放资源 |

### CacheChain 扩展

| 方法 | 说明 |
|------|------|
| `levels()` | 返回层级列表 |
| `append(Cache)` | 追加层级到链尾 |
| `insertAfter(CacheLevel, Cache)` | 在指定层级后插入 |
| `remove(CacheLevel)` | 移除指定层级 |
| `getAll(Set<K>, TypeReference<V>, Function<Set<K>,Map<K,V>>, CachePolicy)` | 批量读穿透 |

### CachePolicy 策略

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| ttl | Duration | 无 | 过期时间 |
| evictionPolicy | EvictionPolicy | LRU | 淘汰策略 |
| maxEntries | int | 0 (不限) | 最大条目数 |
| maxWeight | long | 0 (不限) | 最大权重 |
| writeStrategy | WriteStrategy | AROUND | 写策略 |
| nullable | boolean | true | 是否缓存 null |
| nullTtl | Duration | 1 分钟 | null 缓存 TTL |

## 关键设计

### 缓存键结构

```
{entityType}:{partition}:{keyType}:{keyValue}:{version}
```

示例: `User:tenant_001:id:123:v1`

### 缓存链穿透处理

1. **缓存穿透**: 布隆过滤器 + 空值缓存 (NullMarker + 短 TTL)
2. **缓存击穿**: SingleFlightLoader 单飞加载
3. **缓存雪崩**: TtlJitter 随机抖动 (`baseTtl + random(0, 10%)`)

### 一致性保证

- 强一致: 写穿透 + 失效广播 + 单飞加载
- 最终一致（默认）: 失效模式 + 异步广播
- 弱一致: TTL 自然过期

### 三层参考拓扑

```
读路径: L1 (Caffeine) → L2 (Redis) → L3 (数据源/DB)
写路径: 失效 L1 → 失效 L2 (同步) → 下次读时从 DB 回填
```

## 测试与质量

- **测试总数**: 346
- **覆盖目标**: cache 模块 > 80%
- **关键测试**: `DefaultCacheChainTest`、`CaffeineCacheTest`、`NoOpCacheTest`、`SingleFlightLoaderTest`、`CachePolicyTest`、`CacheStatsTest`

## 相关文件清单

| 文件路径 | 说明 |
|---------|------|
| `holo-horm/holo-horm-cache/src/main/java/com/holo/framework/horm/cache/DefaultCacheChain.java` | 默认链实现 |
| `holo-horm/holo-horm-cache/src/main/java/com/holo/framework/horm/cache/CaffeineCache.java` | L1 本地缓存 |
| `holo-horm/holo-horm-cache/src/main/java/com/holo/framework/horm/cache/RedisCache.java` | L2 Redis stub |
| `holo-horm/holo-horm-cache/src/main/java/com/holo/framework/horm/cache/SingleFlightLoader.java` | 单飞加载 |
| `holo-horm/holo-horm-cache/src/main/java/com/holo/framework/horm/cache/DefaultCacheChain.java` | 默认多级链 |

## 已知限制

- **RedisCache**: 为 stub 实现，未启动真实 Redis 实例验证
- **BEHIND 写策略**: 已定义但未实现异步写逻辑
- **查询缓存**: 仅支持单表、无 JOIN、无投影、THROUGH 模式
- **CaffeineCache**: 不支持 per-entry TTL，使用全局 TTL
- **Redis 统计**: 暂未实现（Redisson 无原生 stats）

## 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-07-06 | 初始文档生成 | 架构扫描 |