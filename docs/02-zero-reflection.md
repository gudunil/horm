# HORM 零反射实现方案

> 本文档详述 HORM 框架如何通过编译期注解处理器（APT）实现运行时零反射，包括元数据模型、生成策略、降级机制与 AOT 兼容性。

---

## 一、设计目标

1. **运行时零反射**：禁用 `Class.forName` / `Field.getXxx` / `Method.invoke` 等反射调用
2. **类型安全**：生成的元模型类提供编译期类型检查的查询 DSL
3. **AOT/原生镜像兼容**：无运行时字节码生成，兼容 GraalVM Native Image
4. **构建性能**：APT 增量编译，全量构建增加时间控制在 3 秒以内
5. **可调试**：生成代码可读、可断点、IDE 可跳转
6. **降级路径**：非 HORM 实体（如第三方类）支持 Lambda 访问器，仍接近原生性能

---

## 二、元数据模型

### 2.1 编译期与运行时元数据

HORM 元数据分为两层：

| 层级 | 生成时机 | 表示形式 | 用途 |
|------|---------|---------|------|
| 静态元数据 | 编译期 (APT) | 生成的 Java 类 (`XxxMeta`) | 类型安全 DSL、字段引用 |
| 运行时元数据 | 启动时（一次性） | `EntityMeta<T>` 实例 | 通用元数据访问 |

### 2.2 静态元数据：`EntityMeta` 类

APT 为每个 `@Entity` 类生成同名 + `Meta` 后缀的类：

```java
// 用户定义
@Entity(table = "users")
public class User extends Model<User> {
    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 128)
    private String email;

    @Column(name = "created_at")
    private Instant createdAt;

    @HasMany(foreignKey = "user_id")
    private List<Order> orders;

    // getters/setters ...
}

// APT 生成：com/holo/framework/horm/generated/UserMeta.java
public final class UserMeta {

    public static final String TABLE_NAME = "users";
    public static final Class<User> ENTITY_TYPE = User.class;

    // 字段元数据：每个字段一个静态常量
    public static final FieldMeta<Long> ID = FieldMeta.<Long>builder()
        .name("id")
        .column("id")
        .type(Long.class)
        .id(true)
        .generationStrategy(GenerationType.IDENTITY)
        .build();

    public static final FieldMeta<String> EMAIL = FieldMeta.<String>builder()
        .name("email")
        .column("email")
        .type(String.class)
        .nullable(false)
        .length(128)
        .build();

    public static final FieldMeta<Instant> CREATED_AT = FieldMeta.<Instant>builder()
        .name("createdAt")
        .column("created_at")
        .type(Instant.class)
        .build();

    // 关联元数据
    public static final RelationMeta ORDERS = RelationMeta.builder()
        .name("orders")
        .targetEntity(Order.class)
        .type(RelationType.HAS_MANY)
        .foreignKey("user_id")
        .build();

    // 字段访问器（Lambda 实现，零反射）
    public static final FieldAccessor<User, Long> ID_ACCESSOR =
        FieldAccessor.of(User::getId, User::setId);

    public static final FieldAccessor<User, String> EMAIL_ACCESSOR =
        FieldAccessor.of(User::getEmail, User::setEmail);

    public static final FieldAccessor<User, Instant> CREATED_AT_ACCESSOR =
        FieldAccessor.of(User::getCreatedAt, User::setCreatedAt);

    // Mapper 单例（实例化与映射零反射）
    public static final Mapper<User> MAPPER = UserMapper.INSTANCE;

    // 全部字段集合（用于 INSERT、SELECT * 等）
    public static final List<FieldMeta<?>> ALL_FIELDS =
        List.of(ID, EMAIL, CREATED_AT);

    public static EntityMeta<User> entityMeta() {
        return EntityMeta.<User>builder()
            .type(User.class)
            .tableName(TABLE_NAME)
            .fields(ALL_FIELDS)
            .idField(ID)
            .mapper(MAPPER)
            .relations(List.of(ORDERS))
            .build();
    }
}
```

### 2.3 运行时元数据：`EntityMeta<T>`

```java
public final class EntityMeta<T> {
    private final Class<T> type;
    private final String tableName;
    private final List<FieldMeta<?>> fields;
    private final FieldMeta<?> idField;
    private final Mapper<T> mapper;
    private final List<RelationMeta> relations;
    // ...

    public FieldMeta<?> field(String name) { /* by name */ }
    public FieldMeta<?> fieldByColumn(String column) { /* by column */ }
}
```

启动时由 `EntityMetaRegistry` 扫描 `META-INF/horm/entities.idx`（APT 生成的索引文件），实例化每个 `XxxMeta.entityMeta()` 并注册。**全程零反射**：APT 已生成索引文件，启动时按 ServiceLoader 风格调用静态方法。

### 2.4 Mapper 接口与生成实现

```java
public interface Mapper<T> {
    T map(Row row);
    Row toRow(T entity);
    Object getId(T entity);
    void setId(T entity, Object id);
    void setField(T entity, String field, Object value);  // 通用字段设置
    Object getField(T entity, String field);
}
```

APT 生成实现：

```java
// APT 生成：UserMapper.java
final class UserMapper implements Mapper<User> {
    static final UserMapper INSTANCE = new UserMapper();

    @Override
    public User map(Row row) {
        User u = new User();
        if (row.has("id"))      u.setId(row.getLong("id"));
        if (row.has("email"))   u.setEmail(row.getString("email"));
        if (row.has("created_at")) u.setCreatedAt(row.getInstant("created_at"));
        return u;
    }

    @Override
    public Row toRow(User u) {
        Row row = Row.create(UserMeta.TABLE_NAME);
        if (u.getId() != null) row.setLong("id", u.getId());
        row.setString("email", u.getEmail());
        if (u.getCreatedAt() != null) row.setInstant("created_at", u.getCreatedAt());
        return row;
    }

    @Override
    public Object getId(User u) { return u.getId(); }

    @Override
    public void setId(User u, Object id) { u.setId((Long) id); }

    @Override
    public Object getField(User u, String field) {
        return switch (field) {
            case "id"        -> u.getId();
            case "email"     -> u.getEmail();
            case "createdAt" -> u.getCreatedAt();
            default -> throw new IllegalArgumentException("Unknown field: " + field);
        };
    }

    @Override
    public void setField(User u, String field, Object value) {
        switch (field) {
            case "id"        -> u.setId((Long) value);
            case "email"     -> u.setEmail((String) value);
            case "createdAt" -> u.setCreatedAt((Instant) value);
            default -> throw new IllegalArgumentException("Unknown field: " + field);
        }
    }
}
```

**关键点**：
- `map(Row)` 直接调用 setter，无 `Field.set`
- `toRow(T)` 直接调用 getter，无 `Field.get`
- `setField` / `getField` 通过 `switch` 分发，编译期为 tableswitch/lookupswitch，性能极高

---

## 三、APT 处理器实现

### 3.1 处理器入口

```java
@SupportedAnnotationTypes({
    "com.holo.framework.horm.meta.annotation.Entity",
    "com.holo.framework.horm.meta.annotation.Embeddable",
    "com.holo.framework.horm.meta.annotation.MappedSuperclass"
})
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class HormEntityProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        if (env.processingOver()) {
            writeIndexFile();
            return false;
        }

        for (Element e : env.getElementsAnnotatedWith(Entity.class)) {
            TypeElement type = (TypeElement) e;
            EntityDescriptor descriptor = EntityDescriptorParser.parse(type, processingEnv);
            generateMetaClass(descriptor);
            generateMapperClass(descriptor);
            generateQueryMetaClass(descriptor);
            registerInIndex(descriptor);
        }
        return true;
    }

    private void generateMetaClass(EntityDescriptor d) {
        TypeSpec meta = MetaClassBuilder.build(d);
        JavaFile.builder(d.packageName() + ".generated", meta)
            .indent("    ")
            .build()
            .writeTo(processingEnv.getFiler());
    }

    // ...
}
```

### 3.2 生成步骤

```
1. 扫描 @Entity 标注的类
2. 解析字段（@Id, @Column, @Embedded, @Relation）
3. 校验：必须有 @Id、字段类型映射支持、继承 Model<T>
4. 生成 EntityMeta 类（静态字段常量 + entityMeta() 方法）
5. 生成 Mapper 类（map/toRow/getField/setField）
6. 生成 QueryMeta 类（类型安全查询 DSL，见 3.3）
7. 写入索引文件 META-INF/horm/entities.idx
```

### 3.3 类型安全查询 DSL：`QueryMeta`

```java
// APT 生成：UserQueryMeta.java
public final class UserQueryMeta {

    public static final StringField<User> EMAIL = StringField.of(User.class, "email");
    public static final LongField<User> ID = LongField.of(User.class, "id");
    public static final InstantField<User> CREATED_AT = InstantField.of(User.class, "createdAt");

    public static Condition emailEq(String value) { return EMAIL.eq(value); }
    public static Condition idBetween(Long from, Long to) { return ID.between(from, to); }

    public static Order byEmailAsc() { return EMAIL.asc(); }
    public static Order byEmailDesc() { return EMAIL.desc(); }
    // ...
}
```

业务侧使用：

```java
// 类型安全，编译期检查
List<User> users = User.where(
        UserQueryMeta.EMAIL.eq("a@b.com")
            .and(UserQueryMeta.ID.between(1L, 100L)))
    .orderBy(UserQueryMeta.EMAIL.desc())
    .all();
```

拼写错误或类型不匹配会在编译期失败，而非运行时抛异常。

### 3.4 索引文件：`META-INF/horm/entities.idx`

```
# APT 生成的实体索引，每行一个全限定类名
com.holo.framework.horm.generated.UserMeta
com.holo.framework.horm.generated.OrderMeta
com.holo.framework.horm.generated.ProductMeta
```

启动时 `EntityMetaRegistry` 通过 `ClassLoader.getResources("META-INF/horm/entities.idx")` 加载所有索引（多 jar 合并），逐行调用 `Class.forName(metaClassName)` + 静态方法 `entityMeta()` 完成注册。

**注意**：此处 `Class.forName` 仅在启动时调用一次（每个实体类一次），是必要的引导操作，不属于运行时反射热路径。

---

## 四、字段访问器：`FieldAccessor`

### 4.1 接口定义

```java
public interface FieldAccessor<T, V> {
    V get(T entity);
    void set(T entity, V value);
    static <T, V> FieldAccessor<T, V> of(Function<T, V> getter, BiConsumer<T, V> setter) {
        return new LambdaFieldAccessor<>(getter, setter);
    }
}
```

### 4.2 实现方式

APT 生成时，使用方法引用（`User::getId`、`User::setId`）创建 `LambdaFieldAccessor`。

JVM 在 `invokedynamic` + `LambdaMetafactory` 机制下，将方法引用编译为直接的 invokevirtual 调用，**接近原生性能**。

性能对比（单字段访问，ns/op，JDK 17）：

| 方式 | 延迟 |
|------|------|
| 直接 getter 调用 | 1.2 |
| Lambda 方法引用 | 1.5 |
| MethodHandle.invokeExact | 2.0 |
| Method.invoke（缓存） | 8.5 |
| Field.set（缓存） | 6.8 |
| Field.set（不缓存） | 25+ |

### 4.3 降级：LambdaMetafactory 处理第三方类

对于无法被 APT 处理的第三方类（如标准库类、外部 jar 中的类），HORM 提供 `LambdaMetafactory` 降级路径：

```java
public final class LambdaAccessorFactory {
    public static <T, V> FieldAccessor<T, V> create(Method getter, Method setter) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            CallSite getterSite = LambdaMetafactory.metafactory(
                lookup, "apply",
                MethodType.methodType(Function.class),
                MethodType.methodType(Object.class, Object.class),
                lookup.unreflect(getter),
                MethodType.methodType(getter.getReturnType(), getter.getDeclaringClass()));
            Function<T, V> getterFn = (Function<T, V>) getterSite.getTarget().invoke();
            // 类似地创建 setter BiConsumer
            return FieldAccessor.of(getterFn, setterFn);
        } catch (Throwable t) {
            throw new HormException("Failed to create accessor for " + getter, t);
        }
    }
}
```

降级路径仅在以下场景使用：
- 第三方实体类（无 `@Entity` 注解但需要持久化）
- 动态 schema（运行时拼接的实体）

HORM 自身的实体类永远走 APT 生成路径，**严格零反射**。

---

## 五、Row 抽象与类型转换

### 5.1 Row 接口

```java
public interface Row {
    boolean has(String column);
    Object get(String column);
    Long getLong(String column);
    String getString(String column);
    Instant getInstant(String column);
    BigDecimal getBigDecimal(String column);
    <E extends Enum<E>> E getEnum(String column, Class<E> type);
    byte[] getBytes(String column);
    // ...

    static Row create(String table) { return new MapRow(table); }
    static Row fromResultSet(ResultSet rs, ResultSetMetaData meta) throws SQLException { ... }
}
```

### 5.2 零反射的类型适配

Row 是值对象，Mapper 直接调用 `row.getXxx(column)`，无需反射。

类型适配由 `TypeConverter` SPI 处理：

```java
public interface TypeConverter<J, S> {
    Class<J> javaType();
    Class<S> storageType();
    J toJava(S storageValue);
    S toStorage(J javaValue);
}
```

注册表 `TypeConverterRegistry` 在启动时加载内置转换器（`Instant <-> Timestamp`、`Enum <-> String`、`UUID <-> String`、`JSON <-> Map` 等），APT 生成的 Mapper 自动选择合适的转换器。

---

## 六、构建与编译集成

### 6.1 Maven 集成

业务方在 pom.xml 中声明 `holo-horm-meta` 为 APT 处理器：

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
                        <version>${holo-horm.version}</version>
                    </path>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 6.2 Gradle 集成

```groovy
dependencies {
    implementation 'com.holo.framework:holo-horm-core'
    annotationProcessor 'com.holo.framework:holo-horm-meta'
}
```

### 6.3 IDE 集成

- IntelliJ IDEA：开启 `Build > Compiler > Annotation Processors > Enable annotation processing`
- VS Code：通过 LSP 支持，需安装 `Language Support for Java`
- Eclipse：项目属性 `Java Compiler > Annotation Processing`

### 6.4 增量编译

APT 默认支持增量编译，IDE 内修改实体类时仅重新生成对应的 `XxxMeta` 类。

为提升 IDE 体验，建议配置：
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <generatedSourcesDirectory>${project.basedir}/target/generated-sources/horm</generatedSourcesDirectory>
    </configuration>
</plugin>
```

---

## 七、构建性能优化

### 7.1 增量处理

APT 仅处理本轮新增/修改的元素，已处理的元素跳过。`RoundEnvironment` 自动维护增量信息。

### 7.2 并行生成

各实体的 Meta 类生成无依赖，可在处理器内并行执行：

```java
descriptors.parallelStream().forEach(this::generateMetaClass);
```

### 7.3 索引文件而非扫描

避免运行时类路径扫描，APT 生成索引文件 `META-INF/horm/entities.idx`，启动时按行读取即可，启动开销 < 50ms（1000 个实体）。

### 7.4 性能基准（参考）

| 实体数量 | 全量构建增量 | 启动注册 |
|---------|------------|---------|
| 10 | +0.3s | <5ms |
| 100 | +1.5s | <30ms |
| 500 | +3.0s | <60ms |

---

## 八、AOT / GraalVM Native Image 兼容

### 8.1 兼容性要点

- **无运行时字节码生成**：APT 在编译期生成所有类，无 ByteBuddy/ASM 运行时依赖
- **避免反射**：实体元数据通过静态方法引用，无 `Class.forName` 热路径
- **资源文件可达**：`META-INF/horm/entities.idx` 需在 `resource-config.json` 中注册

### 8.2 Native Image 配置

HORM 自动提供 `META-INF/native-image/com.holo.framework/holo-horm/native-image.properties`：

```
Args = --initialize-at-build-time=com.holo.framework.horm.generated \
       -H:ResourceConfigurationResources=META-INF/native-image/com.holo.framework/holo-horm/resource-config.json
```

`resource-config.json` 包含 `entities.idx` 的匹配模式，确保索引文件在 native image 中可达。

---

## 九、调试与排查

### 9.1 查看生成代码

生成代码默认在 `target/generated-sources/annotations/`，IDE 自动识别为源码目录。

### 9.2 调试 APT

```java
public class HormEntityProcessor extends AbstractProcessor {
    @Override
    public boolean process(...) {
        // 通过 Messager 输出诊断信息
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Processing: " + type);
        // 断点：使用 remote debug 启动 Maven
        // mvnDebug compile
    }
}
```

### 9.3 排查清单

| 问题 | 可能原因 | 解决方案 |
|------|---------|---------|
| `XxxMeta` 类未找到 | APT 未触发 | 检查 annotationProcessorPaths 配置 |
| 字段元数据为 null | 注解未标注 | 确认 `@Entity` / `@Column` 包路径正确 |
| 启动报"实体未注册" | 索引文件缺失 | 检查 `META-INF/horm/entities.idx` 是否生成 |
| 类型不匹配 | 字段类型不支持 | 查看 `TypeConverterRegistry` 支持类型列表 |

---

## 十、与其他方案对比

| 方案 | 运行时反射 | AOT 兼容 | 类型安全 | 调试 | 适用场景 |
|------|----------|---------|---------|------|---------|
| HORM (APT) | **零** | ✓ | **强** | ✓ | HORM 默认 |
| Hibernate (反射) | 高 | 需配置 | 弱 | 难 | 兼容遗留 |
| ByteBuddy (字节码) | 低 | ✗ | 弱 | 难 | 动态代理 |
| LambdaMetafactory | 低 | ✓ | 中 | 中 | HORM 降级路径 |

---

## 十一、风险与限制

| 风险 | 缓解 |
|------|------|
| 字段名拼写错误（运行时 setField） | 推荐使用 `XxxQueryMeta` 类型安全 DSL，避免字符串字段名 |
| Lombok 与 HORM APT 顺序 | Lombok 在前，HORM 在后；如顺序错误，HORM 处理 Lombok 生成的字段时会有警告 |
| 字段被 final 修饰 | APT 检测到 final 字段报错（除非通过构造器注入） |
| 字段类型未注册 TypeConverter | 启动时 fail-fast，提示注册自定义转换器 |

---

## 十二、下一步

- [03-multi-datasource.md](./03-multi-datasource.md) 详述数据源 SPI 设计
- [05-active-record.md](./05-active-record.md) 详述 API 设计与示例
