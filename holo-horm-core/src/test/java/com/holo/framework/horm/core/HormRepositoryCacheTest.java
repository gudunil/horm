package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.annotation.GenerationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
     * Unit tests for the repository-instance caching in {@link Horm#repository}
     * (O1 optimization), verifying that the same {@link JdbcRepository} is
     * reused across calls for the same {@link HormContext}, and that switching
     * contexts no longer invalidates the cache for previously seen contexts.
     *
     * <p><b>Global state.</b> These tests install a process-wide
     * {@link HormContext} via {@link Horm#install}. The {@code @AfterEach}
     * clears it with {@code HormContext.install(null)} to prevent static
     * reference leaks (per project constraint).
     */
class HormRepositoryCacheTest {

    @SuppressWarnings("unchecked")
    private static EntityMeta<TestEntity> buildMeta() {
        Mapper<TestEntity> mapper = mock(Mapper.class);
        FieldMeta<Long> idField = FieldMeta.<Long>builder()
            .name("id").column("id").type(Long.class).id(true)
            .generationStrategy(GenerationType.IDENTITY)
            .build();
        FieldMeta<String> emailField = FieldMeta.<String>builder()
            .name("email").column("email").type(String.class)
            .build();
        return EntityMeta.<TestEntity>builder()
            .type(TestEntity.class)
            .tableName("test_entities")
            .fields(List.of(idField, emailField))
            .idField(idField)
            .mapper(mapper)
            .build();
    }

    @BeforeEach
    void setUp() {
        EntityMetaRegistry.registerManual(buildMeta());
    }

    @AfterEach
    void tearDown() {
        HormContext.install(null);
        EntityMetaRegistry.clear();
    }

    @Test
    void repositoryReturnsSameInstanceForSameContext() {
        Connection conn = mock(Connection.class);
        HormContext ctx = new HormContext(conn);
        Horm.install(ctx);

        Repository<TestEntity> first = Horm.repository(TestEntity.class);
        Repository<TestEntity> second = Horm.repository(TestEntity.class);

        assertThat(first).isSameAs(second);
    }

    @Test
    void repositoryCacheInvalidatesOnContextSwitch() {
        Connection conn1 = mock(Connection.class);
        HormContext ctx1 = new HormContext(conn1);
        Horm.install(ctx1);
        Repository<TestEntity> first = Horm.repository(TestEntity.class);

        Connection conn2 = mock(Connection.class);
        HormContext ctx2 = new HormContext(conn2);
        Horm.install(ctx2);
        Repository<TestEntity> second = Horm.repository(TestEntity.class);

        assertThat(second).isNotSameAs(first);
    }

    @Test
    void repositoryCacheRepopulatesAfterSwitch() {
        Connection conn1 = mock(Connection.class);
        HormContext ctx1 = new HormContext(conn1);
        Horm.install(ctx1);
        Horm.repository(TestEntity.class);

        Connection conn2 = mock(Connection.class);
        HormContext ctx2 = new HormContext(conn2);
        Horm.install(ctx2);
        Repository<TestEntity> firstAfterSwitch = Horm.repository(TestEntity.class);
        Repository<TestEntity> secondAfterSwitch = Horm.repository(TestEntity.class);

        assertThat(firstAfterSwitch).isSameAs(secondAfterSwitch);
    }

    @Test
    void repositoryCacheRetainsInstancesWhenSwitchingBackToPreviousContext() {
        Connection conn1 = mock(Connection.class);
        HormContext ctx1 = new HormContext(conn1);
        Horm.install(ctx1);
        Repository<TestEntity> first = Horm.repository(TestEntity.class);

        Connection conn2 = mock(Connection.class);
        HormContext ctx2 = new HormContext(conn2);
        Horm.install(ctx2);
        Horm.repository(TestEntity.class);

        Horm.install(ctx1);
        Repository<TestEntity> afterSwitchBack = Horm.repository(TestEntity.class);

        assertThat(afterSwitchBack).isSameAs(first);
    }

    /** CRTP subtype used only as a registry key for these tests. */
    static final class TestEntity extends Model<TestEntity> {
    }
}
