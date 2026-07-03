package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link JdbcRepository} using Mockito mocks of
 * {@link Connection} / {@link PreparedStatement} / {@link ResultSet} —
 * no H2 or real JDBC driver required.
 *
 * <p>Each test registers a hand-built {@link EntityMeta} (with a mocked
 * {@link Mapper}) for {@link TestEntity} via
 * {@link EntityMetaRegistry#registerManual}, then constructs a
 * {@link JdbcRepository} bound to a mock {@link HormContext}.
 */
class JdbcRepositoryTest {

    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    @SuppressWarnings("unchecked")
    private Mapper<TestEntity> mapper;
    private JdbcRepository<TestEntity> repo;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);

        mapper = mock(Mapper.class);
        FieldMeta<Long> idField = FieldMeta.<Long>builder()
            .name("id").column("id").type(Long.class).id(true)
            .build();
        FieldMeta<String> emailField = FieldMeta.<String>builder()
            .name("email").column("email").type(String.class)
            .build();
        EntityMeta<TestEntity> meta = EntityMeta.<TestEntity>builder()
            .type(TestEntity.class)
            .tableName("test_entities")
            .fields(List.of(idField, emailField))
            .idField(idField)
            .mapper(mapper)
            .build();
        EntityMetaRegistry.registerManual(meta);

        repo = new JdbcRepository<>(TestEntity.class, new HormContext(conn));
    }

    @AfterEach
    void tearDown() {
        EntityMetaRegistry.clear();
    }

    @Test
    void findReturnsMappedEntity() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        TestEntity expected = new TestEntity();
        when(mapper.map(any(Row.class))).thenReturn(expected);

        TestEntity result = repo.find(1L);

        assertThat(result).isSameAs(expected);
        verify(ps).setObject(1, 1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT * FROM test_entities WHERE id = ?");
    }

    @Test
    void findReturnsNullWhenNotFound() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertThat(repo.find(999L)).isNull();
        verify(mapper, never()).map(any(Row.class));
    }

    @Test
    void allCollectsAllRows() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true).thenReturn(true).thenReturn(false);
        TestEntity first = new TestEntity();
        TestEntity second = new TestEntity();
        when(mapper.map(any(Row.class))).thenReturn(first).thenReturn(second);

        List<TestEntity> result = repo.all();

        assertThat(result).hasSize(2).containsExactly(first, second);
    }

    @Test
    void saveInsertsWhenIdIsNullAndBackfillsGeneratedId() throws Exception {
        TestEntity entity = new TestEntity();
        when(mapper.getId(entity)).thenReturn(null);
        when(mapper.toRow(entity)).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
            .thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        ResultSet genKeys = mock(ResultSet.class);
        when(ps.getGeneratedKeys()).thenReturn(genKeys);
        when(genKeys.next()).thenReturn(true);
        when(genKeys.getLong(1)).thenReturn(42L);

        TestEntity returned = repo.save(entity);

        assertThat(returned).isSameAs(entity);
        verify(ps).executeUpdate();
        verify(mapper).setId(entity, 42L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture(), eq(Statement.RETURN_GENERATED_KEYS));
        assertThat(sql.getValue())
            .isEqualTo("INSERT INTO test_entities (email) VALUES (?)");
    }

    @Test
    void saveUpdatesWhenIdIsNonNull() throws Exception {
        TestEntity entity = new TestEntity();
        when(mapper.getId(entity)).thenReturn(1L);
        when(mapper.toRow(entity)).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);

        TestEntity returned = repo.save(entity);

        assertThat(returned).isSameAs(entity);
        verify(ps).executeUpdate();
        verify(ps, never()).getGeneratedKeys();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("UPDATE test_entities SET email = ? WHERE id = ?");
    }

    @Test
    void deleteExecutesDeleteSql() throws Exception {
        TestEntity entity = new TestEntity();
        when(mapper.getId(entity)).thenReturn(1L);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);

        repo.delete(entity);

        verify(ps).executeUpdate();
        verify(ps).setObject(1, 1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("DELETE FROM test_entities WHERE id = ?");
    }

    /** Concrete CRTP subtype used only as a registry key for these tests. */
    static final class TestEntity extends Model<TestEntity> {
    }
}
