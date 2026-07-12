package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.Mapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EntityMetaRegistry}.
 *
 * <p>{@link EntityMeta} is {@code final} and cannot be mocked; each test
 * builds a real instance via {@link EntityMeta.Builder} (with a mocked
 * {@link Mapper}) and injects it through {@link EntityMetaRegistry#registerManual}.
 *
 * <p>The classpath-index loading path ({@code loadIndex}) is exercised via
 * a test-only {@code META-INF/horm/entities.idx} resource that lists
 * {@link IndexedEntityMeta} plus two deliberately-broken entries
 * ({@code NonExistentMeta}, {@link BadEntityMeta}) to drive the registry's
 * defensive catch and {@code instanceof} branches.
 */
class EntityMetaRegistryTest {

    @AfterEach
    void clearRegistry() {
        EntityMetaRegistry.clear();
    }

    @Test
    void lookupThrowsForUnregisteredEntity() {
        EntityMetaRegistry.clear();
        assertThatThrownBy(() -> EntityMetaRegistry.lookup(Object.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not registered");
    }

    @SuppressWarnings("unchecked")
    @Test
    void registerManualThenLookupSucceeds() {
        EntityMetaRegistry.clear();
        Mapper<String> mapper = mock(Mapper.class);
        EntityMeta<String> meta = EntityMeta.<String>builder()
            .type(String.class)
            .tableName("strings")
            .fields(List.of())
            .mapper(mapper)
            .build();

        EntityMetaRegistry.registerManual(meta);

        assertThat(EntityMetaRegistry.lookup(String.class)).isSameAs(meta);
    }

    @SuppressWarnings("unchecked")
    @Test
    void concurrentLookupIsThreadSafe() throws Exception {
        EntityMetaRegistry.clear();
        Mapper<String> mapper = mock(Mapper.class);
        EntityMeta<String> meta = EntityMeta.<String>builder()
            .type(String.class)
            .tableName("strings")
            .fields(List.of())
            .mapper(mapper)
            .build();
        EntityMetaRegistry.registerManual(meta);

        // 100 tasks × 1000 lookups each, on a 10-thread pool — exercises
        // concurrent reads against the same key. A data race in the registry
        // would manifest as a NullPointerException or returned mismatch.
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(pool.submit(() -> {
                for (int j = 0; j < 1000; j++) {
                    EntityMeta<String> looked = EntityMetaRegistry.lookup(String.class);
                    if (looked != meta) {
                        throw new AssertionError("Concurrent lookup returned stale meta");
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    /**
     * Re-invokes the private {@code loadIndex()} via reflection to verify
     * that {@code META-INF/horm/entities.idx} is parsed end to end:
     * resource enumeration → line parsing → {@code Class.forName} →
     * {@code entityMeta()} reflection → registry insertion.
     *
     * <p>The index also lists {@code NonExistentMeta} (drives
     * {@code Class.forName} catch block) and {@link BadEntityMeta}
     * (drives the {@code instanceof} false branch). Both must be swallowed
     * without preventing {@link IndexedEntityMeta} from registering.
     */
    @Test
    void loadIndexReadsEntitiesIdxFromClasspath() throws Exception {
        EntityMetaRegistry.clear();

        Method loadIndex = EntityMetaRegistry.class.getDeclaredMethod("loadIndex");
        loadIndex.setAccessible(true);
        loadIndex.invoke(null);

        EntityMeta<IndexedEntity> meta = EntityMetaRegistry.lookup(IndexedEntity.class);
        assertThat(meta).isNotNull();
        assertThat(meta.tableName()).isEqualTo("indexed_entities");
        assertThat(meta.mapper()).isNotNull();
    }

    /**
     * Drives {@code loadIndexFromUrl}'s catch block by pointing it at a URL
     * whose {@code openStream()} throws. The registry must swallow the
     * {@link IOException} without propagating.
     */
    @Test
    void loadIndexFromUrlSwallowsIoErrors() throws Exception {
        Method m = EntityMetaRegistry.class.getDeclaredMethod(
            "loadIndexFromUrl", URL.class, ClassLoader.class, java.util.Set.class);
        m.setAccessible(true);
        // file:/// URL pointing at a path that does not exist — openStream()
        // throws FileNotFoundException (an IOException), exercising the catch.
        URL badUrl = new URL("file:///this/path/does/not/exist/entities.idx");
        m.invoke(null, badUrl, getClass().getClassLoader(), new java.util.HashSet<>());
        // No exception propagated == pass; the catch block ran and returned.
    }

    /**
     * Drives {@code loadIndex}'s outer catch block by installing a context
     * classloader whose {@code getResources} throws. The registry must
     * swallow the {@link IOException} without propagating.
     */
    @Test
    void loadIndexSwallowsEnumerationErrors() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            ClassLoader throwingLoader = mock(ClassLoader.class);
            when(throwingLoader.getResources("META-INF/horm/entities.idx"))
                .thenThrow(new IOException("boom"));

            Thread.currentThread().setContextClassLoader(throwingLoader);

            Method loadIndex = EntityMetaRegistry.class.getDeclaredMethod("loadIndex");
            loadIndex.setAccessible(true);
            loadIndex.invoke(null);
            // No exception propagated == pass; the outer catch block ran.
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}

