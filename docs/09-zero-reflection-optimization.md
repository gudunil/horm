# M8.7: 零反射优化 — 编译期代理生成与反射消除

> 里程碑设计文档 · 版本 1.0 · 2026-07-12

---

## 1. 背景与动机

HORM 框架的核心设计原则之一是"运行时零反射"。M1-M8 已通过 APT 编译期代码生成在 CRUD 热路径上实现了零反射，但全框架反射审计发现仍有 **8 处反射调用**，其中 `TransactionInterceptor.method.invoke()` 在每次 `@Transactional` 方法调用时执行，是唯一的运行时热路径反射。

| # | 模块 | 文件 | 反射调用 | 热路径？ |
|---|------|------|----------|---------|
| R1 | core | `EntityMetaRegistry` | `Class.forName()` + `Method.invoke()` | ❌ 启动一次性 |
| R2 | core | `TransactionAdvisorRegistry` | `Class.forName()` + `Field.get()` | ❌ 启动一次性 |
| **R3** | **core** | **`TransactionInterceptor`** | **`method.invoke(target, args)`** | **⚠️ 每次事务方法调用** |
| R4 | cache | `CaffeineCache` | `Class.forName()` | ❌ 构造一次性 |
| R5 | cache | `RedisCache` | `Class.forName()` | ❌ 构造一次性 |
| R6 | cache | `TypeReference` | `getGenericSuperclass()` | ❌ 构造一次性 |
| R7 | starter | `HormTransactionalBeanPostProcessor` | `Proxy.newProxyInstance()` | ⚠️ Bean 初始化 |
| R8 | starter | `HormTransactionalBeanPostProcessor` | `getDeclaredMethods()` + `isAnnotationPresent()` | ⚠️ Bean 初始化 |

### 性能对比

| 调用方式 | ns/op | 相对直接调用 | GC 压力 |
|---------|-------|------------|---------|
| 直接调用 | ~1.2 | 1.0x | 无 |
| **APT 生成代理（目标）** | **~1.2** | **1.0x** | **无** |
| LambdaMetafactory 桥接 | ~3.1 | 2.6x | 低 |
| MethodHandle.invoke() | ~4.1 | 3.4x | 低 |
| Method.invoke()（当前） | ~8.5 | 7.1x | 有（Object[] 分配） |

### 行业对标

| 框架 | AOP 策略 | 反射使用 |
|------|---------|---------|
| **Micronaut** | 编译期 APT 生成拦截器子类 | **零反射** |
| **Quarkus** | 构建期元编程 | **零反射** |
| Spring 6 | 运行时代理（JDK Proxy / CGLIB） | 仍用反射 |
| **HORM（M8.7 后）** | **编译期 APT 生成事务代理子类** | **零反射** |

---

## 2. 里程碑目标

**在 M9 GA 发布前，消除 HORM 框架中所有可消除的反射调用，实现全框架零反射。**

具体目标：
1. 消除 R3（热路径 `method.invoke`）：APT 编译期生成事务代理子类
2. 消除 R7/R8（JDK 动态代理 + 注解扫描）：BeanPostProcessor 切换到 APT 代理
3. 消除 R1/R2（启动期反射）：ServiceLoader 替代自定义索引
4. 消除 R4/R5（冗余 Class.forName）：改用工厂方法 + NoClassDefFoundError
5. R6（TypeReference）保留：超级类型令牌是 Java 泛型擦除的唯一标准方案，增加 Class 重载简化

---

## 3. 技术方案

### 3.1 Phase 1：APT 生成事务代理子类（消除 R3 + R7/R8）

#### 3.1.1 核心思路

扩展现有 `TransactionAdvisorBuilder`，在编译期为每个含 `@Transactional` 方法的类生成代理子类。代理子类将事务拦截逻辑完全内联，运行时无需 `Method.invoke`、无需 `Proxy.newProxyInstance`。

此方案与 Micronaut 的编译期 AOP 模式本质相同：将运行时动态分派前移到编译期静态代码生成。

#### 3.1.2 生成示例

用户定义：

```java
public class OrderService {

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = IOException.class)
    public Order createOrder(String productId, int quantity) { ... }

    @Transactional(readOnly = true)
    public Order findOrder(Long id) { ... }

    public void validate(String productId) { ... }  // 非事务方法
}
```

APT 生成代理子类（`com.example.generated.OrderService$TransactionalProxy`）：

```java
public final class OrderService$TransactionalProxy extends OrderService {

    private static final TransactionMethodMeta CREATE_ORDER_META =
        new TransactionMethodMeta("createOrder", Propagation.REQUIRED,
            Isolation.DEFAULT, -1, false, List.of("java.io.IOException"), List.of());

    private static final TransactionMethodMeta FIND_ORDER_META =
        new TransactionMethodMeta("findOrder", Propagation.REQUIRED,
            Isolation.DEFAULT, -1, true, List.of(), List.of());

    private final OrderService delegate;
    private final HormContext ctx;

    public OrderService$TransactionalProxy(OrderService delegate, HormContext ctx) {
        this.delegate = delegate;
        this.ctx = ctx;
    }

    @Override
    public Order createOrder(String productId, int quantity) {
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(CREATE_ORDER_META.propagation())
            .isolation(CREATE_ORDER_META.isolation())
            .timeout(CREATE_ORDER_META.timeout())
            .readOnly(CREATE_ORDER_META.readOnly())
            .build();

        TransactionStatus status = TransactionManager.begin(ctx,
            DataSourceRegistry.DEFAULT_NAME, def);
        try {
            Order result = delegate.createOrder(productId, quantity); // 直接调用！
            TransactionManager.commit(status);
            return result;
        } catch (Throwable ex) {
            if (CREATE_ORDER_META.shouldRollback(ex)) {
                TransactionManager.rollback(status);
            } else {
                TransactionManager.commit(status);
            }
            rethrow(ex);
            return null;
        } finally {
            TransactionManager.popAndResume(DataSourceRegistry.DEFAULT_NAME, status);
        }
    }

    @Override
    public Order findOrder(Long id) {
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(FIND_ORDER_META.propagation())
            .isolation(FIND_ORDER_META.isolation())
            .timeout(FIND_ORDER_META.timeout())
            .readOnly(FIND_ORDER_META.readOnly())
            .build();

        TransactionStatus status = TransactionManager.begin(ctx,
            DataSourceRegistry.DEFAULT_NAME, def);
        try {
            Order result = delegate.findOrder(id); // 直接调用！
            TransactionManager.commit(status);
            return result;
        } catch (Throwable ex) {
            if (FIND_ORDER_META.shouldRollback(ex)) {
                TransactionManager.rollback(status);
            } else {
                TransactionManager.commit(status);
            }
            rethrow(ex);
            return null;
        } finally {
            TransactionManager.popAndResume(DataSourceRegistry.DEFAULT_NAME, status);
        }
    }

    @Override
    public void validate(String productId) {
        delegate.validate(productId); // 直接委托，零拦截开销
    }

    private static void rethrow(Throwable ex) {
        if (ex instanceof RuntimeException r) throw r;
        if (ex instanceof Error e) throw e;
        throw new TransactionException("Transaction method threw checked exception", ex);
    }
}
```

#### 3.1.3 TransactionProxyFactory SPI

为消除 BeanPostProcessor 中的 `Constructor.newInstance`，APT 为每个代理类生成工厂类：

```java
// 定义 SPI 接口（meta 模块）
public interface TransactionProxyFactory {
    Object create(Object delegate, HormContext ctx);
    Class<?> targetType();
}

// APT 生成
public final class OrderService$TransactionalProxyFactory implements TransactionProxyFactory {
    @Override
    public Object create(Object delegate, HormContext ctx) {
        return new OrderService$TransactionalProxy((OrderService) delegate, ctx);
    }

    @Override
    public Class<?> targetType() {
        return OrderService.class;
    }
}
```

#### 3.1.4 APT 处理器扩展

在 `HormEntityProcessor` 中扩展 `@Transactional` 处理逻辑：

```
HormEntityProcessor.process()
  ├── @Entity → MetaClassBuilder / MapperBuilder / QueryMetaBuilder (已有)
  └── @Transactional → TransactionAdvisorBuilder (已有)
                    └── TransactionProxyBuilder (新增)
                        ├── 生成 Xxx$TransactionalProxy 代理子类
                        ├── 生成 Xxx$TransactionalProxyFactory 工厂类
                        └── 写入 META-INF/services/...TransactionProxyFactory
```

#### 3.1.5 BeanPostProcessor 改造

```java
public class HormTransactionalBeanPostProcessor implements BeanPostProcessor {

    private final Map<Class<?>, TransactionProxyFactory> factoryCache = new ConcurrentHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        TransactionProxyFactory factory = factoryCache.computeIfAbsent(
            bean.getClass(), this::findFactory);
        if (factory == null) {
            return bean;
        }
        return factory.create(bean, HormContext.current()); // 直接调用！零反射！
    }

    private TransactionProxyFactory findFactory(Class<?> beanClass) {
        for (TransactionProxyFactory f : ServiceLoader.load(TransactionProxyFactory.class)) {
            if (f.targetType().equals(beanClass)) {
                return f;
            }
        }
        return null;
    }
}
```

#### 3.1.6 降级策略

对于无法生成 APT 代理的场景（第三方类、final 类、未实现接口且无 public 方法可覆盖的类），保留 `TransactionInterceptor` 作为降级路径，并改用 LambdaMetafactory 桥接替代 `Method.invoke`：

```java
// LambdaMetafactory 桥接降级（一次性创建，后续直接调用）
@FunctionalInterface
public interface MethodBridge {
    Object invoke(Object target, Object[] args) throws Throwable;
}

public final class MethodBridgeFactory {
    private static final ConcurrentHashMap<Method, MethodBridge> CACHE = new ConcurrentHashMap<>();

    public static MethodBridge bridge(Method method) {
        return CACHE.computeIfAbsent(method, m -> {
            try {
                MethodHandle mh = MethodHandles.lookup().unreflect(m);
                MethodHandle spreader = mh.asSpreader(Object[].class, m.getParameterCount());
                CallSite site = LambdaMetafactory.metafactory(
                    MethodHandles.lookup(), "invoke",
                    MethodType.methodType(MethodBridge.class),
                    MethodType.methodType(Object.class, Object.class, Object[].class),
                    spreader,
                    MethodType.methodType(Object.class, m.getDeclaringClass(), Object[].class)
                );
                return (MethodBridge) site.getTarget().invokeExact();
            } catch (Throwable t) {
                return (target, args) -> m.invoke(target, args); // 终极降级
            }
        });
    }
}
```

#### 3.1.7 设计约束

1. **代理类不能代理 `final` 方法**：如果 `@Transactional` 方法是 `final` 的，APT 在编译期发出 WARNING，运行时该方法不被拦截
2. **代理类不能代理 `final` 类**：APT 跳过 final 类，降级到 LambdaMetafactory 桥接
3. **委托模式**：代理类持有 `delegate` 字段引用原始 Bean，而非继承后覆盖字段注入——避免 Spring 注入问题
4. **非事务方法零开销**：代理类对非 `@Transactional` 方法仅做 `delegate.method()` 直接委托

---

### 3.2 Phase 2：ServiceLoader 替代自定义索引（消除 R1 + R2）

#### 3.2.1 EntityMetaRegistry 改造

```java
// 定义 SPI 接口（meta 模块）
public interface EntityMetaProvider {
    EntityMeta<?> entityMeta();
}

// APT 生成的 XxxMeta 实现 EntityMetaProvider（entityMeta() 方法签名不变）
// APT 额外生成 META-INF/services/com.holo.framework.horm.meta.EntityMetaProvider

// EntityMetaRegistry 改造
private static void loadIndex() {
    for (EntityMetaProvider provider : ServiceLoader.load(EntityMetaProvider.class)) {
        EntityMeta<?> meta = provider.entityMeta(); // 接口直接调用！零反射！
        REGISTRY.put(meta.type(), meta);
    }
}
```

#### 3.2.2 TransactionAdvisorRegistry 改造

```java
// 定义 SPI 接口（meta 模块）
public interface TransactionAdvisorProvider {
    List<TransactionMethodMeta> methods();
    String targetClassName();
}

// APT 生成的 XxxTransactionAdvisor 实现 TransactionAdvisorProvider
// APT 额外生成 META-INF/services/com.holo.framework.horm.meta.TransactionAdvisorProvider

// TransactionAdvisorRegistry 改造
private static void loadIndex() {
    for (TransactionAdvisorProvider provider : ServiceLoader.load(TransactionAdvisorProvider.class)) {
        Map<String, TransactionMethodMeta> methodMap = new ConcurrentHashMap<>();
        for (TransactionMethodMeta meta : provider.methods()) {
            methodMap.put(meta.methodName(), meta);
        }
        REGISTRY.put(provider.targetClassName(), methodMap);
    }
}
```

#### 3.2.3 兼容策略

- 保留 `entities.idx` / `transactions.idx` 文件生成，标记为 `@Deprecated`（过渡期共存）
- `EntityMetaRegistry` 优先使用 ServiceLoader，如无 SPI 文件则回退到 `entities.idx` 解析
- M9 可考虑移除自定义索引文件

---

### 3.3 Phase 3：清理冗余反射（消除 R4 + R5 + 优化 R6）

#### 3.3.1 移除 CaffeineCache / RedisCache 中的 Class.forName

当前 `CaffeineCache` 已 `import com.github.benmanes.caffeine.cache.Caffeine`，当 Caffeine 不在 classpath 时类加载就会失败，`Class.forName` 是冗余代码。

改用工厂方法 + try/catch NoClassDefFoundError：

```java
// Cache 接口增加工厂方法
public interface Cache {
    static Cache createL1(String name, CachePolicy policy) {
        try {
            return new CaffeineCache(name, policy);
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException("Caffeine is not on the classpath", e);
        }
    }

    static Cache createL2(String name, CachePolicy policy) {
        try {
            return new RedisCache(name, policy);
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException("Redisson is not on the classpath", e);
        }
    }
}
```

#### 3.3.2 TypeReference 增强

保留 `getGenericSuperclass()` 机制（超级类型令牌是 Java 生态处理泛型擦除的唯一标准方案），增加简化重载：

```java
public abstract class TypeReference<T> {
    // 现有构造器保留
    protected TypeReference() { ... }

    // 新增：简单类型工厂方法（无需匿名子类）
    public static <T> TypeReference<T> of(Class<T> type) {
        return new StaticTypeReference<>(type);
    }

    private static final class StaticTypeReference<T> extends TypeReference<T> {
        StaticTypeReference(Class<T> type) { super(type); }
    }
}
```

同时为 `Cache` 接口增加 `Class<V>` 重载，使简单类型场景无需 TypeReference：

```java
public interface Cache {
    <K, V> Optional<V> get(K key, Class<V> type);           // 简单类型
    <K, V> Optional<V> get(K key, TypeReference<V> type);   // 复杂泛型
}
```

---

## 4. 交付清单

### Phase 1：APT 事务代理生成（核心优化）

| # | 子任务 | 描述 | 模块 |
|---|--------|------|------|
| P1-1 | 定义 `TransactionProxyFactory` SPI 接口 | `create(delegate, ctx)` + `targetType()` | meta |
| P1-2 | 实现 `TransactionProxyBuilder` | JavaPoet 生成代理子类 + 工厂类 | meta |
| P1-3 | 扩展 `HormEntityProcessor` | 调用 `TransactionProxyBuilder` | meta |
| P1-4 | APT 生成 `META-INF/services/...TransactionProxyFactory` | 索引工厂类 | meta |
| P1-5 | 实现 `MethodBridgeFactory` 降级 | LambdaMetafactory 桥接 | core |
| P1-6 | 改造 `HormTransactionalBeanPostProcessor` | ServiceLoader 加载工厂 + 移除 JDK Proxy | starter |
| P1-7 | 改造 `TransactionInterceptor` | 保留作为降级路径，内部用 MethodBridgeFactory | core |
| P1-8 | APT compile-testing | 验证代理类和工厂类生成 | meta (test) |
| P1-9 | Spring Boot 集成测试 | @Transactional 方法事务拦截验证 | starter (test) |
| P1-10 | 性能基准测试 | JMH: Method.invoke vs APT 代理 vs LambdaMetafactory | benchmark |

### Phase 2：ServiceLoader 替代自定义索引

| # | 子任务 | 描述 | 模块 |
|---|--------|------|------|
| P2-1 | 定义 `EntityMetaProvider` SPI 接口 | `entityMeta()` 方法 | meta |
| P2-2 | 定义 `TransactionAdvisorProvider` SPI 接口 | `methods()` + `targetClassName()` | meta |
| P2-3 | APT 生成的 XxxMeta 实现 EntityMetaProvider | 修改 MetaClassBuilder | meta |
| P2-4 | APT 生成 `META-INF/services` 文件 | 替代 entities.idx / transactions.idx | meta |
| P2-5 | 改造 `EntityMetaRegistry` | ServiceLoader 优先 + entities.idx 兼容回退 | core |
| P2-6 | 改造 `TransactionAdvisorRegistry` | ServiceLoader 优先 + transactions.idx 兼容回退 | core |
| P2-7 | 兼容性测试 | 两种加载路径均验证 | core (test) |

### Phase 3：清理冗余反射

| # | 子任务 | 描述 | 模块 |
|---|--------|------|------|
| P3-1 | 移除 CaffeineCache Class.forName | 改用工厂方法 + NoClassDefFoundError | cache |
| P3-2 | 移除 RedisCache Class.forName | 改用工厂方法 + NoClassDefFoundError | cache |
| P3-3 | TypeReference 增加 `of(Class)` 工厂 | 简单类型无需匿名子类 | cache |
| P3-4 | Cache 接口增加 Class 重载 | `get(key, Class<V>)` | cache |
| P3-5 | 回归测试 | 全量验证 | all (test) |

---

## 5. 验收标准

### 功能验收

- [ ] 所有 `@Transactional` 方法通过 APT 代理子类实现事务拦截（非 `Method.invoke`）
- [ ] 非 `@Transactional` 方法零拦截开销（直接委托）
- [ ] `HormTransactionalBeanPostProcessor` 不再使用 `Proxy.newProxyInstance`
- [ ] `EntityMetaRegistry` 通过 `ServiceLoader` 加载元数据（非 `Class.forName` + `Method.invoke`）
- [ ] `TransactionAdvisorRegistry` 通过 `ServiceLoader` 加载元数据（非 `Class.forName` + `Field.get`）
- [ ] `CaffeineCache` / `RedisCache` 不再使用 `Class.forName`
- [ ] M1-M8.5 全部现有测试零回归

### 性能验收

- [ ] 事务方法调用延迟：APT 代理 ≤ 2ns/op（vs 当前 Method.invoke ~8.5ns/op）
- [ ] 启动时间：ServiceLoader 加载 ≤ entities.idx 加载（亚毫秒差异）
- [ ] JMH 基准测试覆盖：findById / 事务方法 / 缓存操作

### 覆盖率验收

- [ ] meta 模块 processor 包 > 80%
- [ ] core 模块 > 84%
- [ ] cache 模块 > 80%
- [ ] starter 模块 > 80%
- [ ] 集成测试 100% 通过

### 反射验收

- [ ] `TransactionInterceptor.invokeTarget()` 不再调用 `method.invoke()`（APT 代理路径）
- [ ] `HormTransactionalBeanPostProcessor` 不再调用 `Proxy.newProxyInstance()` / `getDeclaredMethods()`
- [ ] `EntityMetaRegistry` 不再调用 `Class.forName()` / `Method.invoke()`（ServiceLoader 路径）
- [ ] `TransactionAdvisorRegistry` 不再调用 `Class.forName()` / `Field.get()`（ServiceLoader 路径）
- [ ] `CaffeineCache` / `RedisCache` 不再调用 `Class.forName()`
- [ ] `TypeReference.getGenericSuperclass()` 保留（Java 泛型擦除的唯一标准方案）

---

## 6. 风险与缓解

| 风险 | 级别 | 缓解措施 |
|------|------|---------|
| 代理子类不能覆盖 `final` 方法 | 中 | APT 编译期发出 WARNING；运行时降级到 LambdaMetafactory 桥接 |
| 代理子类不能继承 `final` 类 | 中 | 降级到 LambdaMetafactory 桥接 |
| Spring CGLIB 代理冲突 | 中 | BeanPostProcessor 检测已有代理，跳过 HORM 代理 |
| ServiceLoader 在 JPMS 下行为差异 | 低 | 模块声明增加 `provides` 指令；保留 entities.idx 兼容回退 |
| LambdaMetafactory 跨模块访问限制 | 中 | APT 生成的代码与实体类同包，不存在跨模块问题 |
| 增量编译导致代理类过期 | 低 | 构建时 clean + full compile；APT round 环境检测 |

---

## 7. 预估工作量

| Phase | 工作量 | 说明 |
|-------|--------|------|
| Phase 1 | 3-5 天 | 核心优化：TransactionProxyBuilder + BeanPostProcessor 改造 |
| Phase 2 | 2-3 天 | ServiceLoader 替代 + 兼容回退 |
| Phase 3 | 1 天 | 清理冗余反射 |
| 测试 + 验证 | 1-2 天 | APT compile-testing + Spring Boot 集成测试 + JMH 基准 |
| **合计** | **7-11 天** | |

---

## 8. 里程碑依赖

```
M8.5 (已完成) ──► M8.7 (本里程碑) ──► M9 (GA 发布)
```

M8.7 依赖 M8.5 已完成的方言适配和 M8 的 Spring Boot Starter 基础设施。M9 GA 发布将包含 M8.7 的零反射优化成果。
