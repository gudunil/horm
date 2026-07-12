package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.EntityMetaProvider;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Runtime registry of {@link EntityMeta} instances.
 *
 * <p>Populated at class-init by first attempting to discover
 * {@link EntityMetaProvider} implementations via {@link ServiceLoader}
 * (the preferred, zero-reflection path). If no ServiceLoader configuration
 * is found, falls back to scanning {@code META-INF/horm/entities.idx} on
 * the classpath (the legacy, reflection-based path).
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
     * Scans for entity metadata using ServiceLoader first, then falls back
     * to the legacy {@code META-INF/horm/entities.idx} classpath index.
     *
     * <p>The ServiceLoader path calls {@link EntityMetaProvider#provide()}
     * directly — no reflection. The legacy path uses {@code Class.forName}
     * + {@code Method.invoke} as a backward-compatible fallback.
     *
     * <p>To avoid duplicate loading, entities already loaded via ServiceLoader
     * are skipped during legacy index processing.
     */
    private static void loadIndex() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = EntityMetaRegistry.class.getClassLoader();
        }

        // Preferred path: ServiceLoader<EntityMetaProvider> (zero-reflection)
        Set<Class<?>> loadedTypes = loadFromServiceLoader(loader);

        // Fallback path: legacy entities.idx (reflection-based)
        // Skip entities already loaded via ServiceLoader to avoid duplicate work
        loadLegacyIndex(loader, loadedTypes);
    }

    private static Set<Class<?>> loadFromServiceLoader(ClassLoader loader) {
        Set<Class<?>> loadedTypes = new HashSet<>();
        try {
            for (EntityMetaProvider provider : ServiceLoader.load(EntityMetaProvider.class, loader)) {
                try {
                    EntityMeta<?> meta = provider.provide();
                    if (meta != null) {
                        REGISTRY.put(meta.type(), meta);
                        loadedTypes.add(meta.type());
                    }
                } catch (Exception e) {
                    System.err.println("[HORM] Failed to load entity from provider "
                        + provider.getClass().getName() + ": " + e);
                }
            }
        } catch (Exception e) {
            System.err.println("[HORM] Failed to enumerate EntityMetaProvider services: " + e);
        }
        return loadedTypes;
    }

    private static void loadLegacyIndex(ClassLoader loader, Set<Class<?>> alreadyLoadedTypes) {
        try {
            Enumeration<URL> urls = loader.getResources(INDEX_RESOURCE);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                loadIndexFromUrl(url, loader, alreadyLoadedTypes);
            }
        } catch (Exception e) {
            // Defensive: never fail class-init because of an I/O error.
            System.err.println("[HORM] Failed to enumerate " + INDEX_RESOURCE + ": " + e);
        }
    }

    private static void loadIndexFromUrl(URL url, ClassLoader loader, Set<Class<?>> alreadyLoadedTypes) {
        try (InputStream in = url.openStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .forEach(line -> registerByMetaClassName(line, loader, alreadyLoadedTypes));
        } catch (Exception e) {
            System.err.println("[HORM] Failed to read entity index " + url + ": " + e);
        }
    }

    private static void registerByMetaClassName(String metaClassName, ClassLoader loader, Set<Class<?>> alreadyLoadedTypes) {
        try {
            Class<?> clazz = Class.forName(metaClassName, true, loader);
            Method factory = clazz.getMethod("entityMeta");
            Object meta = factory.invoke(null);
            if (meta instanceof EntityMeta<?> em) {
                // Skip if already loaded via ServiceLoader
                if (!alreadyLoadedTypes.contains(em.type())) {
                    REGISTRY.put(em.type(), em);
                }
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
