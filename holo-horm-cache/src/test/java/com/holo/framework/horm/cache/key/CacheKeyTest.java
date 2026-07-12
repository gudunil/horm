package com.holo.framework.horm.cache.key;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CacheKey} covering the canonical string form,
 * partition/version overrides and {@code equals}/{@code hashCode} semantics.
 */
class CacheKeyTest {

    @Test
    void primaryKeyCacheKeyRendersDefaultPartitionAndVersion() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .idKey(123L)
            .build();

        assertThat(key.toString()).isEqualTo("User:default:id:123:v1");
    }

    @Test
    void toStringRendersExplicitPartitionSegment() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .partition("tenant_001")
            .idKey(123L)
            .build();

        assertThat(key.toString()).isEqualTo("User:tenant_001:id:123:v1");
    }

    @Test
    void queryCacheKeyUsesQueryHashAsKeyValue() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .queryKey("abcdef0123456789")
            .build();

        assertThat(key.toString()).isEqualTo("User:default:query:abcdef0123456789:v1");
    }

    @Test
    void customVersionAppearsInTrailingSegment() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .version("v2")
            .idKey(123L)
            .build();

        assertThat(key.toString()).isEqualTo("User:default:id:123:v2");
    }

    @Test
    void staticFactoryOfProducesSameStringAsBuilder() {
        CacheKey fromFactory = CacheKey.of("User", "default", "query", "abcdef0123456789", "v1");

        assertThat(fromFactory.toString()).isEqualTo("User:default:query:abcdef0123456789:v1");
    }

    @Test
    void equalsAndHashCodeAreBasedOnToString() {
        CacheKey a = new CacheKeyBuilder()
            .entityType("User")
            .partition("tenant_001")
            .idKey(123L)
            .build();
        CacheKey b = new CacheKeyBuilder()
            .entityType("User")
            .partition("tenant_001")
            .idKey(123L)
            .build();
        CacheKey c = new CacheKeyBuilder()
            .entityType("User")
            .partition("tenant_002")
            .idKey(123L)
            .build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
        assertThat(a.hashCode()).isNotEqualTo(c.hashCode());
    }

    @Test
    void equalsReturnsFalseForNullAndOtherTypes() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .idKey(1L)
            .build();

        assertThat(key).isNotEqualTo(null);
        assertThat(key).isNotEqualTo("User:default:id:1:v1");
    }

    @Test
    void equalsReturnsTrueForSameInstance() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .idKey(1L)
            .build();

        assertThat(key).isEqualTo(key);
    }

    @Test
    void gettersExposeRawSegments() {
        CacheKey key = new CacheKeyBuilder()
            .entityType("User")
            .partition("tenant_001")
            .idKey(123L)
            .version("v2")
            .build();

        assertThat(key.entityType()).isEqualTo("User");
        assertThat(key.partition()).isEqualTo("tenant_001");
        assertThat(key.keyType()).isEqualTo("id");
        assertThat(key.keyValue()).isEqualTo("123");
        assertThat(key.version()).isEqualTo("v2");
    }
}
