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
import com.holo.framework.horm.meta.query.Conditions;
import com.holo.framework.horm.meta.query.LongField;
import com.holo.framework.horm.meta.query.StringField;
import com.holo.framework.horm.meta.query.TypedField;
import com.holo.framework.horm.meta.query.expr.Aggregates;
import com.holo.framework.horm.meta.query.expr.Expr;
import com.holo.framework.horm.meta.query.expr.Functions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class GroupByHavingTest {

    private static final LongField<TestEntity> ID = LongField.of(TestEntity.class, "id", "id");
    private static final StringField<TestEntity> EMAIL = StringField.of(TestEntity.class, "email", "email");
    private static final LongField<OtherEntity> OTHER_ID = LongField.of(OtherEntity.class, "id", "id");

    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    private Mapper<TestEntity> mapper;
    private QueryImpl<TestEntity> query;

    @BeforeEach
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

        // OtherEntity for cross-entity rejection tests
        Mapper<OtherEntity> otherMapper = mock(Mapper.class);
        FieldMeta<Long> otherIdField = FieldMeta.<Long>builder()
            .name("id").column("id").type(Long.class).id(true).build();
        EntityMeta<OtherEntity> otherMeta = EntityMeta.<OtherEntity>builder()
            .type(OtherEntity.class)
            .tableName("other_entities")
            .fields(List.of(otherIdField))
            .idField(otherIdField)
            .mapper(otherMapper)
            .build();
        EntityMetaRegistry.registerManual(otherMeta);

        query = new QueryImpl<>(TestEntity.class, new HormContext(conn));
    }

    @AfterEach
    void tearDown() {
        EntityMetaRegistry.reload();
    }

    @Test
    void groupByTypedFieldRendersGroupBy() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.groupBy(ID, EMAIL).selectExpr(ID).listRows();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT id FROM test_entities GROUP BY id, email");
    }

    @Test
    void groupByExprRendersGroupBy() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        // Functions.upper returns ComparableExpr which extends Expr
        var yearExpr = Functions.upper(EMAIL);
        query.groupBy(yearExpr).selectExpr(ID).listRows();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT id FROM test_entities GROUP BY UPPER(email)");
    }

    @Test
    void havingRendersHavingClause() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        // Simulate HAVING COUNT(*) > 5 using Conditions.rawWithLeading,
        // which is what ComparableExpr.gt would produce if AggExpr extended ComparableExpr.
        Condition havingCond = Conditions.rawWithLeading("COUNT(*) > ?", List.of(), 5L);
        query.groupBy(EMAIL).having(havingCond).selectExpr(EMAIL, Aggregates.count()).listRows();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT email, COUNT(*) FROM test_entities GROUP BY email HAVING (COUNT(*) > ?)");
        verify(ps).setObject(1, 5L);
    }

    @Test
    void groupByHavingTogetherRendersBoth() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Condition havingCond = Conditions.rawWithLeading("COUNT(*) > ?", List.of(), 10L);
        query.groupBy(EMAIL).having(havingCond).selectExpr(EMAIL, Aggregates.count()).listRows();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT email, COUNT(*) FROM test_entities GROUP BY email HAVING (COUNT(*) > ?)");
        verify(ps).setObject(1, 10L);
    }

    @Test
    void selectExprReturnsProjectionQuery() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        ProjectionQuery pq = query.selectExpr(ID);
        assertThat(pq).isInstanceOf(ProjectionQuery.class);
        pq.listRows();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT id FROM test_entities");
    }

    @Test
    void selectExprWithAliasRendersAs() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        var countAlias = Aggregates.alias(Aggregates.count(), "cnt");
        query.selectExpr(EMAIL, countAlias).listRows();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT email, COUNT(*) AS cnt FROM test_entities");
    }

    @Test
    void groupByWithCrossEntityFieldThrows() {
        // Raw TypedField from OtherEntity simulates runtime-only cross-entity leakage
        TypedField raw = OTHER_ID;
        assertThatThrownBy(() -> query.groupBy(raw))
            .isInstanceOf(HormException.class)
            .hasMessageContaining("GROUP BY field 'id'")
            .hasMessageContaining("does not belong to entity")
            .hasMessageContaining(TestEntity.class.getName());
    }

    @Test
    void selectExprEmptyThrows() {
        assertThatThrownBy(() -> query.selectExpr())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("selectExpr requires at least one projection");
    }

    @Test
    void projectionQueryGroupByAfterSelectExpr() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        query.selectExpr(EMAIL, Aggregates.count()).groupBy(EMAIL).listRows();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT email, COUNT(*) FROM test_entities GROUP BY email");
    }

    @Test
    void projectionQueryHavingAfterSelectExpr() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Condition havingCond = Conditions.rawWithLeading("COUNT(*) > ?", List.of(), 3L);
        query.selectExpr(EMAIL, Aggregates.count()).having(havingCond).listRows();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .isEqualTo("SELECT email, COUNT(*) FROM test_entities HAVING (COUNT(*) > ?)");
        verify(ps).setObject(1, 3L);
    }

    static final class TestEntity extends Model<TestEntity> {}
    static final class OtherEntity extends Model<OtherEntity> {}
}
