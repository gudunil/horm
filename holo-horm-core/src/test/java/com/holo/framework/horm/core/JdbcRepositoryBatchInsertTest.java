package com.holo.framework.horm.core;

import com.holo.framework.horm.core.dialect.Dialect;
import com.holo.framework.horm.core.dialect.MySqlDialect;
import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.Row;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.meta.annotation.GenerationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link JdbcRepository#batchInsert(List)} (O3
 * optimization), verifying JDBC batch mechanics, generated-key backfill
 * ordering, and error wrapping.
 *
 * <p>Uses Mockito mocks of {@link Connection} /
 * {@link PreparedStatement} / {@link ResultSet} — no H2 or real JDBC
 * driver required. Pattern mirrors {@link JdbcRepositoryTest}.
 */
class JdbcRepositoryBatchInsertTest {

    private Connection conn;
    private PreparedStatement ps;
    private ResultSet genKeys;
    @SuppressWarnings("unchecked")
    private Mapper<TestEntity> mapper;
    private JdbcRepository<TestEntity> repo;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        genKeys = mock(ResultSet.class);

        mapper = mock(Mapper.class);
        FieldMeta<Long> idField = FieldMeta.<Long>builder()
            .name("id").column("id").type(Long.class).id(true)
            .generationStrategy(GenerationType.IDENTITY)
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
    void batchInsertEmptyListReturnsEmptyList() throws Exception {
        List<TestEntity> result = repo.batchInsert(List.of());

        assertThat(result).isEmpty();
        verify(conn, never()).prepareStatement(anyString(), any(Integer.class));
    }

    @Test
    void batchInsertNullReturnsEmptyList() throws Exception {
        List<TestEntity> result = repo.batchInsert(null);

        assertThat(result).isEmpty();
        verify(conn, never()).prepareStatement(anyString(), any(Integer.class));
    }

    @Test
    void batchInsertUsesAddBatchAndExecuteBatch() throws Exception {
        TestEntity e1 = new TestEntity();
        TestEntity e2 = new TestEntity();
        TestEntity e3 = new TestEntity();
        List<TestEntity> entities = List.of(e1, e2, e3);

        when(mapper.toRow(any(TestEntity.class))).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
            .thenReturn(ps);
        when(ps.executeBatch()).thenReturn(new int[]{1, 1, 1});
        when(ps.getGeneratedKeys()).thenReturn(genKeys);
        when(genKeys.next()).thenReturn(true, true, true, false);
        when(genKeys.getLong(1)).thenReturn(101L, 102L, 103L);

        repo.batchInsert(entities);

        verify(ps, times(3)).addBatch();
        verify(ps, times(1)).executeBatch();
    }

    @Test
    void batchInsertBackfillsGeneratedKeysInOrder() throws Exception {
        TestEntity e1 = new TestEntity();
        TestEntity e2 = new TestEntity();
        TestEntity e3 = new TestEntity();
        List<TestEntity> entities = List.of(e1, e2, e3);

        when(mapper.toRow(any(TestEntity.class))).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
            .thenReturn(ps);
        when(ps.executeBatch()).thenReturn(new int[]{1, 1, 1});
        when(ps.getGeneratedKeys()).thenReturn(genKeys);
        when(genKeys.next()).thenReturn(true, true, true, false);
        when(genKeys.getLong(1)).thenReturn(100L, 200L, 300L);

        repo.batchInsert(entities);

        verify(mapper).setId(e1, 100L);
        verify(mapper).setId(e2, 200L);
        verify(mapper).setId(e3, 300L);
    }

    @Test
    void batchInsertUsesPreGeneratedInsertSql() throws Exception {
        TestEntity e1 = new TestEntity();
        when(mapper.toRow(any(TestEntity.class))).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
            .thenReturn(ps);
        when(ps.executeBatch()).thenReturn(new int[]{1});
        when(ps.getGeneratedKeys()).thenReturn(genKeys);
        when(genKeys.next()).thenReturn(true, false);
        when(genKeys.getLong(1)).thenReturn(42L);

        repo.batchInsert(List.of(e1));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture(), eq(Statement.RETURN_GENERATED_KEYS));
        assertThat(sql.getValue())
            .isEqualTo("INSERT INTO test_entities (email) VALUES (?)");
    }

    @Test
    void batchInsertThrowsWhenBatchRowFails() throws Exception {
        TestEntity e1 = new TestEntity();
        when(mapper.toRow(any(TestEntity.class))).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
            .thenReturn(ps);
        when(ps.executeBatch()).thenReturn(new int[]{Statement.EXECUTE_FAILED});
        when(ps.getGeneratedKeys()).thenReturn(genKeys);

        assertThatThrownBy(() -> repo.batchInsert(List.of(e1)))
            .isInstanceOf(HormException.class)
            .hasMessageContaining("batch row failed");
    }

    @Test
    void batchInsertThrowsWhenGeneratedKeyCountMismatch() throws Exception {
        TestEntity e1 = new TestEntity();
        TestEntity e2 = new TestEntity();
        when(mapper.toRow(any(TestEntity.class))).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
            .thenReturn(ps);
        when(ps.executeBatch()).thenReturn(new int[]{1, 1});
        when(ps.getGeneratedKeys()).thenReturn(genKeys);
        when(genKeys.next()).thenReturn(true, false);
        when(genKeys.getLong(1)).thenReturn(42L);

        assertThatThrownBy(() -> repo.batchInsert(List.of(e1, e2)))
            .isInstanceOf(HormException.class)
            .hasMessageContaining("generated key count (1) does not match batch size (2)");
    }

    @Test
    void batchInsertWrapsSQLExceptionInHormException() throws Exception {
        TestEntity e1 = new TestEntity();
        when(mapper.toRow(any(TestEntity.class))).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
            .thenThrow(new SQLException("batch boom"));

        assertThatThrownBy(() -> repo.batchInsert(List.of(e1)))
            .isInstanceOf(HormException.class)
            .hasMessageContaining("Failed to batch insert")
            .hasMessageContaining(TestEntity.class.getName())
            .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void batchInsertThrowsWhenDialectDoesNotGuaranteeGeneratedKeyOrder() throws Exception {
        DataSourceRegistry registry = new DataSourceRegistry();
        registry.registerDefault(new SimpleDataSourceProvider(conn));
        Dialect unsafeDialect = new MySqlDialect() {
            @Override
            public boolean supportsBatchInsertGeneratedKeysInOrder() {
                return false;
            }

            @Override
            public String name() {
                return "unsafe";
            }
        };
        HormContext unsafeCtx = new HormContext(registry, null, Map.of(DataSourceRegistry.DEFAULT_NAME, unsafeDialect));
        JdbcRepository<TestEntity> unsafeRepo = new JdbcRepository<>(TestEntity.class, unsafeCtx);

        TestEntity e1 = new TestEntity();
        when(mapper.toRow(any(TestEntity.class))).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
            .thenReturn(ps);

        assertThatThrownBy(() -> unsafeRepo.batchInsert(List.of(e1)))
            .isInstanceOf(HormException.class)
            .hasMessageContaining("not supported by dialect unsafe");
    }

    /** CRTP subtype used only as a registry key for these tests. */
    static final class TestEntity extends Model<TestEntity> {
    }
}
