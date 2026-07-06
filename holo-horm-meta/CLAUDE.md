[根目录](../../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **holo-horm-meta**

# Holo :: HORM :: Meta (注解定义 / APT 处理器)

## 模块职责

提供 HORM 框架的注解定义集合与 APT 编译器处理器，编译期为 `@Entity` 标注的类生成三个伴随类：`XxxMeta`（字段/关联元数据）、`XxxMapper`（零反射对象映射）、`XxxQueryMeta`（类型安全查询 DSL 常量）。

- **parent**: `holo-horm` (1.0.0-SNAPSHOT)
- **artifactId**: `holo-horm-meta`
- **packaging**: jar

## 核心设计原则

- **运行时零反射**: APT 编译期生成代码，运行时热路径无反射
- **类型安全**: 生成的元模型类是普通 Java 类，编译器/IDE 完全感知
- **AOT 兼容**: 无运行时字节码生成，兼容 GraalVM Native Image
- **调试友好**: 生成代码可读、可断点、可单步

## 子包结构

```
com.holo.framework.horm.meta
  ├── annotation/              @Entity, @Id, @Column, @Table, @Version, @Cached 等注解
  ├── processor/                APT 处理器核心
  │   ├── HormEntityProcessor   APT 入口
  │   ├── EntityDescriptor      实体描述符 IR
  │   ├── EntityDescriptorParser 注解解析器
  │   ├── EntityValidator       编译期校验 (R1-R12)
  │   ├── MetaClassBuilder      XxxMeta JavaPoet 代码生成
  │   ├── MapperBuilder         XxxMapper JavaPoet 代码生成
  │   ├── QueryMetaBuilder      XxxQueryMeta JavaPoet 代码生成
  │   └── IndexWriter           entities.idx 索引文件写入
  ├── query/                    类型安全查询条件 DSL
  │   ├── Condition, Conditions, CompositeCondition
  │   ├── TypedField, ComparableField
  │   ├── StringField, LongField, IntegerField, BigDecimalField, InstantField
  │   ├── BooleanField, EnumField, RelationField
  ├── Row                       数据源无关行抽象
  ├── Mapper                    对象映射接口
  ├── FieldMeta, RelationMeta   字段/关联元数据
  ├── EntityMeta                运行时实体元数据
  ├── FieldAccessor             字段访问器接口
  └── TypeConverter             类型转换 SPI
```

## 对外接口

### 注解（运行时保留）

| 注解 | 目标 | 说明 |
|------|------|------|
| `@Entity` | 类 | 标记为 HORM 实体，指定 table/dataSource/schema |
| `@Table` | 类 | 表名映射 |
| `@Id` | 字段 | 主键标识，支持 GenerationType.IDENTITY |
| `@Column` | 字段 | 列映射：name/nullable/length/insertable/updatable |
| `@Version` | 字段 | 乐观锁版本号（int/Integer/long/Long） |
| `@BelongsTo` | 字段 | 多对一（持 FK 端） |
| `@HasOne` | 字段 | 一对一 |
| `@HasMany` | 字段 | 一对多 |
| `@HasAndBelongsToMany` | 字段 | 多对多（中间表） |
| `@HasManyThrough` | 字段 | 远程一对多（通过中间实体） |
| `@Cached` | 类 | 启用缓存，可配策略和层级 |
| `@Transactional` | 类/方法 | 声明式事务元数据 |
| `@GeneratedValue` | 字段 | 主键生成策略 |
| `@CascadeType` | 字段 | 级联操作类型 |

### 运行时接口

| 接口 | 说明 |
|------|------|
| `Mapper<T>` | map(Row)、toRow(T)、getId/setId、getField/setField |
| `FieldAccessor<T,V>` | get/set 方法引用封装 |
| `TypeConverter<J,S>` | Java 类型 ↔ 存储类型转换 |
| `Condition` | 查询条件（sqlFragment + bindings） |
| `Row` | 行数据抽象（MapRow 实现） |

## 关键依赖与配置

- **APT 生成类位置**: `<实体包>.generated`
- **索引文件**: `META-INF/horm/entities.idx`
- **构建依赖**: JavaPoet 1.13.0
- **无 Mockito**: 测试使用真实对象或匿名内部类代替 mock()

### 编译期校验规则 (R1-R12)

| 规则 | 说明 |
|------|------|
| R1 | @Entity 类必须继承 Model<T> |
| R2 | @Id 字段必须存在 |
| R3 | 字段不能为 final |
| R4 | 字段类型必须支持 |
| R5 | 关联字段必须为 List<...> |
| R6 | @BelongsTo 必须指定 foreignKey |
| R7 | 关联目标必须是 @Entity |
| R8 | 禁止双向关联循环 |
| R9 | @HasManyThrough 必须指定 through/middleEntity |
| R10 | @Version 只能用于 int/Integer/long/Long |
| R11 | @Version 字段必须 insertable=false, updatable=false |
| R12 | @Cached 的 cacheLevels 不能为空 |

## 数据模型

### EntityMeta<T>（运行时元数据）

| 字段 | 类型 | 说明 |
|------|------|------|
| type | Class<T> | 实体类 |
| tableName | String | 逻辑表名 |
| schema | String | 数据库 schema |
| dataSource | String | 目标数据源名 |
| fields | List<FieldMeta<?>> | 字段元数据列表 |
| idField | FieldMeta<?> | 主键字段 |
| mapper | Mapper<T> | APT 生成的 Mapper 实例 |
| relations | List<RelationMeta> | 关联元数据列表 |
| versionField | FieldMeta<?> | 乐观锁版本字段 |
| cached | boolean | 是否启用缓存 |
| cachePolicy | CachePolicy | 缓存策略 |
| cacheLevels | CacheLevel[] | 缓存层级 |

## 测试与质量

- **测试框架**: JUnit 5 + AssertJ + Google compile-testing
- **测试数量**: 156
- **覆盖目标**: processor 包 > 80%，整体 meta 模块 > 84%
- **关键测试**: `HormEntityProcessorTest`、`ConditionTest`、`TypedFieldTest`、`RowTest`、`EntityMetaTest`

## 相关文件清单

| 文件路径 | 说明 |
|---------|------|
| `holo-horm/holo-horm-meta/pom.xml` | 模块 POM |
| `holo-horm/holo-horm-meta/src/main/java/com/holo/framework/horm/meta/processor/HormEntityProcessor.java` | APT 入口 |
| `holo-horm/holo-horm-meta/src/main/java/com/holo/framework/horm/meta/processor/MetaClassBuilder.java` | 元数据类生成 |
| `holo-horm/holo-horm-meta/src/main/java/com/holo/framework/horm/meta/processor/MapperBuilder.java` | Mapper 生成 |
| `holo-horm/holo-horm-meta/src/main/java/com/holo/framework/horm/meta/annotation/Entity.java` | @Entity 注解 |
| `holo-horm/holo-horm-meta/src/main/java/com/holo/framework/horm/meta/EntityMeta.java` | 运行时元数据 |

## 常见问题 (FAQ)

### 1. `XxxMeta` 类未找到
APT 未触发，检查 `annotationProcessorPaths` 配置是否正确。

### 2. 字段元数据为 null
确认 `@Entity` / `@Column` 包路径正确。

### 3. 启动报"实体未注册"
检查 `META-INF/horm/entities.idx` 是否生成。

### 4. Lombok 与 HORM APT 顺序
Lombok 在前，HORM 在后。APT 顺序通过 `annotationProcessorPaths` 控制。

## 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-07-06 | 初始文档生成 | 架构扫描 |