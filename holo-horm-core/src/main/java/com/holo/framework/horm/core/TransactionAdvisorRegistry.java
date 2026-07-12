package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.TransactionAdvisorProvider;
import com.holo.framework.horm.meta.TransactionMethodMeta;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry of transaction metadata.
 *
 * <p>Populated at class-init by first attempting to discover
 * {@link TransactionAdvisorProvider} implementations via {@link ServiceLoader}
 * (the preferred, zero-reflection path). If no ServiceLoader configuration
 * is found, falls back to scanning {@code META-INF/horm/transactions.idx}
 * on the classpath (the legacy, reflection-based path).
 *
 * <p>The index file is written by the {@code holo-horm-meta} annotation
 * processor at compile time — one fully-qualified {@code XxxTransactionAdvisor}
 * class name per line. For each entry the registry performs a one-shot
 * bootstrap reflection call ({@code Class.forName} + read {@code METHODS}
 * static field) to materialize the list of {@link TransactionMethodMeta}
 * and caches them in a {@link ConcurrentHashMap} keyed by class name.
 *
 * <p>This bootstrap reflection is <strong>not</strong> a runtime hot path:
 * it runs once per transactional class during static initialization. All
 * subsequent {@link #lookup(Class, String)} calls are O(1) map lookups —
 * no reflection.
 *
 * <p>Failures during index loading are logged and swallowed so that a
 * missing or malformed index never prevents the registry from initializing;
 * affected classes will simply fail with a clear message when looked up.
 */
public final class TransactionAdvisorRegistry {

    private static final String INDEX_RESOURCE = "META-INF/horm/transactions.idx";

    /**
     * Map from original class name to a map of method name -> TransactionMethodMeta.
     * The inner map uses "*" as a special key for class-level @Transactional.
     */
    private static final Map<String, Map<String, TransactionMethodMeta>> REGISTRY =
        new ConcurrentHashMap<>();

    static {
        loadIndex();
    }

    private TransactionAdvisorRegistry() {
    }

    /**
     * Scans for transaction metadata using ServiceLoader first, then falls
     * back to the legacy {@code META-INF/horm/transactions.idx} classpath index.
     *
     * <p>The ServiceLoader path calls {@link TransactionAdvisorProvider#methods()}
     * and {@link TransactionAdvisorProvider#targetClassName()} directly — no reflection.
     * The legacy path uses {@code Class.forName} + {@code Field.get} as a
     * backward-compatible fallback.
     *
     * <p>To avoid duplicate loading, advisors already loaded via ServiceLoader
     * are skipped during legacy index processing.
     */
    private static void loadIndex() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = TransactionAdvisorRegistry.class.getClassLoader();
        }

        // Preferred path: ServiceLoader<TransactionAdvisorProvider> (zero-reflection)
        Set<String> loadedClasses = loadFromServiceLoader(loader);

        // Fallback path: legacy transactions.idx (reflection-based)
        // Skip advisors already loaded via ServiceLoader to avoid duplicate work
        loadLegacyIndex(loader, loadedClasses);
    }

    private static Set<String> loadFromServiceLoader(ClassLoader loader) {
        Set<String> loadedClasses = new HashSet<>();
        try {
            for (TransactionAdvisorProvider provider : ServiceLoader.load(TransactionAdvisorProvider.class, loader)) {
                try {
                    String targetClassName = provider.targetClassName();
                    List<TransactionMethodMeta> methods = provider.methods();
                    if (targetClassName != null && methods != null && !methods.isEmpty()) {
                        Map<String, TransactionMethodMeta> methodMap = new ConcurrentHashMap<>();
                        for (TransactionMethodMeta meta : methods) {
                            methodMap.put(meta.methodName(), meta);
                        }
                        REGISTRY.put(targetClassName, methodMap);
                        loadedClasses.add(targetClassName);
                    }
                } catch (Exception e) {
                    System.err.println("[HORM] Failed to load transaction advisor from provider "
                        + provider.getClass().getName() + ": " + e);
                }
            }
        } catch (Exception e) {
            System.err.println("[HORM] Failed to enumerate TransactionAdvisorProvider services: " + e);
        }
        return loadedClasses;
    }

    private static void loadLegacyIndex(ClassLoader loader, Set<String> alreadyLoadedClasses) {
        try {
            Enumeration<URL> urls = loader.getResources(INDEX_RESOURCE);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                loadIndexFromUrl(url, loader, alreadyLoadedClasses);
            }
        } catch (Exception e) {
            // Defensive: never fail class-init because of an I/O error.
            System.err.println("[HORM] Failed to enumerate " + INDEX_RESOURCE + ": " + e);
        }
    }

    private static void loadIndexFromUrl(URL url, ClassLoader loader, Set<String> alreadyLoadedClasses) {
        try (InputStream in = url.openStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                registerByAdvisorClassName(trimmed, loader, alreadyLoadedClasses);
            }
        } catch (Exception e) {
            System.err.println("[HORM] Failed to read transaction index " + url + ": " + e);
        }
    }

    private static void registerByAdvisorClassName(String advisorClassName, ClassLoader loader, Set<String> alreadyLoadedClasses) {
        try {
            Class<?> advisorClass = Class.forName(advisorClassName, true, loader);
            Field methodsField = advisorClass.getField("METHODS");
            Object methodsObj = methodsField.get(null);
            if (methodsObj instanceof List<?> methodsList) {
                // Derive the original class name from the advisor class name
                // e.g. "com.example.FooTransactionAdvisor" -> "com.example.Foo"
                String originalClassName = deriveOriginalClassName(advisorClassName);
                // Skip if already loaded via ServiceLoader
                if (!alreadyLoadedClasses.contains(originalClassName)) {
                    Map<String, TransactionMethodMeta> methodMap = new ConcurrentHashMap<>();
                    for (Object obj : methodsList) {
                        if (obj instanceof TransactionMethodMeta meta) {
                            methodMap.put(meta.methodName(), meta);
                        }
                    }
                    REGISTRY.put(originalClassName, methodMap);
                }
            } else {
                System.err.println("[HORM] " + advisorClassName + ".METHODS is not a List: " + methodsObj);
            }
        } catch (Exception e) {
            // Swallow: a single broken entry must not break the whole registry.
            System.err.println("[HORM] Failed to register transaction advisor " + advisorClassName + ": " + e);
        }
    }

    /**
     * Derives the original class name from the advisor class name.
     * e.g. "com.example.FooTransactionAdvisor" -> "com.example.Foo"
     */
    private static String deriveOriginalClassName(String advisorClassName) {
        int lastDot = advisorClassName.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? advisorClassName.substring(lastDot + 1) : advisorClassName;
        String packageName = lastDot >= 0 ? advisorClassName.substring(0, lastDot) : "";

        // Remove "TransactionAdvisor" suffix
        if (simpleName.endsWith("TransactionAdvisor")) {
            String originalSimpleName = simpleName.substring(0, simpleName.length() - "TransactionAdvisor".length());
            return packageName.isEmpty() ? originalSimpleName : packageName + "." + originalSimpleName;
        }
        return advisorClassName;
    }

    /**
     * Looks up the {@link TransactionMethodMeta} for the given class and method name.
     *
     * <p>First checks for a method-level metadata entry. If not found, falls back
     * to the class-level entry (keyed by "*"). Returns {@link Optional#empty()}
     * if no metadata is registered for the class.
     *
     * @param targetClass the target class
     * @param methodName  the method name
     * @return the transaction metadata, or empty if not found
     */
    public static Optional<TransactionMethodMeta> lookup(Class<?> targetClass, String methodName) {
        Map<String, TransactionMethodMeta> methodMap = REGISTRY.get(targetClass.getName());
        if (methodMap == null) {
            return Optional.empty();
        }
        // First try method-level metadata
        TransactionMethodMeta meta = methodMap.get(methodName);
        if (meta != null) {
            return Optional.of(meta);
        }
        // Fall back to class-level metadata (keyed by "*")
        meta = methodMap.get("*");
        return Optional.ofNullable(meta);
    }

    /**
     * Returns {@code true} if transaction metadata is registered for the given class.
     *
     * @param targetClass the target class
     * @return true if any transactional methods are registered for the class
     */
    public static boolean isTransactional(Class<?> targetClass) {
        return REGISTRY.containsKey(targetClass.getName());
    }

    /**
     * Test hook: manually inject transaction metadata bypassing the classpath index.
     * Intended for unit tests that need to exercise transaction interceptor code
     * without compiling a real {@code @Transactional} class through the APT.
     *
     * @param targetClass the target class
     * @param methodName  the method name (use "*" for class-level)
     * @param meta        the transaction metadata
     */
    public static void registerManual(Class<?> targetClass, String methodName, TransactionMethodMeta meta) {
        REGISTRY.computeIfAbsent(targetClass.getName(), k -> new ConcurrentHashMap<>())
            .put(methodName, meta);
    }

    /**
     * Re-runs the classpath index scan, picking up any {@code XxxTransactionAdvisor}
     * classes added since the last load. Entries already in the registry are kept;
     * only missing entries are inserted.
     *
     * <p>Intended as a test hook: {@code @AfterEach} cleanup via {@link #clear()}
     * wipes the registry, and the static initializer does not re-run, so
     * integration tests that depend on classpath scanning must call this method
     * explicitly in their {@code @BeforeAll}.
     */
    public static void reload() {
        loadIndex();
    }

    /**
     * Test hook: clears the registry. Intended for {@code @AfterEach} cleanup
     * so tests do not leak state.
     */
    public static void clear() {
        REGISTRY.clear();
    }
}
