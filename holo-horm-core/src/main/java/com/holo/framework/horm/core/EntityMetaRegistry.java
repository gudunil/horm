package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * Runtime registry of {@link EntityMeta} instances, populated at class-init
 * by scanning {@code META-INF/horm/entities.idx} on the classpath.
 *
 * <p>The index file is written by the {@code holo-horm-meta} annotation
 * processor at compile time — one fully-qualified {@code XxxMeta} class name
 * per line. For each entry the registry performs a one-shot bootstrap
 * reflection call ({@code Class.forName} + invoke {@code entityMeta()}) to
 * materialize the {@link EntityMeta} and caches it in a
 * {@link java.util.concurrent.ConcurrentHashMap} keyed by entity type.
 *
 * <p>This bootstrap reflection is <strong>not</strong> a runtime hot path:
 * it runs once per entity class during static initialization. All subsequent
 * {@link #lookup(Class)} calls are O(1) map lookups — no reflection.
 *
 * <p>Failures during index loading are logged and swallowed so that a
 * missing or malformed index never prevents the registry from initializing;
 * affected entity types will simply fail with a clear
 * {@link IllegalStateException} on {@link #lookup(Class)}.
 */
public final class EntityMetaRegistry {

    private static final String INDEX_RESOURCE = "META-INF/horm/entities.idx";

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, EntityMeta<?>> REGISTRY =
        new java.util.concurrent.ConcurrentHashMap<>();

    static {
        loadIndex();
    }

    private EntityMetaRegistry() {
    }

    /**
     * Scans every {@code META-INF/horm/entities.idx} on the classpath and
     * registers the corresponding {@link EntityMeta} instances.
     *
     * <p>Multiple jars may each contribute their own index file; the
     * classloader's {@link ClassLoader#getResources(String)} enumeration
     * merges them transparently.
     */
    private static void loadIndex() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = EntityMetaRegistry.class.getClassLoader();
        }
        try {
            Enumeration<URL> urls = loader.getResources(INDEX_RESOURCE);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                loadIndexFromUrl(url, loader);
            }
        } catch (Exception e) {
            // Defensive: never fail class-init because of an I/O error.
            System.err.println("[HORM] Failed to enumerate " + INDEX_RESOURCE + ": " + e);
        }
    }

    private static void loadIndexFromUrl(URL url, ClassLoader loader) {
        try (InputStream in = url.openStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .forEach(line -> registerByMetaClassName(line, loader));
        } catch (Exception e) {
            System.err.println("[HORM] Failed to read entity index " + url + ": " + e);
        }
    }

    private static void registerByMetaClassName(String metaClassName, ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName(metaClassName, true, loader);
            Method factory = clazz.getMethod("entityMeta");
            Object meta = factory.invoke(null);
            if (meta instanceof EntityMeta<?> em) {
                REGISTRY.put(em.type(), em);
            } else {
                System.err.println("[HORM] " + metaClassName + ".entityMeta() returned non-EntityMeta: " + meta);
            }
        } catch (Exception e) {
            // Swallow: a single broken entry must not break the whole registry.
            System.err.println("[HORM] Failed to register entity meta " + metaClassName + ": " + e);
        }
    }

    /**
     * Looks up the {@link EntityMeta} for the given entity type.
     *
     * @throws IllegalStateException if the type is not registered (i.e. not
     *         annotated with {@code @Entity} or its {@code XxxMeta} class is
     *         not on the classpath)
     */
    @SuppressWarnings("unchecked")
    public static <T> EntityMeta<T> lookup(Class<T> entityType) {
        EntityMeta<?> meta = REGISTRY.get(entityType);
        if (meta == null) {
            throw new IllegalStateException(
                "Entity " + entityType.getName() + " not registered; did you annotate it with @Entity?");
        }
        return (EntityMeta<T>) meta;
    }

    /**
     * Test hook: manually inject an {@link EntityMeta} bypassing the
     * classpath index. Intended for unit tests that need to exercise
     * {@link Model} / repository code without compiling a real
     * {@code @Entity} class through the APT.
     */
    public static void registerManual(EntityMeta<?> meta) {
        REGISTRY.put(meta.type(), meta);
    }

    /**
     * Re-runs the classpath index scan, picking up any {@code XxxMeta}
     * classes added since the last load. Entries already in the registry
     * are kept; only missing entries are inserted.
     *
     * <p>Intended as a test hook: {@code @AfterEach} cleanup via
     * {@link #clear()} wipes the registry, and the static initializer does
     * not re-run, so integration tests that depend on classpath scanning
     * must call this method explicitly in their {@code @BeforeAll}.
     */
    public static void reload() {
        loadIndex();
    }

    /**
     * Test hook: clears the registry. Intended for {@code @AfterEach}
     * cleanup so tests do not leak state.
     */
    static void clear() {
        REGISTRY.clear();
    }
}
