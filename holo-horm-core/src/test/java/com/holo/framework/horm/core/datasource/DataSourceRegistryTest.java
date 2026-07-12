package com.holo.framework.horm.core.datasource;

import com.holo.framework.horm.core.DataSourceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceRegistryTest {

    private DataSourceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DataSourceRegistry();
    }

    @Test
    void registerAndGet() throws SQLException {
        DataSourceProvider provider = mockProvider();
        registry.register("test", provider);

        assertThat(registry.get("test")).isSameAs(provider);
        assertThat(registry.contains("test")).isTrue();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void registerDefaultAndGetDefault() throws SQLException {
        DataSourceProvider provider = mockProvider();
        registry.registerDefault(provider);

        assertThat(registry.getDefault()).isSameAs(provider);
        assertThat(registry.hasDefault()).isTrue();
        assertThat(registry.get(DataSourceRegistry.DEFAULT_NAME)).isSameAs(provider);
    }

    @Test
    void resolveWithNullReturnsDefault() throws SQLException {
        DataSourceProvider defaultProvider = mockProvider();
        DataSourceProvider namedProvider = mockProvider();
        registry.registerDefault(defaultProvider);
        registry.register("secondary", namedProvider);

        assertThat(registry.resolve(null)).isSameAs(defaultProvider);
        assertThat(registry.resolve("")).isSameAs(defaultProvider);
        assertThat(registry.resolve("secondary")).isSameAs(namedProvider);
    }

    @Test
    void registerDuplicateThrows() throws SQLException {
        DataSourceProvider provider1 = mockProvider();
        DataSourceProvider provider2 = mockProvider();
        registry.register("test", provider1);

        assertThatThrownBy(() -> registry.register("test", provider2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    void registerWithEmptyNameThrows() throws SQLException {
        DataSourceProvider provider = mockProvider();

        assertThatThrownBy(() -> registry.register("", provider))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
    }

    @Test
    void registerWithNullNameThrows() throws SQLException {
        DataSourceProvider provider = mockProvider();

        assertThatThrownBy(() -> registry.register(null, provider))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registerWithNullProviderThrows() {
        assertThatThrownBy(() -> registry.register("test", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getUnregisteredThrows() {
        assertThatThrownBy(() -> registry.get("unknown"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No datasource registered");
    }

    @Test
    void getDefaultUnregisteredThrows() {
        assertThatThrownBy(() -> registry.getDefault())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveUnregisteredThrows() throws SQLException {
        registry.registerDefault(mockProvider());

        assertThatThrownBy(() -> registry.resolve("unknown"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clearRemovesAll() throws SQLException {
        registry.register("test1", mockProvider());
        registry.register("test2", mockProvider());
        assertThat(registry.size()).isEqualTo(2);

        registry.clear();

        assertThat(registry.size()).isEqualTo(0);
        assertThat(registry.contains("test1")).isFalse();
        assertThat(registry.hasDefault()).isFalse();
    }

    @Test
    void containsReturnsFalseForUnregistered() {
        assertThat(registry.contains("unknown")).isFalse();
    }

    @Test
    void hasDefaultReturnsFalseWhenNotRegistered() {
        assertThat(registry.hasDefault()).isFalse();
    }

    private DataSourceProvider mockProvider() throws SQLException {
        return new DataSourceProvider() {
            @Override
            public Connection getConnection() {
                return null;
            }
        };
    }
}
