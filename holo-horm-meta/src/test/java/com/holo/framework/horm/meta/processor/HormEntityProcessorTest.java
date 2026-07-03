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
