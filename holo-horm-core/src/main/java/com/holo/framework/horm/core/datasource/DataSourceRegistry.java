package com.holo.framework.horm.core.datasource;

import com.holo.framework.horm.core.DataSourceProvider;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for named {@link DataSourceProvider} instances.
 *
 * <p>M5 introduces multi-datasource support. Each datasource is registered
 * with a logical name; entities declare their target datasource via
 * {@code @Entity(dataSource = "name")}. The special name {@value #DEFAULT_NAME}
 * denotes the default datasource used when no explicit name is specified.
 *
 * <p>This registry is thread-safe; datasources can be registered and
 * retrieved concurrently.
 */
public final class DataSourceRegistry {

    /** The reserved name for the default datasource. */
    public static final String DEFAULT_NAME = "default";

    private final Map<String, DataSourceProvider> providers = new ConcurrentHashMap<>();

    /**
     * Registers a datasource with the given name.
     *
     * @param name     logical name (must not be null or empty)
     * @param provider the datasource provider (must not be null)
     * @throws IllegalArgumentException if name is empty or provider is null
     * @throws IllegalStateException    if a datasource with the same name is already registered
     */
    public void register(String name, DataSourceProvider provider) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("datasource name must not be empty");
        }
        if (providers.putIfAbsent(name, provider) != null) {
            throw new IllegalStateException("Datasource already registered: " + name);
        }
    }

    /**
     * Registers the given datasource as the default (name = {@value #DEFAULT_NAME}).
     *
     * @param provider the default datasource provider
     * @throws IllegalStateException if a default datasource is already registered
     */
    public void registerDefault(DataSourceProvider provider) {
        register(DEFAULT_NAME, provider);
    }

    /**
     * Returns the datasource registered under the given name.
     *
     * @param name the datasource name
     * @return the provider
     * @throws IllegalStateException if no datasource is registered with that name
     */
    public DataSourceProvider get(String name) {
        DataSourceProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalStateException("No datasource registered with name: " + name);
        }
        return provider;
    }

    /**
     * Returns the default datasource.
     *
     * @throws IllegalStateException if no default datasource is registered
     */
    public DataSourceProvider getDefault() {
        return get(DEFAULT_NAME);
    }

    /**
     * Returns the datasource for the given entity datasource name. If the
     * name is null or empty, returns the default datasource.
     *
     * @param dataSourceName the name declared in {@code @Entity(dataSource = ...)}, may be null/empty
     * @return the resolved provider
     * @throws IllegalStateException if the named datasource is not registered
     */
    public DataSourceProvider resolve(String dataSourceName) {
        if (dataSourceName == null || dataSourceName.isEmpty()) {
            return getDefault();
        }
        return get(dataSourceName);
    }

    /**
     * Returns {@code true} if a datasource with the given name is registered.
     */
    public boolean contains(String name) {
        return providers.containsKey(name);
    }

    /**
     * Returns {@code true} if a default datasource is registered.
     */
    public boolean hasDefault() {
        return providers.containsKey(DEFAULT_NAME);
    }

    /**
     * Clears all registered datasources. Intended for test cleanup.
     */
    public void clear() {
        providers.clear();
    }

    /**
     * Returns the number of registered datasources.
     */
    public int size() {
        return providers.size();
    }

    /**
     * Returns an unmodifiable view of all registered datasource entries.
     */
    public Set<Map.Entry<String, DataSourceProvider>> entries() {
        return Collections.unmodifiableSet(providers.entrySet());
    }
}
