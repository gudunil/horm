package com.holo.framework.horm.cache.key;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CacheKeyBuilder} covering default values, the
 * entity-type overloads, every key-segment helper and the validation rules.
 */
class CacheKeyBuilderTest {

    @Test
    void defaultsPartitionAndVersionWhenNotExplicitlySet() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .idKey(1L)
            .build();

        assertThat(key.partition()).isEqualTo("default");
        assertThat(key.version()).isEqualTo("v1");
    }

    @Test
    void entityTypeFromClassUsesSimpleName() {
        CacheKey key = new CacheKeyBuilder()
            .entityType(User.class)
            .idKey(1L)
            .build();

        assertThat(key.entityType()).isEqualTo("User");
        assertThat(key.toString()).startsWith("User:");
    }

    @Test
    void entityTypeFromStringSetsValueDirectly() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("Order")
            .idKey(99L)
            .build();

        assertThat(key.entityType()).isEqualTo("Order");
    }

    @Test
    void idKeySetsKeyTypeToIdAndStringifiesValue() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .idKey(42L)
            .build();

        assertThat(key.keyType()).isEqualTo("id");
        assertThat(key.keyValue()).isEqualTo("42");
        assertThat(key.toString()).isEqualTo("User:default:id:42:v1");
    }

    @Test
    void idKeyAcceptsStringValue() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .idKey("uuid-abc-123")
            .build();

        assertThat(key.keyValue()).isEqualTo("uuid-abc-123");
        assertThat(key.toString()).isEqualTo("User:default:id:uuid-abc-123:v1");
    }

    @Test
    void queryKeySetsKeyTypeToQuery() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .queryKey("abcdef0123456789")
            .build();

        assertThat(key.keyType()).isEqualTo("query");
        assertThat(key.keyValue()).isEqualTo("abcdef0123456789");
    }

    @Test
    void customKeyAcceptsArbitraryKeyType() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .customKey("email", "alice@holo.dev")
            .build();

        assertThat(key.keyType()).isEqualTo("email");
        assertThat(key.keyValue()).isEqualTo("alice@holo.dev");
        assertThat(key.toString()).isEqualTo("User:default:email:alice@holo.dev:v1");
    }

    @Test
    void sensitiveDelegatesToSensitiveHash() {
        Object value = "secret-token";
        String expectedHash = SensitiveHash.hash(value);

        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .sensitive(value)
            .build();

        assertThat(key.keyType()).isEqualTo("sensitive");
        assertThat(key.keyValue()).isEqualTo(expectedHash);
        // The raw value must not appear in the rendered key.
        assertThat(key.toString()).doesNotContain("secret-token");
    }

    @Test
    void partitionAndVersionAreOverridable() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .partition("tenant_42")
            .version("v3")
            .idKey(1L)
            .build();

        assertThat(key.toString()).isEqualTo("User:tenant_42:id:1:v3");
    }

    @Test
    void buildFailsWhenEntityTypeMissing() {
        assertThatThrownBy(() -> new CacheKeyBuilder().idKey(1L).build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("entityType");
    }

    @Test
    void buildFailsWhenNoKeySegmentProvided() {
        assertThatThrownBy(() -> new CacheKeyBuilder().entityType("User").build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("key segment");
    }

    @Test
    void entityTypeClassRejectsNull() {
        assertThatThrownBy(() -> new CacheKeyBuilder().entityType((Class<?>) null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entityType");
    }

    @Test
    void entityTypeStringRejectsBlank() {
        assertThatThrownBy(() -> new CacheKeyBuilder().entityType("  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entityType");
    }

    @Test
    void idKeyRejectsNull() {
        assertThatThrownBy(() -> new CacheKeyBuilder().entityType("User").idKey(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id");
    }

    @Test
    void queryKeyRejectsBlank() {
        assertThatThrownBy(() -> new CacheKeyBuilder().entityType("User").queryKey(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("queryHash");
    }

    @Test
    void customKeyRejectsBlankKeyType() {
        assertThatThrownBy(() -> new CacheKeyBuilder().entityType("User").customKey("", "v"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("keyType");
    }

    @Test
    void customKeyRejectsNullKeyValue() {
        assertThatThrownBy(() -> new CacheKeyBuilder().entityType("User").customKey("k", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("keyValue");
    }

    @Test
    void sensitiveRejectsNull() {
        assertThatThrownBy(() -> new CacheKeyBuilder().entityType("User").sensitive(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("value");
    }

    @Test
    void versionRejectsBlank() {
        assertThatThrownBy(() -> new CacheKeyBuilder()
            .entityType("User")
            .version("")
            .idKey(1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version");
    }

    @Test
    void partitionRejectsBlank() {
        assertThatThrownBy(() -> new CacheKeyBuilder()
            .entityType("User")
            .partition("")
            .idKey(1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("partition");
    }

    /** Test-only Model subclass so {@code entityType(Class)} can use a
     *  simple name. */
    static class User {
        // intentionally empty — only the class name is needed
    }
}
