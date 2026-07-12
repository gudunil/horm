package com.holo.framework.horm.meta.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.util.List;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke + code-generation tests for {@link HormEntityProcessor} using
 * {@code compile-testing}.
 *
 * <p>M1-3 scope: verify the parse pipeline does not throw and emits the
 * expected {@code NOTE} diagnostic.
 *
 * <p>M1-4 scope: verify the generated {@code XxxMeta}/{@code XxxMapper}/
 * {@code XxxQueryMeta} classes and {@code entities.idx} resource are present
 * and structurally correct via {@link Compilation#generatedSourceFiles()} and
 * {@link Compilation#generatedFiles()}.
 *
 * <p>M1-5 scope: verify {@link EntityValidator} reports {@code ERROR}
 * diagnostics for entities that violate the HORM contract (missing {@code @Id},
 * missing {@code Model<T>} inheritance, final mapped fields, unsupported field
 * types). Happy-path entities extend a stub {@code Model<T>} (see
 * {@link #MODEL_SOURCE}) so that R2 passes and code-generation tests remain
 * green.
 */
class HormEntityProcessorTest {

    /**
     * Stub of the M1-6 {@code Model<T>} base class. Prepended to every
     * compilation so that entity sources can {@code extends Model<Self>}. The
     * real implementation will live in the {@code holo-horm-core} module; the
     * meta module's test classpath does not include it.
     */
    private static final String MODEL_SOURCE = """
        package com.holo.framework.horm.core;
        public abstract class Model<T> {
        }
        """;

    /**
     * Stub classes needed by M8.7 TransactionProxyBuilder generated code.
     * The generated proxy references core classes that are not on the meta
     * test classpath, so we provide minimal stubs for compile-testing.
     */
    private static final String CORE_STUBS = """
        package com.holo.framework.horm.core;
        public final class TransactionDefinition {
            public TransactionDefinition() {}
            public static Builder builder() { return new Builder(); }
            public static final class Builder {
                public Builder propagation(Object p) { return this; }
                public Builder isolation(Object i) { return this; }
                public Builder timeout(int t) { return this; }
                public Builder readOnly(boolean r) { return this; }
                public TransactionDefinition build() { return new TransactionDefinition(); }
            }
        }
        """;

    private static final String CORE_STUBS2 = """
        package com.holo.framework.horm.core;
        public final class HormContext {
            public HormContext() {}
            public static HormContext current() { return new HormContext(); }
        }
        """;

    private static final String CORE_STUBS3 = """
        package com.holo.framework.horm.core;
        public final class TransactionStatus {
            public TransactionStatus() {}
        }
        """;

    private static final String CORE_STUBS4 = """
        package com.holo.framework.horm.core;
        public final class TransactionManager {
            private TransactionManager() {}
            public static TransactionStatus begin(HormContext ctx, String ds, TransactionDefinition def) { return new TransactionStatus(); }
            public static void commit(TransactionStatus s) {}
            public static void rollback(TransactionStatus s) {}
            public static void popAndResume(String ds, TransactionStatus s) {}
        }
        """;

    private static final String CORE_STUBS5 = """
        package com.holo.framework.horm.core.datasource;
        public final class DataSourceRegistry {
            public static final String DEFAULT_NAME = "default";
            private DataSourceRegistry() {}
        }
        """;

    private static final String CORE_STUBS6 = """
        package com.holo.framework.horm.core;
        public class TransactionException extends RuntimeException {
            public TransactionException(String m) { super(m); }
            public TransactionException(String m, Throwable c) { super(m, c); }
        }
        """;

    private static final String USER_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.Column;
        import com.holo.framework.horm.meta.annotation.GenerationType;
        import com.holo.framework.horm.core.Model;
        import java.time.Instant;

        @Entity(table = "users")
        public class User extends Model<User> {
            @Id(strategy = GenerationType.IDENTITY)
            private Long id;

            @Column(name = "email", nullable = false, length = 128)
            private String email;

            @Column
            private Instant createdAt;

            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
            public String getEmail() { return email; }
            public void setEmail(String email) { this.email = email; }
            public Instant getCreatedAt() { return createdAt; }
            public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        }
        """;

    private static final String ACCOUNT_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.GenerationType;
        import com.holo.framework.horm.core.Model;

        @Entity
        public class Account extends Model<Account> {
            @Id private Long id;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
        }
        """;

    private static final String ORDER_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.GenerationType;
        import com.holo.framework.horm.core.Model;

        @Entity(table = "orders")
        public class Order extends Model<Order> {
            @Id private Long id;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
        }
        """;

    private static final String PRODUCT_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.Column;
        import com.holo.framework.horm.meta.annotation.GenerationType;
        import com.holo.framework.horm.core.Model;
        import java.math.BigDecimal;

        @Entity(table = "products")
        public class Product extends Model<Product> {
            @Id(strategy = GenerationType.IDENTITY)
            private Long id;

            @Column
            private Status status;

            @Column
            private BigDecimal price;

            @Column
            private Integer stock;

            @Column
            private Boolean active;

            @Column
            private byte[] data;

            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
            public Status getStatus() { return status; }
            public void setStatus(Status status) { this.status = status; }
            public BigDecimal getPrice() { return price; }
            public void setPrice(BigDecimal price) { this.price = price; }
            public Integer getStock() { return stock; }
            public void setStock(Integer stock) { this.stock = stock; }
            public Boolean isActive() { return active; }
            public void setActive(Boolean active) { this.active = active; }
            public byte[] getData() { return data; }
            public void setData(byte[] data) { this.data = data; }

            public enum Status {
                ACTIVE, INACTIVE
            }
        }
        """;

    private static final String PROFILE_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.core.Model;

        @Entity(table = "profiles")
        public class Profile extends Model<Profile> {
            @Id private Long id;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
        }
        """;

    private static final String TAG_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.core.Model;

        @Entity(table = "tags")
        public class Tag extends Model<Tag> {
            @Id private Long id;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
        }
        """;

    private static final String USER_WITH_HAS_MANY_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.HasMany;
        import com.holo.framework.horm.core.Model;
        import java.util.List;

        @Entity(table = "users")
        public class User extends Model<User> {
            @Id private Long id;
            @HasMany(targetEntity = Order.class, foreignKey = "user_id")
            private List<Order> orders;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
            public List<Order> getOrders() { return orders; }
            public void setOrders(List<Order> orders) { this.orders = orders; }
        }
        """;

    private static final String USER_ALL_RELATIONS_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.Column;
        import com.holo.framework.horm.meta.annotation.BelongsTo;
        import com.holo.framework.horm.meta.annotation.HasOne;
        import com.holo.framework.horm.meta.annotation.HasMany;
        import com.holo.framework.horm.meta.annotation.HasAndBelongsToMany;
        import com.holo.framework.horm.meta.annotation.HasManyThrough;
        import com.holo.framework.horm.core.Model;
        import java.util.List;

        @Entity(table = "users_all")
        public class UserAll extends Model<UserAll> {
            @Id private Long id;

            @Column(name = "parent_order_id")
            private Long parentOrderId;

            @BelongsTo(targetEntity = Order.class, foreignKey = "parent_order_id")
            private List<Order> parentOrders;

            @HasOne(targetEntity = Profile.class, foreignKey = "user_id")
            private List<Profile> profiles;

            @HasMany(targetEntity = Order.class, foreignKey = "user_id")
            private List<Order> orders;

            @HasAndBelongsToMany(targetEntity = Tag.class,
                joinTable = "user_tags_all",
                foreignKey = "user_id",
                associationForeignKey = "tag_id")
            private List<Tag> tags;

            @HasManyThrough(targetEntity = Product.class,
                through = Order.class,
                foreignKey = "user_id",
                associationForeignKey = "product_id")
            private List<Product> products;

            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
            public Long getParentOrderId() { return parentOrderId; }
            public void setParentOrderId(Long v) { this.parentOrderId = v; }
            public List<Order> getParentOrders() { return parentOrders; }
            public void setParentOrders(List<Order> v) { this.parentOrders = v; }
            public List<Profile> getProfiles() { return profiles; }
            public void setProfiles(List<Profile> v) { this.profiles = v; }
            public List<Order> getOrders() { return orders; }
            public void setOrders(List<Order> v) { this.orders = v; }
            public List<Tag> getTags() { return tags; }
            public void setTags(List<Tag> v) { this.tags = v; }
            public List<Product> getProducts() { return products; }
            public void setProducts(List<Product> v) { this.products = v; }
        }
        """;

    private static final String BAD_RELATION_NOT_LIST_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.HasMany;
        import com.holo.framework.horm.core.Model;

        @Entity
        public class Bad extends Model<Bad> {
            @Id private Long id;
            @HasMany(targetEntity = Order.class, foreignKey = "user_id")
            private Order orders;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
            public Order getOrders() { return orders; }
            public void setOrders(Order o) { this.orders = o; }
        }
        """;

    private static final String BAD_HABTM_NO_JOINTABLE_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.HasAndBelongsToMany;
        import com.holo.framework.horm.core.Model;
        import java.util.List;

        @Entity
        public class Bad extends Model<Bad> {
            @Id private Long id;
            @HasAndBelongsToMany(targetEntity = Tag.class)
            private List<Tag> tags;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
            public List<Tag> getTags() { return tags; }
            public void setTags(List<Tag> t) { this.tags = t; }
        }
        """;

    private static final String BAD_THROUGH_NO_THROUGH_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.HasManyThrough;
        import com.holo.framework.horm.core.Model;
        import java.util.List;

        @Entity
        public class Bad extends Model<Bad> {
            @Id private Long id;
            @HasManyThrough(targetEntity = Product.class)
            private List<Product> products;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
            public List<Product> getProducts() { return products; }
            public void setProducts(List<Product> p) { this.products = p; }
        }
        """;

    private static final String BAD_FINAL_RELATION_FIELD_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.HasMany;
        import com.holo.framework.horm.core.Model;
        import java.util.List;
        import java.util.ArrayList;

        @Entity
        public class Bad extends Model<Bad> {
            @Id private Long id;
            @HasMany(targetEntity = Order.class, foreignKey = "user_id")
            private final List<Order> orders = new ArrayList<>();
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
            public List<Order> getOrders() { return orders; }
        }
        """;

    private static final String BAD_RELATION_TARGET_NOT_ENTITY_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.HasMany;
        import com.holo.framework.horm.core.Model;
        import java.util.List;

        @Entity
        public class Bad extends Model<Bad> {
            @Id private Long id;
            @HasMany(targetEntity = String.class, foreignKey = "bad_id")
            private List<String> names;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
            public List<String> getNames() { return names; }
            public void setNames(List<String> n) { this.names = n; }
        }
        """;

    @Test
    void parsesSimpleEntity() {
        Compilation comp = compile(USER_SOURCE);

        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(comp.errors()).isEmpty();
        assertThat(noteMessages(comp)).anyMatch(m -> m.contains("Parsed 1 entity"));
    }

    @Test
    void parsesMultipleEntities() {
        Compilation comp = compile(ACCOUNT_SOURCE, ORDER_SOURCE);

        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(noteMessages(comp)).anyMatch(m -> m.contains("Parsed 2 entit"));
    }

    @Test
    void entityOnInterfaceReportsError() {
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;

            @Entity
            public interface NotAnEntity {
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("@Entity can only be applied to classes"));
    }

    @Test
    void rejectsEntityWithoutId() {
        // R1: @Entity must declare exactly one @Id field.
        // Foo has only @Column — R1 fires (R2 also fires since Foo does not
        // extend Model, but the test only asserts the R1 message).
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Column;

            @Entity
            public class Foo {
                @Column
                private String name;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("must declare exactly one @Id field"));
    }

    @Test
    void rejectsEntityNotExtendingModel() {
        // R2: @Entity must extend Model<T>. Foo has @Id (R1 passes) but does
        // not extend Model, so only R2 fires.
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Id;

            @Entity
            public class Foo {
                @Id
                private Long id;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("must extend Model"));
    }

    @Test
    void rejectsFinalFieldWithColumn() {
        // R3: @Id/@Column fields must not be final (Model<T> requires setters).
        // Foo has @Id Long id (R1 passes) and @Column final String name (R3
        // fires). R2 also fires since Foo does not extend Model.
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Id;
            import com.holo.framework.horm.meta.annotation.Column;

            @Entity
            public class Foo {
                @Id
                private Long id;
                @Column
                private final String name = "x";
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getName() { return name; }
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("must not be final"));
    }

    @Test
    void rejectsUnsupportedFieldType() {
        // R4: field types must be supported by TypeMapper. java.util.Date is
        // not in the supported set (Long/Integer/String/Boolean/Instant/
        // BigDecimal/enum/byte[]). R2 also fires since Foo does not extend
        // Model.
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Id;
            import com.holo.framework.horm.meta.annotation.Column;
            import java.util.Date;

            @Entity
            public class Foo {
                @Id
                private Long id;
                @Column
                private Date created;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public Date getCreated() { return created; }
                public void setCreated(Date created) { this.created = created; }
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("unsupported field type"));
    }

    @Test
    void generatesUserMetaClass() throws IOException {
        Compilation comp = compile(USER_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String src = src(generated(comp, "UserMeta.java"));

        assertThat(src).contains("public final class UserMeta");
        assertThat(src).contains("TABLE_NAME = \"users\"");
        assertThat(src).contains("FieldMeta<Long> ID");
        assertThat(src).contains(".id(true)");
        assertThat(src).contains(".generationStrategy(GenerationType.IDENTITY)");
        assertThat(src).contains("FieldMeta<String> EMAIL");
        assertThat(src).contains("FieldAccessor<User, Long> ID_ACCESSOR");
        assertThat(src).contains("MAPPER = UserMapper.INSTANCE");
        assertThat(src).contains("List<FieldMeta<?>> ALL_FIELDS");
        assertThat(src).contains("entityMeta()");
        assertThat(src).contains(".idField(ID)");
        assertThat(src).contains(".mapper(MAPPER)");
    }

    @Test
    void generatesUserMapperClass() throws IOException {
        Compilation comp = compile(USER_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String src = src(generated(comp, "UserMapper.java"));

        assertThat(src).contains("final class UserMapper implements Mapper<User>");
        assertThat(src).contains("static final UserMapper INSTANCE");
        assertThat(src).contains("row.getLong(\"id\")");
        assertThat(src).contains("row.getString(\"email\")");
        assertThat(src).contains("row.getInstant(\"created_at\")");
        assertThat(src).contains("switch (field)");
        assertThat(src).contains("(Long) value");
        assertThat(src).contains("Row.create(UserMeta.TABLE_NAME)");
        assertThat(src).contains("return u.getId()");
        assertThat(src).contains("u.setId((Long) id)");
    }

    @Test
    void generatesUserQueryMetaClass() throws IOException {
        Compilation comp = compile(USER_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String src = src(generated(comp, "UserQueryMeta.java"));

        assertThat(src).contains("public final class UserQueryMeta");
        assertThat(src).contains("LongField<User> ID = LongField.of(User.class, \"id\", \"id\")");
        assertThat(src).contains("StringField<User> EMAIL");
        assertThat(src).contains("InstantField<User> CREATED_AT");
    }

    @Test
    void writesEntitiesIdx() throws IOException {
        Compilation comp = compile(USER_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String idx = comp.generatedFiles().stream()
            .filter(f -> f.getName().endsWith("entities.idx"))
            .findFirst().orElseThrow(() -> new AssertionError("no entities.idx"))
            .getCharContent(true).toString();

        assertThat(idx).contains("test.generated.UserMeta");
    }

    @Test
    void generatesMetaForMultipleEntities() throws IOException {
        Compilation comp = compile(ACCOUNT_SOURCE, ORDER_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        List<String> names = generatedSourceNames(comp);
        assertThat(names).anyMatch(n -> n.endsWith("AccountMeta.java"));
        assertThat(names).anyMatch(n -> n.endsWith("OrderMeta.java"));
        assertThat(names).anyMatch(n -> n.endsWith("OrderMapper.java"));

        String idx = comp.generatedFiles().stream()
            .filter(f -> f.getName().endsWith("entities.idx"))
            .findFirst().orElseThrow(() -> new AssertionError("no entities.idx"))
            .getCharContent(true).toString();

        assertThat(idx).contains("test.generated.AccountMeta");
        assertThat(idx).contains("test.generated.OrderMeta");
    }

    @Test
    void generatesEnumAndDecimalFields() throws IOException {
        Compilation comp = compile(PRODUCT_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String metaSrc = src(generated(comp, "ProductMeta.java"));
        assertThat(metaSrc).contains("FieldMeta<BigDecimal>");
        assertThat(metaSrc).contains("FieldMeta<Integer>");
        assertThat(metaSrc).contains("FieldMeta<Boolean>");
        assertThat(metaSrc).contains("FieldMeta<byte[]>");
        assertThat(metaSrc).contains("Status");

        String mapperSrc = src(generated(comp, "ProductMapper.java"));
        assertThat(mapperSrc).contains("row.getEnum(\"status\"");
        assertThat(mapperSrc).contains("Status.class");
        assertThat(mapperSrc).contains("row.getBigDecimal(\"price\")");
        assertThat(mapperSrc).contains("row.getInteger(\"stock\")");
        assertThat(mapperSrc).contains("row.getBoolean(\"active\")");
        assertThat(mapperSrc).contains("row.getBytes(\"data\")");
        assertThat(mapperSrc).contains("row.set(\"price\",");
        assertThat(mapperSrc).contains("row.set(\"data\",");

        String queryMetaSrc = src(generated(comp, "ProductQueryMeta.java"));
        assertThat(queryMetaSrc).contains("EnumField<Product");
        assertThat(queryMetaSrc).contains("Status");
        assertThat(queryMetaSrc).contains("BigDecimalField<Product>");
        assertThat(queryMetaSrc).contains("IntegerField<Product>");
        assertThat(queryMetaSrc).contains("BooleanField<Product>");
        // byte[] is not supported by any TypedField subclass — must be absent.
        assertThat(queryMetaSrc).doesNotContain("byte[]");
    }

    @Test
    void parsesEntityWithHasManyRelation() throws IOException {
        Compilation comp = compile(USER_WITH_HAS_MANY_SOURCE, ORDER_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String metaSrc = src(generated(comp, "UserMeta.java"));
        assertThat(metaSrc).contains("ALL_RELATIONS");
        assertThat(metaSrc).contains("RelationMeta.builder()");
        assertThat(metaSrc).contains("HAS_MANY");
        assertThat(metaSrc).contains("\"user_id\"");

        String queryMetaSrc = src(generated(comp, "UserQueryMeta.java"));
        assertThat(queryMetaSrc).contains("RelationField<User, Order> ORDERS");
        assertThat(queryMetaSrc).contains("RelationField.of(User.class, Order.class");
    }

    @Test
    void generatesMapperSetRelationDispatch() throws IOException {
        Compilation comp = compile(USER_WITH_HAS_MANY_SOURCE, ORDER_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String mapperSrc = src(generated(comp, "UserMapper.java"));
        assertThat(mapperSrc).contains("public void setRelation");
        assertThat(mapperSrc).contains("switch (name)");
        assertThat(mapperSrc).contains("case \"orders\" -> u.setOrders((");
        assertThat(mapperSrc).contains("throw new IllegalArgumentException");
    }

    @Test
    void rejectsRelationFieldNotList() {
        // R5: relation field type must be java.util.List
        Compilation comp = compile(BAD_RELATION_NOT_LIST_SOURCE, ORDER_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("must be of type java.util.List"));
    }

    @Test
    void rejectsRelationTargetNotEntity() {
        // R6: relation target must be @Entity-annotated. String.class is not.
        Compilation comp = compile(BAD_RELATION_TARGET_NOT_ENTITY_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("is not an @Entity"));
    }

    @Test
    void rejectsHabtmWithoutJoinTable() {
        // R7: @HasAndBelongsToMany must declare non-empty joinTable
        Compilation comp = compile(BAD_HABTM_NO_JOINTABLE_SOURCE, TAG_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("must declare a non-empty joinTable"));
    }

    @Test
    void rejectsHasManyThroughWithoutThrough() {
        // R8: @HasManyThrough must declare a through entity
        Compilation comp = compile(BAD_THROUGH_NO_THROUGH_SOURCE, PRODUCT_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("must declare a through entity"));
    }

    @Test
    void rejectsFinalRelationField() {
        // R9: relation fields must not be final
        Compilation comp = compile(BAD_FINAL_RELATION_FIELD_SOURCE, ORDER_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("must not be final"));
    }

    @Test
    void acceptsVersionFieldOnEntity() throws IOException {
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Id;
            import com.holo.framework.horm.meta.annotation.Column;
            import com.holo.framework.horm.meta.annotation.Version;
            import com.holo.framework.horm.core.Model;

            @Entity(table = "versioned_items")
            public class VersionedItem extends Model<VersionedItem> {
                @Id
                private Long id;
                @Column
                private String name;
                @Version
                private Long version;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
                public Long getVersion() { return version; }
                public void setVersion(Long version) { this.version = version; }
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(comp.errors()).isEmpty();

        String metaSrc = src(generated(comp, "VersionedItemMeta.java"));
        assertThat(metaSrc).contains(".version(true)");
        assertThat(metaSrc).contains(".versionField(VERSION)");
        assertThat(metaSrc).contains(".insertable(false)");
        assertThat(metaSrc).contains(".updatable(false)");

        String mapperSrc = src(generated(comp, "VersionedItemMapper.java"));
        assertThat(mapperSrc).contains("public void incrementVersion");
        assertThat(mapperSrc).contains("u.setVersion((Long) (u.getVersion() + 1))");
    }

    @Test
    void rejectsVersionFieldWithWrongType() {
        // R10: @Version field type must be int/Integer/long/Long
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Id;
            import com.holo.framework.horm.meta.annotation.Version;
            import com.holo.framework.horm.core.Model;

            @Entity
            public class Bad extends Model<Bad> {
                @Id private Long id;
                @Version
                private String version;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getVersion() { return version; }
                public void setVersion(String version) { this.version = version; }
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("must be of type int, Integer, long, or Long"));
    }

    @Test
    void parsesAllFiveRelationTypes() throws IOException {
        Compilation comp = compile(USER_ALL_RELATIONS_SOURCE, ORDER_SOURCE,
            PROFILE_SOURCE, TAG_SOURCE, PRODUCT_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(comp.errors()).isEmpty();

        String metaSrc = src(generated(comp, "UserAllMeta.java"));
        assertThat(metaSrc).contains("BELONGS_TO");
        assertThat(metaSrc).contains("HAS_ONE");
        assertThat(metaSrc).contains("HAS_MANY");
        assertThat(metaSrc).contains("HAS_AND_BELONGS_TO_MANY");
        assertThat(metaSrc).contains("HAS_MANY_THROUGH");

        String queryMetaSrc = src(generated(comp, "UserAllQueryMeta.java"));
        assertThat(queryMetaSrc).contains("RelationField<UserAll, Order> PARENT_ORDERS");
        assertThat(queryMetaSrc).contains("RelationField<UserAll, Profile> PROFILES");
        assertThat(queryMetaSrc).contains("RelationField<UserAll, Order> ORDERS");
        assertThat(queryMetaSrc).contains("RelationField<UserAll, Tag> TAGS");
        assertThat(queryMetaSrc).contains("RelationField<UserAll, Product> PRODUCTS");
    }

    @Test
    void generatesTransactionalProxyClass() throws IOException {
        Compilation comp = compileWithStubs("""
            package test;
            import com.holo.framework.horm.meta.annotation.Transactional;

            public class OrderService {
                @Transactional
                public String createOrder(String productId, int quantity) {
                    return "order-" + productId;
                }
            }
            """);

        if (comp.status() != Compilation.Status.SUCCESS) {
            comp.errors().forEach(d -> {
                System.err.println("COMPILE ERROR: " + d.getMessage(null));
                d.getMessage(null).lines().forEach(System.err::println);
            });
            // Print all generated files for diagnosis
            comp.generatedSourceFiles().forEach(f -> {
                try {
                    System.err.println("=== Generated: " + f.getName() + " ===");
                    System.err.println(f.getCharContent(true).toString().substring(0, Math.min(500, f.getCharContent(true).length())));
                } catch (IOException e) { }
            });
        }
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String proxySrc = src(generated(comp, "OrderService_TransactionalProxy.java"));
        assertThat(proxySrc).contains("class OrderService_TransactionalProxy extends OrderService");
        assertThat(proxySrc).contains("private final OrderService delegate");
        assertThat(proxySrc).contains("private final Object ctx");
        assertThat(proxySrc).contains("CREATE_ORDER_META");
        assertThat(proxySrc).contains("TransactionManager.begin");
        assertThat(proxySrc).contains("delegate.createOrder(productId, quantity)");
    }

    @Test
    void generatesTransactionalProxyFactoryClass() throws IOException {
        Compilation comp = compileWithStubs("""
            package test;
            import com.holo.framework.horm.meta.annotation.Transactional;

            public class PaymentService {
                @Transactional
                public void processPayment(String orderId) {}
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String factorySrc = src(generated(comp, "PaymentService_TransactionalProxyFactory.java"));
        assertThat(factorySrc).contains("implements TransactionProxyFactory");
        assertThat(factorySrc).contains("return new PaymentService_TransactionalProxy((PaymentService) delegate, context)");
        assertThat(factorySrc).contains("return PaymentService.class");
    }

    @Test
    void generatesServiceLoaderConfigForProxyFactory() throws IOException {
        Compilation comp = compileWithStubs("""
            package test;
            import com.holo.framework.horm.meta.annotation.Transactional;

            public class ReportService {
                @Transactional(readOnly = true)
                public String generate() { return "report"; }
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String config = comp.generatedFiles().stream()
            .filter(f -> f.getName().contains("META-INF/services/"))
            .filter(f -> f.getName().contains("TransactionProxyFactory"))
            .findFirst().orElseThrow(() -> new AssertionError("no TransactionProxyFactory service config"))
            .getCharContent(true).toString();

        assertThat(config).contains("test.generated.ReportService_TransactionalProxyFactory");
    }

    @Test
    void warnsOnFinalTransactionalClass() {
        Compilation comp = compileWithStubs("""
            package test;
            import com.holo.framework.horm.meta.annotation.Transactional;

            public final class FinalService {
                @Transactional
                public void doWork() {}
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(comp.warnings())
            .anyMatch(d -> d.getMessage(null).contains("final class"));
    }

    @Test
    void skipsFinalTransactionalMethod() throws IOException {
        Compilation comp = compileWithStubs("""
            package test;
            import com.holo.framework.horm.meta.annotation.Transactional;

            public class MixedService {
                @Transactional
                public final void finalMethod() {}

                @Transactional
                public void normalMethod() {}
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String proxySrc = src(generated(comp, "MixedService_TransactionalProxy.java"));
        // final method should NOT be overridden in proxy
        assertThat(proxySrc).doesNotContain("finalMethod");
        // normal method should be overridden
        assertThat(proxySrc).contains("NORMAL_METHOD_META");
    }

    private static Compilation compileWithStubs(String... sources) {
        String[] all = new String[sources.length + 7];
        all[0] = MODEL_SOURCE;
        all[1] = CORE_STUBS;
        all[2] = CORE_STUBS2;
        all[3] = CORE_STUBS3;
        all[4] = CORE_STUBS4;
        all[5] = CORE_STUBS5;
        all[6] = CORE_STUBS6;
        System.arraycopy(sources, 0, all, 7, sources.length);
        JavaFileObject[] files = new JavaFileObject[all.length];
        for (int i = 0; i < all.length; i++) {
            String packageName = extractPackageName(all[i]);
            String className = extractClassName(all[i]);
            files[i] = JavaFileObjects.forSourceString(packageName + "." + className, all[i]);
        }
        return javac()
            .withProcessors(new HormEntityProcessor())
            .compile(files);
    }

    private static Compilation compile(String... sources) {
        // Prepend the stub Model<T> base class so entity sources that
        // `extends Model<Self>` resolve. The M1-6 core module is not on the
        // meta test classpath; error-scenario entities that do NOT extend
        // Model are unaffected (R2 still fires for them).
        String[] all = new String[sources.length + 1];
        all[0] = MODEL_SOURCE;
        System.arraycopy(sources, 0, all, 1, sources.length);
        JavaFileObject[] files = new JavaFileObject[all.length];
        for (int i = 0; i < all.length; i++) {
            String packageName = extractPackageName(all[i]);
            String className = extractClassName(all[i]);
            files[i] = JavaFileObjects.forSourceString(packageName + "." + className, all[i]);
        }
        return javac()
            .withProcessors(new HormEntityProcessor())
            .compile(files);
    }

    private static List<String> noteMessages(Compilation comp) {
        return comp.notes().stream()
            .map(d -> d.getMessage(null))
            .toList();
    }

    private static List<String> generatedSourceNames(Compilation comp) {
        return comp.generatedSourceFiles().stream()
            .map(JavaFileObject::getName)
            .toList();
    }

    private static JavaFileObject generated(Compilation comp, String suffix) {
        return comp.generatedSourceFiles().stream()
            .filter(f -> f.getName().endsWith(suffix))
            .findFirst().orElseThrow(() -> new AssertionError("no generated file ending with " + suffix));
    }

    private static String src(JavaFileObject f) throws IOException {
        return f.getCharContent(true).toString();
    }

    private static String extractPackageName(String source) {
        int pkgIdx = source.indexOf("package ");
        int semi = source.indexOf(';', pkgIdx);
        return source.substring(pkgIdx + 8, semi).trim();
    }

    private static String extractClassName(String source) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\b(?:class|interface|enum|record)\\s+(\\w+)")
            .matcher(source);
        if (!m.find()) {
            throw new IllegalArgumentException("Cannot extract class name from source");
        }
        return m.group(1);
    }
}
