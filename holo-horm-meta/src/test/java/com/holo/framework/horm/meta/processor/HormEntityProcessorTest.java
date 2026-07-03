package com.holo.framework.horm.meta.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.util.List;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for {@link HormEntityProcessor} using {@code compile-testing}.
 *
 * <p>M1-3 scope: verify the parse pipeline does not throw and emits the
 * expected {@code NOTE} diagnostic. Code-generation assertions (presence of
 * {@code XxxMeta}, {@code XxxMapper}, {@code entities.idx}) arrive in M1-8.
 */
class HormEntityProcessorTest {

    @Test
    void parsesSimpleEntity() {
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Id;
            import com.holo.framework.horm.meta.annotation.Column;
            import com.holo.framework.horm.meta.annotation.GenerationType;
            import java.time.Instant;

            @Entity(table = "users")
            public class User {
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
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(comp.errors()).isEmpty();
        assertThat(noteMessages(comp)).anyMatch(m -> m.contains("Parsed 1 entity"));
    }

    @Test
    void parsesMultipleEntities() {
        Compilation comp = compile(
            """
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Id;
            import com.holo.framework.horm.meta.annotation.GenerationType;

            @Entity
            public class Account {
                @Id private Long id;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
            }
            """,
            """
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Id;
            import com.holo.framework.horm.meta.annotation.GenerationType;

            @Entity(table = "orders")
            public class Order {
                @Id private Long id;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
            }
            """);

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
    void entityWithoutIdStillCompiles() {
        // Parser is permissive: missing @Id is not an error in M1-3.
        // Validation lives in M1-5 EntityValidator.
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Column;

            @Entity(table = "settings")
            public class Setting {
                @Column private String key;
                @Column private String value;
                public String getKey() { return key; }
                public void setKey(String key) { this.key = key; }
                public String getValue() { return value; }
                public void setValue(String value) { this.value = value; }
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(noteMessages(comp)).anyMatch(m -> m.contains("Parsed 1 entity"));
    }

    private static Compilation compile(String... sources) {
        JavaFileObject[] files = new JavaFileObject[sources.length];
        for (int i = 0; i < sources.length; i++) {
            String packageName = extractPackageName(sources[i]);
            String className = extractClassName(sources[i]);
            files[i] = JavaFileObjects.forSourceString(packageName + "." + className, sources[i]);
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
