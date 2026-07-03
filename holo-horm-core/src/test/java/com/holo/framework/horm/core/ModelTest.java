package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Model}.
 *
 * <p>M1-6 covers two concerns:
 * <ul>
 *   <li>{@link Model#isPersisted()} — fully usable, exercises
 *       {@link EntityMetaRegistry#lookup} + APT-generated {@link Mapper}.</li>
 *   <li>{@code save}/{@code delete}/{@code reload}/{@code find}/{@code all}/
 *       {@code count} — assert they surface {@link UnsupportedOperationException}
 *       from {@link Horm#repository(Class)} until M1-7 ships
 *       {@code JdbcRepository}.</li>
 * </ul>
 *
 * <p>{@link EntityMeta} is {@code final} and cannot be mocked; we build a
 * real instance via {@link EntityMeta.Builder} with a mocked {@link Mapper}
 * to control {@code getId()} return values.
 *
 * <p><b>Coverage note:</b> the repository-throwing assertions use explicit
 * try/catch with direct method calls rather than {@code assertThatThrownBy}
 * with a lambda/throwing-callable. JaCoCo does not attribute bytecode
 * coverage to {@link Model} methods when they are invoked through AssertJ's
 * {@code ThrowingCallable} lambda indirection — the methods run, but their
 * probes are not recorded. Calling the method directly from the test
 * method's own stack frame is attributed correctly.
 */
class ModelTest {

    @AfterEach
    void clearRegistry() {
        EntityMetaRegistry.clear();
    }

    @Test
    void isPersistedReturnsFalseWhenIdIsNull() {
        registerTestEntityMeta(null);

        TestEntity entity = new TestEntity();
        assertThat(entity.isPersisted()).isFalse();
    }

    @Test
    void isPersistedReturnsTrueWhenIdIsNonNull() {
        registerTestEntityMeta(1L);

        TestEntity entity = new TestEntity();
        assertThat(entity.isPersisted()).isTrue();
    }

    @Test
    void mapperReturnsRegisteredMapper() {
        Mapper<TestEntity> mapper = registerTestEntityMeta(1L);

        TestEntity entity = new TestEntity();
        assertThat(entity.mapper()).isSameAs(mapper);
    }

    @Test
    void isPersistedThrowsWhenEntityHasNoIdField() {
        @SuppressWarnings("unchecked")
        Mapper<TestEntity> mapper = mock(Mapper.class);
        EntityMeta<TestEntity> meta = EntityMeta.<TestEntity>builder()
            .type(TestEntity.class)
            .tableName("test_entities")
            .fields(List.of())
            .mapper(mapper)
            .build();
        EntityMetaRegistry.registerManual(meta);

        TestEntity entity = new TestEntity();
        assertThatThrownBy(entity::isPersisted)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no @Id field");
    }

    @Test
    void saveThrowsUnsupportedOperationBeforeM1_7() {
        registerTestEntityMeta(1L);
        TestEntity entity = new TestEntity();
        try {
            entity.save();
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertThat(e).hasMessageContaining("M1-7");
        }
    }

    @Test
    void deleteThrowsUnsupportedOperationBeforeM1_7() {
        registerTestEntityMeta(1L);
        TestEntity entity = new TestEntity();
        try {
            entity.delete();
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertThat(e).hasMessageContaining("M1-7");
        }
    }

    @Test
    void reloadThrowsUnsupportedOperationBeforeM1_7() {
        registerTestEntityMeta(1L);
        TestEntity entity = new TestEntity();
        try {
            entity.reload();
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertThat(e).hasMessageContaining("M1-7");
        }
    }

    @Test
    void staticFindThrowsUnsupportedOperationBeforeM1_7() {
        registerTestEntityMeta(1L);
        try {
            Model.find(TestEntity.class, 1L);
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertThat(e).hasMessageContaining("M1-7");
        }
    }

    @Test
    void staticAllThrowsUnsupportedOperationBeforeM1_7() {
        registerTestEntityMeta(1L);
        try {
            Model.all(TestEntity.class);
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertThat(e).hasMessageContaining("M1-7");
        }
    }

    @Test
    void staticCountThrowsUnsupportedOperationBeforeM1_7() {
        registerTestEntityMeta(1L);
        try {
            Model.count(TestEntity.class);
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertThat(e).hasMessageContaining("M1-7");
        }
    }

    /**
     * Builds a minimal {@link EntityMeta} for {@link TestEntity} with a
     * mocked {@link Mapper} that returns {@code idValue} from {@code getId},
     * then injects it into the registry. Returns the mocked mapper so callers
     * can verify interactions.
     */
    @SuppressWarnings("unchecked")
    private Mapper<TestEntity> registerTestEntityMeta(Object idValue) {
        Mapper<TestEntity> mapper = mock(Mapper.class);
        when(mapper.getId(any(TestEntity.class))).thenReturn(idValue);

        FieldMeta<Long> idField = FieldMeta.<Long>builder()
            .name("id").column("id").type(Long.class).id(true)
            .build();

        EntityMeta<TestEntity> meta = EntityMeta.<TestEntity>builder()
            .type(TestEntity.class)
            .tableName("test_entities")
            .fields(List.of(idField))
            .idField(idField)
            .mapper(mapper)
            .build();

        EntityMetaRegistry.registerManual(meta);
        return mapper;
    }

    /** Concrete CRTP subtype under test. */
    static final class TestEntity extends Model<TestEntity> {
    }
}
