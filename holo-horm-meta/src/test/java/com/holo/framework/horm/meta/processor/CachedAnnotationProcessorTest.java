package com.holo.framework.horm.meta.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * APT compile-testing for the M6 {@code @Cached}/{@code @CachePolicy}
 * annotations.
 *
 * <p>Covers three scenarios required by Task 37:
 * <ul>
 *   <li><b>Happy path</b> — {@code @Cached} entity generates
 *       {@code CACHED}/{@code CACHE_POLICY}/{@code CACHE_LEVELS} constants
 *       in {@code XxxMeta} and wires them into {@code entityMeta()}.</li>
 *   <li><b>R12 validation</b> — invalid {@code ttl}/{@code nullTtl} strings
 *       produce compile errors.</li>
 *   <li><b>Default values</b> — unannotated entity omits cache constants and
 *       leaves {@code cached()=false} (Task 36).</li>
 * </ul>
 *
 * <p>Reuses the stub {@code Model<T>} base class pattern from
 * {@link HormEntityProcessorTest} so that R2 passes for happy-path entities.
 */
class CachedAnnotationProcessorTest {

    private static final String MODEL_SOURCE = """
        package com.holo.framework.horm.core;
        public abstract class Model<T> {
        }
        """;

    /** Minimal @Cached entity using default @CachePolicy values. */
    private static final String CACHED_USER_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.Cached;
        import com.holo.framework.horm.core.Model;

        @Entity
        @Cached
        public class CachedUser extends Model<CachedUser> {
            @Id
            private Long id;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
        }
        """;

    /** @Cached entity with custom @CachePolicy and multiple levels. */
    private static final String CACHED_PRODUCT_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.Cached;
        import com.holo.framework.horm.meta.annotation.CachePolicy;
        import com.holo.framework.horm.meta.annotation.CacheLevel;
        import com.holo.framework.horm.meta.annotation.EvictionPolicy;
        import com.holo.framework.horm.meta.annotation.WriteStrategy;
        import com.holo.framework.horm.core.Model;

        @Entity
        @Cached(levels = {CacheLevel.L1, CacheLevel.L2},
                policy = @CachePolicy(ttl = "1h", eviction = EvictionPolicy.LFU,
                                      maxEntries = 5000, writeStrategy = WriteStrategy.THROUGH,
                                      nullable = false, nullTtl = "30s"))
        public class CachedProduct extends Model<CachedProduct> {
            @Id
            private Long id;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
        }
        """;

    /** @Cached with enabled=false — should behave as unannotated. */
    private static final String CACHED_DISABLED_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.Cached;
        import com.holo.framework.horm.core.Model;

        @Entity
        @Cached(enabled = false)
        public class DisabledCache extends Model<DisabledCache> {
            @Id
            private Long id;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
        }
        """;

    /** Plain entity without @Cached — used to verify defaults. */
    private static final String PLAIN_USER_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.core.Model;

        @Entity
        public class PlainUser extends Model<PlainUser> {
            @Id
            private Long id;
            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
        }
        """;

    @Test
    void cachedEntityGeneratesCacheConstants() throws IOException {
        Compilation comp = compile(CACHED_USER_SOURCE);
        if (comp.status() != Compilation.Status.SUCCESS) {
            comp.errors().forEach(d -> System.out.println("ERROR: " + d.getMessage(null)));
        }
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(comp.errors()).isEmpty();

        String src = src(generated(comp, "CachedUserMeta.java"));
        assertThat(src).contains("public static final boolean CACHED = true");
        assertThat(src).contains("public static final CachePolicy CACHE_POLICY");
        assertThat(src).contains("public static final CacheLevel[] CACHE_LEVELS");
        assertThat(src).contains("new CacheLevel[]{CacheLevel.L1}");
        // entityMeta() factory wires the constants
        assertThat(src).contains(".cached(CACHED)");
        assertThat(src).contains(".cachePolicy(CACHE_POLICY)");
        assertThat(src).contains(".cacheLevels(CACHE_LEVELS)");
    }

    @Test
    void cachedEntityWithDefaultsGeneratesDefaultPolicy() throws IOException {
        Compilation comp = compile(CACHED_USER_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        String src = src(generated(comp, "CachedUserMeta.java"));
        // Default ttl=30m → 30 * 60 * 1_000_000_000 nanos = 1_800_000_000_000
        assertThat(src).contains("Duration.ofNanos(1800000000000L)");
        // Default eviction=LRU, writeStrategy=AROUND
        assertThat(src).contains("EvictionPolicy.LRU");
        assertThat(src).contains("WriteStrategy.AROUND");
        // Default maxEntries=10000, nullable=true
        assertThat(src).contains(".maxEntries(10000)");
        assertThat(src).contains(".nullable(true)");
        // Default nullTtl=1m → 60 * 1_000_000_000 nanos = 60_000_000_000
        assertThat(src).contains("Duration.ofNanos(60000000000L)");
    }

    @Test
    void cachedEntityWithCustomPolicyGeneratesCorrectValues() throws IOException {
        Compilation comp = compile(CACHED_PRODUCT_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(comp.errors()).isEmpty();

        String src = src(generated(comp, "CachedProductMeta.java"));
        // ttl=1h → 3600 * 1_000_000_000 nanos = 3_600_000_000_000
        assertThat(src).contains("Duration.ofNanos(3600000000000L)");
        assertThat(src).contains("EvictionPolicy.LFU");
        assertThat(src).contains("WriteStrategy.THROUGH");
        assertThat(src).contains(".maxEntries(5000)");
        assertThat(src).contains(".nullable(false)");
        // nullTtl=30s → 30 * 1_000_000_000 nanos = 30_000_000_000
        assertThat(src).contains("Duration.ofNanos(30000000000L)");
        // Multiple levels
        assertThat(src).contains("new CacheLevel[]{CacheLevel.L1, CacheLevel.L2}");
    }

    @Test
    void uncachedEntityOmitsCacheConstants() throws IOException {
        Compilation comp = compile(PLAIN_USER_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(comp.errors()).isEmpty();

        String src = src(generated(comp, "PlainUserMeta.java"));
        assertThat(src).doesNotContain("CACHED");
        assertThat(src).doesNotContain("CACHE_POLICY");
        assertThat(src).doesNotContain("CACHE_LEVELS");
        // entityMeta() should not set cache fields (defaults: cached=false)
        assertThat(src).doesNotContain(".cached(");
        assertThat(src).doesNotContain(".cachePolicy(");
        assertThat(src).doesNotContain(".cacheLevels(");
    }

    @Test
    void cachedDisabledOmitsCacheConstants() throws IOException {
        // @Cached(enabled=false) should behave like unannotated
        Compilation comp = compile(CACHED_DISABLED_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(comp.errors()).isEmpty();

        String src = src(generated(comp, "DisabledCacheMeta.java"));
        assertThat(src).doesNotContain("CACHED");
        assertThat(src).doesNotContain("CACHE_POLICY");
        assertThat(src).doesNotContain("CACHE_LEVELS");
    }

    @Test
    void rejectsInvalidTtl() {
        // R12b: @CachePolicy.ttl must be parseable as Duration
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Id;
            import com.holo.framework.horm.meta.annotation.Cached;
            import com.holo.framework.horm.meta.annotation.CachePolicy;
            import com.holo.framework.horm.core.Model;

            @Entity
            @Cached(policy = @CachePolicy(ttl = "not-a-duration"))
            public class Bad extends Model<Bad> {
                @Id private Long id;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("@CachePolicy.ttl")
                && d.getMessage(null).contains("not a valid duration"));
    }

    @Test
    void rejectsInvalidNullTtl() {
        // R12c: @CachePolicy.nullTtl must be parseable as Duration
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Id;
            import com.holo.framework.horm.meta.annotation.Cached;
            import com.holo.framework.horm.meta.annotation.CachePolicy;
            import com.holo.framework.horm.core.Model;

            @Entity
            @Cached(policy = @CachePolicy(nullTtl = "xyz"))
            public class Bad extends Model<Bad> {
                @Id private Long id;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(comp.errors())
            .anyMatch(d -> d.getMessage(null).contains("@CachePolicy.nullTtl")
                && d.getMessage(null).contains("not a valid duration"));
    }

    @Test
    void acceptsIso8601Ttl() throws IOException {
        // ISO-8601 form "PT2H30M" should be accepted
        Compilation comp = compile("""
            package test;
            import com.holo.framework.horm.meta.annotation.Entity;
            import com.holo.framework.horm.meta.annotation.Id;
            import com.holo.framework.horm.meta.annotation.Cached;
            import com.holo.framework.horm.meta.annotation.CachePolicy;
            import com.holo.framework.horm.core.Model;

            @Entity
            @Cached(policy = @CachePolicy(ttl = "PT2H30M"))
            public class Iso extends Model<Iso> {
                @Id private Long id;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
            }
            """);

        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(comp.errors()).isEmpty();

        String src = src(generated(comp, "IsoMeta.java"));
        // PT2H30M = 2*3600 + 30*60 = 9000 seconds = 9_000_000_000_000 nanos
        assertThat(src).contains("Duration.ofNanos(9000000000000L)");
    }

    private static Compilation compile(String... sources) {
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
