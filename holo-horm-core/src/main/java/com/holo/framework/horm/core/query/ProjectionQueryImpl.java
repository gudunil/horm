package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.JdbcOperations;
import com.holo.framework.horm.core.MetaSupport;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.Row;
import com.holo.framework.horm.meta.query.Condition;
import com.holo.framework.horm.meta.query.TypedField;
import com.holo.framework.horm.meta.query.expr.Expr;
import com.holo.framework.horm.core.dialect.Dialect;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Default {@link ProjectionQuery} implementation. Snapshot-transferred from
 * {@link QueryImpl} state at the time {@code selectExpr} is called.
 */
final class ProjectionQueryImpl<T extends Model<T>> implements ProjectionQuery {

    private final HormContext ctx;
    private final EntityMeta<T> meta;
    private final String dataSourceName;
    private final Class<T> entityType;

    private final List<Condition> whereConditions;
    private final List<QueryImpl.OrderBy> orderByClauses;
    private final Long limit;
    private final Long offset;
    private final List<Expr<?>> groupByExprs;
    private final List<Condition> havingConditions;
    private final List<Expr<?>> projections;

    ProjectionQueryImpl(
            HormContext ctx,
            EntityMeta<T> meta,
            String dataSourceName,
            Class<T> entityType,
            List<Condition> whereConditions,
            List<QueryImpl.OrderBy> orderByClauses,
            Long limit, Long offset,
            List<Expr<?>> groupByExprs,
            List<Condition> havingConditions,
            List<Expr<?>> projections) {
        this.ctx = ctx;
        this.meta = meta;
        this.dataSourceName = dataSourceName;
        this.entityType = entityType;
        this.whereConditions = whereConditions;
        this.orderByClauses = orderByClauses;
        this.limit = limit;
        this.offset = offset;
        this.groupByExprs = groupByExprs;
        this.havingConditions = havingConditions;
        this.projections = projections;
    }

    @Override
    public ProjectionQuery groupBy(Expr<?>... expressions) {
        if (expressions != null) {
            for (Expr<?> e : expressions) {
                if (e != null) groupByExprs.add(e);
            }
        }
        return this;
    }

    @Override
    public ProjectionQuery having(Condition... conditions) {
        if (conditions != null) {
            for (Condition c : conditions) {
                if (c != null) havingConditions.add(c);
            }
        }
        return this;
    }

    @Override
    public List<Row> listRows() {
        StringBuilder sql = new StringBuilder("SELECT ");
        List<Object> bindings = new ArrayList<>();
        appendProjections(sql, bindings);
        sql.append(" FROM ").append(MetaSupport.qualifiedTable(meta));
        appendWhere(sql, bindings);
        appendGroupBy(sql, bindings);
        appendHaving(sql, bindings);
        appendOrderBy(sql);
        Dialect dialect = ctx.dialect(dataSourceName);
        if (limit != null || offset != null) {
            dialect.paginate(sql, bindings, offset != null ? offset : 0, limit != null ? limit : Long.MAX_VALUE);
        }
        return JdbcOperations.query(ctx, dataSourceName, sql.toString(), bindings,
            rs -> {
                List<Row> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(rowFromResultSet(rs));
                }
                return rows;
            },
            "projection listRows " + entityType.getName());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> List<S> listScalar(Class<S> scalarType) {
        if (projections.size() != 1) {
            throw new IllegalArgumentException(
                "listScalar requires exactly one projection, got " + projections.size());
        }
        List<Row> rows = listRows();
        String col = resolveColumn(projections.get(0));
        List<S> result = new ArrayList<>(rows.size());
        for (Row row : rows) {
            result.add((S) com.holo.framework.horm.meta.ExprAccessor.get(row, col, scalarType));
        }
        return result;
    }

    @Override
    public Optional<Row> firstRow() {
        StringBuilder sql = new StringBuilder("SELECT ");
        List<Object> bindings = new ArrayList<>();
        appendProjections(sql, bindings);
        sql.append(" FROM ").append(MetaSupport.qualifiedTable(meta));
        appendWhere(sql, bindings);
        appendGroupBy(sql, bindings);
        appendHaving(sql, bindings);
        appendOrderBy(sql);
        Dialect dialect = ctx.dialect(dataSourceName);
        long off = offset != null ? offset : 0L;
        dialect.paginate(sql, bindings, off, 1L);
        return JdbcOperations.query(ctx, dataSourceName, sql.toString(), bindings,
            rs -> rs.next() ? Optional.of(rowFromResultSet(rs)) : Optional.empty(),
            "projection firstRow " + entityType.getName());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> Optional<S> firstScalar(Class<S> scalarType) {
        if (projections.size() != 1) {
            throw new IllegalArgumentException(
                "firstScalar requires exactly one projection, got " + projections.size());
        }
        return firstRow().map(row ->
            (S) com.holo.framework.horm.meta.ExprAccessor.get(row, resolveColumn(projections.get(0)), scalarType));
    }

    private void appendProjections(StringBuilder sql, List<Object> bindings) {
        Dialect dialect = ctx.dialect(dataSourceName);
        for (int i = 0; i < projections.size(); i++) {
            if (i > 0) sql.append(", ");
            Expr<?> p = projections.get(i);
            // 对于 FuncExpr，调用 Dialect 进行方言特定的渲染
            if (p instanceof com.holo.framework.horm.meta.query.expr.FuncExpr<?> funcExpr) {
                List<String> argSqlFragments = new ArrayList<>();
                for (Expr<?> arg : funcExpr.arguments()) {
                    argSqlFragments.add(arg.sqlFragment());
                }
                sql.append(dialect.functionSql(funcExpr.functionType(), argSqlFragments));
            } else {
                sql.append(p.sqlFragment());
            }
            bindings.addAll(p.bindings());
            if (p.alias() != null) {
                char q = dialect.identifierQuoteChar();
                sql.append(" AS ").append(q).append(p.alias()).append(q);
            }
        }
    }

    private void appendWhere(StringBuilder sql, List<Object> bindings) {
        if (whereConditions.isEmpty()) return;
        sql.append(" WHERE ");
        for (int i = 0; i < whereConditions.size(); i++) {
            if (i > 0) sql.append(" AND ");
            Condition c = whereConditions.get(i);
            sql.append("(").append(c.sqlFragment()).append(")");
            bindings.addAll(c.bindings());
        }
    }

    private void appendGroupBy(StringBuilder sql, List<Object> bindings) {
        if (groupByExprs.isEmpty()) return;
        Dialect dialect = ctx.dialect(dataSourceName);
        sql.append(" GROUP BY ");
        for (int i = 0; i < groupByExprs.size(); i++) {
            if (i > 0) sql.append(", ");
            Expr<?> e = groupByExprs.get(i);
            // 对于 FuncExpr，调用 Dialect 进行方言特定的渲染
            if (e instanceof com.holo.framework.horm.meta.query.expr.FuncExpr<?> funcExpr) {
                List<String> argSqlFragments = new ArrayList<>();
                for (Expr<?> arg : funcExpr.arguments()) {
                    argSqlFragments.add(arg.sqlFragment());
                }
                sql.append(dialect.functionSql(funcExpr.functionType(), argSqlFragments));
            } else {
                sql.append(e.sqlFragment());
            }
            bindings.addAll(e.bindings());
        }
    }

    private void appendHaving(StringBuilder sql, List<Object> bindings) {
        if (havingConditions.isEmpty()) return;
        sql.append(" HAVING ");
        for (int i = 0; i < havingConditions.size(); i++) {
            if (i > 0) sql.append(" AND ");
            Condition c = havingConditions.get(i);
            sql.append("(").append(c.sqlFragment()).append(")");
            bindings.addAll(c.bindings());
        }
    }

    private void appendOrderBy(StringBuilder sql) {
        if (orderByClauses.isEmpty()) return;
        sql.append(" ORDER BY ");
        for (int i = 0; i < orderByClauses.size(); i++) {
            if (i > 0) sql.append(", ");
            QueryImpl.OrderBy ob = orderByClauses.get(i);
            if (ob.alias() != null) sql.append(ob.alias()).append(".");
            sql.append(ob.column()).append(" ").append(ob.direction().name());
        }
    }

    private Row rowFromResultSet(ResultSet rs) throws SQLException {
        Row row = Row.create(meta.tableName());
        java.sql.ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String label = md.getColumnLabel(i);
            if (label == null) label = md.getColumnName(i);
            // 转小写以匹配 JDBC 列标签行为（H2/MySQL 返回大写）
            row.set(label.toLowerCase(), rs.getObject(i));
        }
        return row;
    }

    private String resolveColumn(Expr<?> expr) {
        if (expr.alias() != null) return expr.alias();
        if (expr instanceof TypedField<?, ?> f) return f.column();
        // 对于函数表达式，将 sqlFragment 转为小写以匹配 Row 中的列名（H2/MySQL 返回大写列标签）
        return expr.sqlFragment().toLowerCase();
    }
}
