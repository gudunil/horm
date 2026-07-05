package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Model}.
 *
 * <p>M1-7 expands the surface beyond {@link Model#isPersisted()}: the
 * Active Record methods ({@code save}/{@code delete}/{@code reload} and the
 * static {@code find}/{@code all}/{@code count} helpers) now route through
 * a real {@link JdbcRepository}, so each test installs a mocked
 * {@link HormContext} (with a mock {@link Connection}) and verifies that
 * the corresponding JDBC call is made.
 *
 * <p>{@link EntityMeta} is {@code final} and cannot be mocked; we build a
 * real instance via {@link EntityMeta.Builder} with a mocked {@link Mapper}
 * to control {@code getId()} / {@code toRow()} return values.
 *
 * <p><b>Coverage note:</b> the {@code isPersistedThrowsWhenEntityHasNoIdField}
 * assertion uses {@code assertThatThrownBy} because that path runs entirely
 * inside {@link Model#idValue()} (no JDBC indirection); JaCoCo attributes
 * it correctly. The repository-delegation tests invoke {@link Model}
 * methods directly from the test method's own stack frame.
 */
class ModelTest {

    @AfterEach
    void clearRegistry() {
        EntityMetaRegistry.clear();
        HormContext.install(null);
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

    /**
     * M1-7: {@code Model.save()} delegates to {@link JdbcRepository#save},
     * which (for a non-null id) issues an UPDATE via PreparedStatement.
     */
    @Test
    void saveDelegatesToRepository() throws Exception {
        Mapper<TestEntity> mapper = registerTestEntityMeta(1L);
        Connection conn = installMockContext();
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        when(mapper.toRow(any(TestEntity.class))).thenReturn(Row.create("test_entities"));

        TestEntity entity = new TestEntity();
        entity.save();

        verify(ps).executeUpdate();
    }

    /** M1-7: {@code Model.delete()} issues a DELETE via PreparedStatement. */
    @Test
    void deleteDelegatesToRepository() throws Exception {
        registerTestEntityMeta(1L);
        Connection conn = installMockContext();
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);

        TestEntity entity = new TestEntity();
        entity.delete();

        verify(ps).executeUpdate();
    }

    /**
     * M1-7: {@code Model.reload()} routes through
     * {@link JdbcRepository#find}, which issues a SELECT ... WHERE id = ?
     * and returns {@code null} when no row matches.
     */
    @Test
    void reloadDelegatesToRepository() throws Exception {
        registerTestEntityMeta(1L);
        Connection conn = installMockContext();
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        TestEntity entity = new TestEntity();
        assertThat(entity.reload()).isNull();
        verify(ps).executeQuery();
    }

    /** M1-7: {@code Model.find(Class, Object)} issues a SELECT by id. */
    @Test
    void staticFindDelegatesToRepository() throws Exception {
        registerTestEntityMeta(1L);
        Connection conn = installMockContext();
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertThat(Model.find(TestEntity.class, 1L)).isNull();
        verify(ps).executeQuery();
    }

    /** M1-7: {@code Model.all(Class)} issues a SELECT * and returns a list. */
    @Test
    void staticAllDelegatesToRepository() throws Exception {
        registerTestEntityMeta(1L);
        Connection conn = installMockContext();
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertThat(Model.all(TestEntity.class)).isEmpty();
        verify(ps).executeQuery();
    }

    /** M1-7: {@code Model.count(Class)} issues a SELECT COUNT(*). */
    @Test
    void staticCountDelegatesToRepository() throws Exception {
        registerTestEntityMeta(1L);
        Connection conn = installMockContext();
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertThat(Model.count(TestEntity.class)).isEqualTo(0L);
        verify(ps).executeQuery();
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
        FieldMeta<String> nameField = FieldMeta.<String>builder()
            .name("name").column("name").type(String.class)
            .build();

        EntityMeta<TestEntity> meta = EntityMeta.<TestEntity>builder()
            .type(TestEntity.class)
            .tableName("test_entities")
            .fields(List.of(idField, nameField))
            .idField(idField)
            .mapper(mapper)
            .build();

        EntityMetaRegistry.registerManual(meta);
        return mapper;
    }

    /** Installs a {@link HormContext} backed by a mock {@link Connection}. */
    private Connection installMockContext() {
        Connection conn = mock(Connection.class);
        Horm.install(new HormContext(conn));
        return conn;
    }

    /** Concrete CRTP subtype under test. */
    static final class TestEntity extends Model<TestEntity> {
    }
}
