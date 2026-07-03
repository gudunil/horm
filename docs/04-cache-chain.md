# HORM 缓存链系统设计

> 本文档详述 HORM 多级缓存链系统的架构、SPI、策略组合、键设计、一致性保证、性能优化与使用方式。

---

## 一、设计目标

1. **多级组合**：支持 L1（本地）/ L2（分布式）/ L3（远端）多级缓存链式组合
2. **策略可插拔**：LRU / LFU / TTL / W-TinyLFU / 写穿透 / 写回 / 读穿透等策略任意组合
3. **零业务侵入**：缓存对业务代码透明，通过注解或配置启用
4. **强一致性可选**：默认最终一致，可选强一致模式（写穿透 + 失效广播）
5. **可观测**：命中率、延迟、淘汰、错误率等指标暴露
6. **数据源无关**：缓存链对 SQL / NoSQL / REST / 文件数据源统一生效

---

## 二、架构总览

```
┌─────────────────────────── HORM Cache Chain ───────────────────────────┐
│                                                                          │
│                          业务请求（Query / save / update / delete）      │
│                                       │                                  │
│                                       ▼                                  │
│                          ┌────────────────────────┐                     │
│                          │   CacheChain (入口)     │                     │
│                          └────────────┬───────────┘                     │
│                                       │                                  │
│         ┌─────────────────────────────┼─────────────────────────┐      │
│         │                             │                          │      │
│         ▼                             ▼                          ▼      │
│  ┌──────────────┐         ┌──────────────────┐       ┌──────────────┐  │
│  │  L1: Local   │ ──miss─►│  L2: Distributed │ ─miss►│  L3: Remote  │  │
│  │  (Caffeine)  │         │  (Redis)         │       │  (DB / HTTP) │  │
│  └──────┬───────┘         └────────┬─────────┘       └──────┬───────┘  │
│         │ hit                       │ hit                    │           │
│         │                           │                        │           │
│         ▼  回填                      ▼  回填                  ▼           │
│       返回                         返回                    加载         │
│                                                                │         │
│                                                                ▼         │
│                                                          ┌────────────┐   │
│                                                          │ CacheLoader│   │
│                                                          │ (回填 L2/L1)│  │
│                                                          └────────────┘   │
│                                                                           │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                          策略层                                    │  │
│  │   TTL  │  LRU  │  LFU  │  W-TinyLFU  │  写穿透  │  写回  │ ...    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                           │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                          事件层                                    │  │
│  │   Hit / Miss / Evict / Expire / Invalidate / Error   → 监听/广播  │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                           │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                          监控层                                    │  │
│  │   命中率 / 延迟 / 淘汰数 / 错误率 → Micrometer → Prometheus/Grafana│  │
│  └───────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 三、核心 SPI

### 3.1 `Cache` 接口（单层缓存）

```java
public interface Cache extends AutoCloseable {

    /** 缓存名称（如 "L1-caffeine"） */
    String name();

    /** 层级（L1 / L2 / L3） */
    CacheLevel level();

    /** 读取 */
    <K, V> Optional<V> get(K key, TypeReference<V> type);

    /** 写入 */
    <K, V> void put(K key, V value, CachePolicy policy);

    /** 读取，未命中则用 loader 加载并写入 */
    <K, V> Optional<V> get(K key, TypeReference<V> type, Supplier<V> loader, CachePolicy policy);

    /** 批量读取 */
    <K, V> Map<K, V> getAll(Set<K> keys, TypeReference<V> type);

    /** 批量写入 */
    <K, V> void putAll(Map<K, V> entries, CachePolicy policy);

    /** 失效单条 */
    <K> void invalidate(K key);

    /** 失效批量 */
    <K> void invalidateAll(Set<K> keys);

    /** 失效全部 */
    void invalidateAll();

    /** 当前缓存统计 */
    CacheStats stats();

    @Override
    void close();
}
```

### 3.2 `CacheChain` 接口（多级组合）

```java
public interface CacheChain extends Cache {

    /** 当前链中的所有层级（按 L1→L2→L3 顺序） */
    List<Cache> levels();

    /** 添加层级到链尾 */
    CacheChain append(Cache cache);

    /** 在指定层级后插入 */
    CacheChain insertAfter(CacheLevel level, Cache cache);

    /** 移除指定层级 */
    CacheChain remove(CacheLevel level);
}
```

### 3.3 `CachePolicy` 类（策略声明）

```java
public final class CachePolicy {
    private final Duration ttl;                  // 过期时间
    private final EvictionPolicy evictionPolicy; // 淘汰策略（LRU/LFU/W-TinyLFU）
    private final int maxEntries;                // 最大条目数
    private final long maxWeight;                // 最大权重（按字节数或自定义）
    private final WriteStrategy writeStrategy;   // 写策略（穿透/回/绕过）
    private final boolean nullable;              // 是否缓存 null（防穿透）
    private final Duration nullTtl;              // null 缓存 TTL

    public static CachePolicyBuilder builder() { return new CachePolicyBuilder(); }
}
```

### 3.4 `CacheLoader` / `CacheWriter` SPI

```java
@FunctionalInterface
public interface CacheLoader<K, V> {
    V load(K key) throws Exception;
}

@FunctionalInterface
public interface CacheWriter<K, V> {
    void write(K key, V value) throws Exception;
}
```

### 3.5 `CacheEvent` 与监听器

```java
public record CacheEvent(
    CacheEventType type,    // HIT / MISS / EVICT / EXPIRE / INVALIDATE / ERROR
    String cacheName,
    CacheLevel level,
    Object key,
    Object value,
    Instant timestamp,
    Throwable error
) {}

public interface CacheEventListener {
    void onEvent(CacheEvent event);
}
```

### 3.6 `CacheStats` 类

```java
public record CacheStats(
    long hits,
    long misses,
    long evictions,
    long expirations,
    long loads,
    long loadFailures,
    Duration averageLoadTime,
    long estimatedSize
) {
    public double hitRate() { return hits + misses == 0 ? 0 : (double) hits / (hits + misses); }
}
```

---

## 四、参考实现

### 4.1 L1：Caffeine 本地缓存

```java
public class CaffeineCache implements Cache {
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> cache;
    private final Serializer serializer;

    @Override
    public <K, V> Optional<V> get(K key, TypeReference<V> type) {
        Object v = cache.getIfPresent(key);
        return v == null ? Optional.empty() : Optional.of((V) v);
        // L1 直接持有对象引用，零拷贝、零序列化
    }

    @Override
    public <K, V> void put(K key, V value, CachePolicy policy) {
        CaffeineSpec spec = CaffeineSpec.from(policy);
        // 注：Caffeine 的淘汰策略在 builder 时确定，运行时不可变
        // HORM 提供多桶策略，按 policy.ttl 分桶
        cache.put(key, value);
    }
}
```

### 4.2 L2：Redis 分布式缓存

```java
public class RedisCache implements Cache {
    private final RedissonClient redisson;
    private final String namespace;
    private final Serializer serializer;

    @Override
    public <K, V> Optional<V> get(K key, TypeReference<V> type) {
        RBucket<byte[]> bucket = redisson.getBucket(namespace + ":" + key);
        byte[] bytes = bucket.get();
        if (bytes == null) return Optional.empty();
        return Optional.of(serializer.deserialize(bytes, type));
    }

    @Override
    public <K, V> void put(K key, V value, CachePolicy policy) {
        RBucket<byte[]> bucket = redisson.getBucket(namespace + ":" + key);
        byte[] bytes = serializer.serialize(value);
        if (policy.ttl() != null) {
            bucket.set(bytes, policy.ttl());
        } else {
            bucket.set(bytes);
        }
    }

    @Override
    public <K> void invalidate(K key) {
        redisson.getBucket(namespace + ":" + key).delete();
        // 同时广播失效事件，触发其他节点的 L1 失效
        redisson.getTopic(namespace + ":invalidate").publish(key);
    }
}
```

### 4.3 L3：远端缓存（数据源本身）

L3 通常即数据源本身（DB / Mongo / REST），由 `CacheLoader` 调用 Repository 加载。

### 4.4 `DefaultCacheChain` 实现

```java
public class DefaultCacheChain implements CacheChain {
    private final List<Cache> levels;
    private final EventBus eventBus;
    private final MetricsCollector metrics;

    @Override
    public <K, V> Optional<V> get(K key, TypeReference<V> type, Supplier<V> loader, CachePolicy policy) {
        // 1. 逐层查找
        for (int i = 0; i < levels.size(); i++) {
            Cache cache = levels.get(i);
            Optional<V> v = cache.get(key, type);
            if (v.isPresent()) {
                metrics.recordHit(cache.level(), key);
                // 回填上层
                for (int j = 0; j < i; j++) {
                    levels.get(j).put(key, v.get(), policy);
                }
                return v;
            }
            metrics.recordMiss(cache.level(), key);
        }

        // 2. 全未命中，加载
        V value;
        try {
            value = loader.get();
        } catch (Exception e) {
            metrics.recordLoadFailure(key, e);
            throw new CacheLoadException("Failed to load value for key: " + key, e);
        }
        metrics.recordLoad(key);

        // 3. 回填所有层
        if (value != null || policy.nullable()) {
            for (Cache cache : levels) {
                cache.put(key, value, policy);
            }
        }

        return Optional.ofNullable(value);
    }

    @Override
    public <K> void invalidate(K key) {
        // 1. 失效所有层
        for (Cache cache : levels) {
            cache.invalidate(key);
        }
        // 2. 广播事件
        eventBus.publish(new CacheEvent(INVALIDATE, "chain", null, key, null, Instant.now(), null));
    }
}
```

---

## 五、缓存键设计

### 5.1 键结构

```
{entityType}:{partition}:{keyType}:{keyValue}:{version}
```

示例：
- `User:id:123:v1` - 按 ID 查询用户
- `User:email:a@b.com:v1` - 按 email 查询用户
- `User:query:hash(conditions+order+limit):v1` - 复杂查询缓存

### 5.2 分区

支持按业务维度分区，避免热点：
- `User:tenant_001:id:123`
- `User:tenant_002:id:123`

分区键由 `PartitionResolver` 决定（默认无分区，可配置按租户 ID / 数据源 / 自定义）。

### 5.3 版本控制

每次 schema 变更或 EntityMeta 重生成，版本号自动 +1，旧缓存自然失效，避免兼容性问题。

### 5.4 敏感字段处理

缓存键不包含敏感字段值，改用 SHA-256 hash：
- 不安全：`User:password:abc123`
- 安全：`User:password:hash:5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8`

### 5.5 Hash 计算（复杂查询）

```java
public static String queryHash(Query<?> query) {
    StringBuilder sb = new StringBuilder();
    sb.append(query.entityType().getName());
    sb.append(query.conditions().stream().map(Object::toString).sorted().collect(Collectors.joining(",")));
    sb.append(query.orders().stream().map(Object::toString).collect(Collectors.joining(",")));
    sb.append(query.offset()).append(":").append(query.limit());
    return DigestUtils.sha256Hex(sb.toString()).substring(0, 16);
}
```

---

## 六、读写策略

### 6.1 Read-Through（读穿透，默认）

```java
User user = User.find(User.class, 1L);
// → CacheChain.get(key, type, () -> repository.findById(id), policy)
// → 未命中则自动从 Repository 加载并回填
```

### 6.2 Cache-Aside（旁路）

业务代码控制缓存：
```java
Optional<User> cached = cacheChain.get(key, type);
User user = cached.orElseGet(() -> {
    User u = repository.findById(id);
    cacheChain.put(key, u, policy);
    return u;
});
```

### 6.3 Write-Through（写穿透）

`save()` / `update()` 时：
1. 写入数据源
2. 同步写入缓存链所有层

```java
public <T extends Model<T>> T save(T entity) {
    repository.save(entity);
    cacheChain.put(cacheKey(entity), entity, policy);
    return entity;
}
```

### 6.4 Write-Behind（写回）

`save()` 时：
1. 写入缓存（仅 L1 或 L2）
2. 异步刷入数据源

```java
public <T extends Model<T>> T save(T entity) {
    cacheChain.put(cacheKey(entity), entity, policy);
    asyncWriter.offer(() -> repository.save(entity));
    return entity;
}
```

风险：进程崩溃时未刷入数据源的数据丢失。仅适合可容忍丢失的场景（如审计日志、统计数据）。

### 6.5 Write-Around（写绕过）

`save()` 时仅写数据源，不写缓存。下次读取时自然回填。适合写多读少的场景。

### 6.6 失效模式（推荐）

`update()` / `delete()` 时仅失效缓存，不主动写入：

```java
public <T extends Model<T>> T update(T entity) {
    repository.update(entity);
    cacheChain.invalidate(cacheKey(entity)); // 失效，下次读时回填
    return entity;
}
```

适合读多写少场景，避免写穿透时的并发覆盖问题。

---

## 七、一致性保证

### 7.1 三种一致性级别

| 级别 | 描述 | 适用场景 |
|------|------|---------|
| **强一致** | 写穿透 + 同步失效广播 + 单飞加载 | 金融、库存 |
| **最终一致** | 失效模式 + 异步广播（默认） | 通用场景 |
| **弱一致** | TTL 自然过期，无失效广播 | 缓存预热、统计 |

### 7.2 失效广播

L2 失效时通过 Redis Pub/Sub 广播到所有节点，触发 L1 失效：

```java
// 监听失效广播
redisson.getTopic(namespace + ":invalidate").addListener(String.class, (channel, key) -> {
    l1Cache.invalidate(key);
    metrics.recordInvalidateByBroadcast(key);
});
```

支持 Kafka / RocketMQ 作为广播通道（适合大规模集群）。

### 7.3 单飞加载（防击穿）

热点 key 失效时，并发请求只允许一个进入数据源加载，其他等待：

```java
public class SingleFlightLoader<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    public V load(K key, Supplier<V> loader) {
        while (true) {
            CompletableFuture<V> future = inFlight.get(key);
            if (future == null) {
                CompletableFuture<V> newFuture = new CompletableFuture<>();
                if (inFlight.putIfAbsent(key, newFuture) == null) {
                    try {
                        V value = loader.get();
                        newFuture.complete(value);
                        return value;
                    } catch (Exception e) {
                        newFuture.completeExceptionally(e);
                        throw e;
                    } finally {
                        inFlight.remove(key);
                    }
                }
            } else {
                try {
                    return future.get(30, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    inFlight.remove(key, future);
                    // 重试
                }
            }
        }
    }
}
```

### 7.4 缓存穿透防护

- **空值缓存**：查询返回 null 时，缓存特殊标记 `NULL_MARKER`，TTL 较短（如 1 分钟）
- **布隆过滤器**：启动时加载所有 ID 到布隆过滤器，查询前先过滤不存在的 key

### 7.5 缓存雪崩防护

- **TTL 抖动**：`ttl = baseTtl + random(0, 10% * baseTtl)`，避免同时大量失效
- **限流降级**：数据源加载失败时，返回旧值或降级响应（需 `CachePolicy.allowStale(true)`）

---

## 八、序列化

### 8.1 序列化器 SPI

```java
public interface Serializer {
    byte[] serialize(Object obj) throws SerializationException;
    <T> T deserialize(byte[] bytes, TypeReference<T> type) throws SerializationException;
    String name();
}
```

### 8.2 内置实现

| 序列化器 | 速度 | 体积 | 跨语言 | 推荐 |
|---------|------|------|-------|------|
| Protostuff | 极快 | 小 | 否 | ✓ L1/L2 默认 |
| Kryo | 极快 | 小 | 否 | 可选 |
| Jackson JSON | 中 | 中 | ✓ | 跨语言场景 |
| JDK Serializable | 慢 | 大 | 是 Java | 不推荐 |
| Hessian2 | 快 | 中 | ✓ | 跨语言可选 |

### 8.3 L1 零序列化

L1（本地缓存）默认直接持有对象引用，无需序列化，性能最高。但要注意：
- 修改 L1 中的对象会污染缓存（建议返回不可变副本或防御性拷贝）
- 大对象占内存，需控制 maxWeight

---

## 九、注解驱动使用

### 9.1 实体级注解

```java
@Entity(table = "users")
@Cached(
    levels = {CacheLevel.L1, CacheLevel.L2},
    policy = @CachePolicy(ttl = "30m", eviction = EvictionPolicy.LRU, maxEntries = 10000)
)
public class User extends Model<User> { ... }
```

### 9.2 方法级注解

```java
public class UserService {
    @Cacheable(key = "'user:' + #id", policy = @CachePolicy(ttl = "1h"))
    public User findById(Long id) {
        return User.find(User.class, id);
    }

    @CacheInvalidate(key = "'user:' + #user.id")
    public void update(User user) {
        user.update();
    }

    @CachePut(key = "'user:' + #user.id")
    public User save(User user) {
        return user.save();
    }
}
```

### 9.3 配置式

```yaml
holo:
  horm:
    cache:
      enabled: true
      chain:
        - level: L1
          type: caffeine
          spec: maximumSize=10000,expireAfterWrite=30m
        - level: L2
          type: redis
          namespace: holo
          ttl: 1h
      default-policy:
        ttl: 30m
        eviction: LRU
        max-entries: 10000
        nullable: true
        null-ttl: 1m
```

---

## 十、性能优化

### 10.1 异步回填

未命中时数据源查询同步返回，回填缓存异步化：

```java
public <K, V> Optional<V> get(K key, TypeReference<V> type, Supplier<V> loader, CachePolicy policy) {
    // 1. 查找缓存
    for (Cache cache : levels) {
        Optional<V> v = cache.get(key, type);
        if (v.isPresent()) {
            // 异步回填上层（如果上层未命中）
            asyncBackfillUpperLevels(key, v.get(), policy, cache.level());
            return v;
        }
    }

    // 2. 同步加载
    V value = loader.get();

    // 3. 同步写 L2（持久化层）、异步写 L1
    if (value != null || policy.nullable()) {
        getL2().put(key, value, policy);
        asyncWriteL1(key, value, policy);
    }

    return Optional.ofNullable(value);
}
```

### 10.2 预加载

根据访问模式预取热数据：

```java
public class WarmupLoader implements CacheEventListener {
    @Override
    public void onEvent(CacheEvent event) {
        if (event.type() == HIT && isHotKey(event.key())) {
            // 预加载相关 key
            prefetch(relatedKeys(event.key()));
        }
    }
}
```

### 10.3 批量化

`getAll(Set<K>)` 批量查询，减少 RTT。Caffeine 与 Redis 都支持 MGET。

```java
Map<K, V> getAll(Set<K> keys, TypeReference<V> type) {
    // 1. L1 批量查询
    Map<K, V> result = new HashMap<>(l1.getAll(keys, type));

    // 2. L1 未命中的 key，去 L2 批量查询
    Set<K> missingKeys = Sets.difference(keys, result.keySet());
    if (!missingKeys.isEmpty()) {
        Map<K, V> l2Result = l2.getAll(missingKeys, type);
        result.putAll(l2Result);
        // 回填 L1
        l1.putAll(l2Result, policy);
    }

    // 3. 仍未命中的，从数据源批量加载
    Set<K> stillMissing = Sets.difference(keys, result.keySet());
    if (!stillMissing.isEmpty()) {
        Map<K, V> loaded = batchLoader.loadAll(stillMissing);
        result.putAll(loaded);
        l1.putAll(loaded, policy);
        l2.putAll(loaded, policy);
    }

    return result;
}
```

### 10.4 多级一致性优化

写穿透时按 L2 → L1 顺序写入，避免 L1 旧值被并发读取后覆盖 L2：
1. 写入 L2（同步）
2. 失效 L1（同步）
3. 下次读取时从 L2 加载到 L1

---

## 十一、监控

### 11.1 Micrometer 指标

```
horm_cache_hits_total{level="L1",cache="caffeine",entity="User"} 12345
horm_cache_misses_total{level="L1",cache="caffeine",entity="User"} 678
horm_cache_hit_ratio{level="L1",cache="caffeine",entity="User"} 0.948
horm_cache_evictions_total{level="L1",cache="caffeine",entity="User"} 12
horm_cache_load_duration_seconds{level="L2",cache="redis",entity="User"} 0.023
horm_cache_size{level="L1",cache="caffeine",entity="User"} 8234
```

### 11.2 Grafana Dashboard

HORM 提供 Grafana Dashboard JSON 模板，开箱即用，展示：
- 各层级命中率趋势
- 加载延迟分布（P50/P95/P99）
- 淘汰与失效统计
- 错误率与降级次数

---

## 十二、与其他缓存框架对比

| 框架 | 多级组合 | 失效广播 | 强一致可选 | 数据源集成 | Spring 集成 |
|------|---------|---------|----------|----------|-----------|
| Spring Cache | ✗ | ✗ | ✗ | ✗ | ✓ |
| JetCache | ✓ | ✓（多级） | 部分 | ✗ | ✓ |
| J2Cache | ✓ | ✓ | ✓ | ✗ | ✓ |
| HORM CacheChain | ✓ | ✓ | ✓ | ✓（与 ORM 深度集成） | ✓ |

HORM 缓存链的核心差异化：与 ORM 元数据深度集成，缓存键自动生成，失效策略与数据源写操作自动联动。

---

## 十三、参考资源

- [Caffeine 文档](https://github.com/ben-manes/caffeine)
- [Redisson 文档](https://github.com/redisson/redisson/wiki)
- [JetCache](https://github.com/alibaba/jetcache)
- [J2Cache](https://gitee.com/ld/J2Cache)
- [Cache Patterns (Microsoft)](https://learn.microsoft.com/en-us/azure/architecture/patterns/category/caching)
- [W-TinyLFU 论文](https://arxiv.org/abs/1512.00727)
