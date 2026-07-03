package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.HormException;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.Row;
import com.holo.framework.horm.meta.query.Condition;
import com.holo.framework.horm.meta.query.LongField;
import com.holo.framework.horm.meta.query.StringField;
import com.holo.framework.horm.meta.query.TypedField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link QueryImpl} using Mockito mocks — no H2.
 * Mirrors the {@link com.holo.framework.horm.core.JdbcRepositoryTest} pattern:
 * hand-built {@link EntityMeta} + mocked {@link Mapper} registered manually,
 * assert SQL fragments and parameter binding order.
 */
class QueryImplTest {

    private static final LongField<TestEntity> ID = LongField.of(TestEntity.class, "id", "id");
    private static final StringField<TestEntity> EMAIL = StringField.of(TestEntity.class, "email", "email");

    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    @SuppressWarnings("unchecked")
    private Mapper<TestEntity> mapper;
    private QueryImpl<TestEntity> query;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);

        mapper = mock(Mapper.class);
        FieldMeta<Long> idField = FieldMeta.<Long>builder()
            .name("id").column("id").type(Long.class).id(true).build();
        FieldMeta<String> emailField = FieldMeta.<String>builder()
            .name("email").column("email").type(String.class).build();
        EntityMeta<TestEntity> meta = EntityMeta.<TestEntity>builder()
            .type(TestEntity.class)
            .tableName("test_entities")
            .fields(List.of(idField, emailField))
            .idField(idField)
            .mapper(mapper)
            .build();
        EntityMetaRegistry.registerManual(meta);

        query = new QueryImpl<>(TestEntity.class, new HormContext(conn));
    }

    @AfterEach
    void tearDown() {
        // reload() is public (clear() is package-private); it clears the registry
        // and re-scans the classpath, which drops the manually-registered test meta.
        EntityMetaRegistry.reload();
    }

    @Test
    void listWithWhereOrderByLimitOffsetRendersFullSql() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query
            .where(ID.eq(1L))
            .orderBy(ID, Order.DESC)
            .limit(10L)
            .offset(20L)
            .list();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT * FROM test_entities WHERE (id = ?) ORDER BY id DESC LIMIT ? OFFSET ?");

        verify(ps).setObject(1, 1L);
        verify(ps).setObject(2, 10L);
        verify(ps).setObject(3, 20L);
    }

    @Test
    void listWithoutClausesRendersSelectAll() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.list();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT * FROM test_entities");
    }

    @Test
    void listMapsAllRows() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true).thenReturn(true).thenReturn(false);
        TestEntity first = new TestEntity();
        TestEntity second = new TestEntity();
        when(mapper.map(any(Row.class))).thenReturn(first).thenReturn(second);

        List<TestEntity> result = query.list();

        assertThat(result).containsExactly(first, second);
    }

    @Test
    void whereMultipleConditionsImplicitAnd() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.where(ID.eq(1L), EMAIL.eq("foo")).list();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT * FROM test_entities WHERE (id = ?) AND (email = ?)");
    }

    @Test
    void andChainsAdditionalConditions() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.where(ID.eq(1L)).and(EMAIL.eq("foo")).list();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT * FROM test_entities WHERE (id = ?) AND (email = ?)");
    }

    @Test
    void orWrapsInParensAndAndsIntoPredicate() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.where(ID.eq(1L)).or(EMAIL.eq("a"), EMAIL.eq("b")).list();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT * FROM test_entities WHERE (id = ?) AND ((email = ?) OR (email = ?))");
    }

    @Test
    void orSingleConditionAppendsDirectly() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.where(ID.eq(1L)).or(EMAIL.eq("a")).list();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT * FROM test_entities WHERE (id = ?) AND (email = ?)");
    }

    @Test
    void findFirstReturnsPresent() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        TestEntity expected = new TestEntity();
        when(mapper.map(any(Row.class))).thenReturn(expected);

        Optional<TestEntity> result = query.findFirst();

        assertThat(result).isPresent().contains(expected);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT * FROM test_entities LIMIT ?");
        verify(ps).setObject(1, 1L);
    }

    @Test
    void findFirstReturnsEmpty() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Optional<TestEntity> result = query.findFirst();

        assertThat(result).isEmpty();
    }

    @Test
    void findFirstRespectsWhereAndOffset() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.where(ID.gt(0L)).offset(5L).findFirst();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT * FROM test_entities WHERE (id > ?) LIMIT ? OFFSET ?");
        verify(ps).setObject(1, 0L);
        verify(ps).setObject(2, 1L);
        verify(ps).setObject(3, 5L);
    }

    @Test
    void countRendersCountSql() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(42L);

        long result = query.where(ID.gt(0L)).count();

        assertThat(result).isEqualTo(42L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT COUNT(*) FROM test_entities WHERE (id > ?)");
        verify(ps).setObject(1, 0L);
    }

    @Test
    void countIgnoresOrderByAndLimit() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(1L);

        query.orderBy(ID, Order.DESC).limit(10L).count();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT COUNT(*) FROM test_entities");
    }

    @Test
    void existsReturnsTrueWhenRowMatches() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);

        boolean result = query.where(ID.eq(1L)).exists();

        assertThat(result).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT 1 FROM test_entities WHERE (id = ?) LIMIT 1");
        verify(ps).setObject(1, 1L);
    }

    @Test
    void existsReturnsFalseWhenNoRow() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertThat(query.exists()).isFalse();
    }

    @Test
    void listWrapsSQLExceptionInHormException() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("boom"));

        assertThatThrownBy(() -> query.list())
            .isInstanceOf(HormException.class)
            .hasMessageContaining("Failed to list")
            .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void multipleOrderByClausesRenderCommaSeparated() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.orderBy(ID, Order.DESC).orderBy(EMAIL, Order.ASC).list();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT * FROM test_entities ORDER BY id DESC, email ASC");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void orderByWithForeignFieldThrowsHormException() {
        // Compile-time generics would reject a TypedField<OtherEntity> here; we
        // intentionally use a raw type to simulate the runtime-only check that
        // guards against cross-entity column leakage (e.g. via reflection or
        // raw-type callers).
        TypedField raw = LongField.of(OtherEntity.class, "id", "id");
        assertThatThrownBy(() -> query.orderBy(raw, Order.ASC))
            .isInstanceOf(HormException.class)
            .hasMessageContaining("ORDER BY field 'id'")
            .hasMessageContaining("does not belong to entity")
            .hasMessageContaining(TestEntity.class.getName());
    }

    @Test
    void limitNegativeThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> query.limit(-1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit must be >= 0");
    }

    @Test
    void offsetNegativeThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> query.offset(-5L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("offset must be >= 0");
    }

    @Test
    void whereNullOrEmptyIsNoOp() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.where((Condition) null).where().list();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT * FROM test_entities");
    }

    @Test
    void findFirstWrapsSQLExceptionInHormException() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("boom"));

        assertThatThrownBy(() -> query.findFirst())
            .isInstanceOf(HormException.class)
            .hasMessageContaining("Failed to findFirst")
            .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void countWrapsSQLExceptionInHormException() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("boom"));

        assertThatThrownBy(() -> query.count())
            .isInstanceOf(HormException.class)
            .hasMessageContaining("Failed to count")
            .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void existsWrapsSQLExceptionInHormException() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("boom"));

        assertThatThrownBy(() -> query.exists())
            .isInstanceOf(HormException.class)
            .hasMessageContaining("Failed to check existence")
            .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void limitZeroProducesLimitZeroSql() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.limit(0L).list();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT * FROM test_entities LIMIT ?");
        verify(ps).setObject(1, 0L);
    }

    @Test
    void offsetWithoutLimitRendersOffsetOnly() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.offset(5L).list();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT * FROM test_entities OFFSET ?");
        verify(ps).setObject(1, 5L);
    }

    /** Concrete CRTP subtype used as a registry key for these tests. */
    static final class TestEntity extends Model<TestEntity> {
    }

    /** Foreign entity type used to verify ORDER BY cross-entity rejection. */
    static final class OtherEntity extends Model<OtherEntity> {
    }
}
