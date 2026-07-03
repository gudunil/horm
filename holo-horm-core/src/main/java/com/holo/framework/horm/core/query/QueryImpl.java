package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.HormException;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.Row;
import com.holo.framework.horm.meta.query.Condition;
import com.holo.framework.horm.meta.query.TypedField;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Default {@link Query} implementation. SQL is composed from the
 * {@link EntityMeta} registered for {@code T}: table name and field columns
 * come from compile-time-generated metadata, while every user-supplied value
 * (condition bindings, limit, offset) flows through {@code ?} placeholders.
 *
 * <p>Column identifiers in ORDER BY are validated against the query's entity
 * type — only {@link TypedField}s whose {@link TypedField#entityType()}
 * matches {@code T} are accepted, which prevents cross-entity column leakage
 * without requiring a runtime column allowlist.
 *
 * <p>All {@link SQLException}s are wrapped in {@link HormException}, matching
 * the {@link com.holo.framework.horm.core.JdbcRepository} convention.
 */
public final class QueryImpl<T extends Model<T>> implements Query<T> {

    private final Class<T> entityType;
    private final HormContext ctx;
    private final EntityMeta<T> meta;
    private final Mapper<T> mapper;

    private final List<Condition> whereConditions = new ArrayList<>();
    private final List<OrderBy> orderByClauses = new ArrayList<>();
    private Long limit;
    private Long offset;

    public QueryImpl(Class<T> entityType, HormContext ctx) {
        this.entityType = entityType;
        this.ctx = ctx;
        this.meta = EntityMetaRegistry.lookup(entityType);
        this.mapper = meta.mapper();
    }

    @Override
    public Query<T> where(Condition... conditions) {
        appendConditions(conditions);
        return this;
    }

    @Override
    public Query<T> and(Condition... conditions) {
        appendConditions(conditions);
        return this;
    }

    @Override
    public Query<T> or(Condition... conditions) {
        if (conditions == null || conditions.length == 0) {
            return this;
        }
        if (conditions.length == 1) {
            whereConditions.add(conditions[0]);
        } else {
            whereConditions.add(Condition.or(conditions));
        }
        return this;
    }

    @Override
    public Query<T> orderBy(TypedField<T, ?> field, Order direction) {
        if (!field.entityType().equals(entityType)) {
            throw new HormException(
                "ORDER BY field '" + field.name()
                    + "' does not belong to entity '" + entityType.getName() + "'");
        }
        orderByClauses.add(new OrderBy(field.column(), direction));
        return this;
    }

    @Override
    public Query<T> limit(long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0, got " + limit);
        }
        this.limit = limit;
        return this;
    }

    @Override
    public Query<T> offset(long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0, got " + offset);
        }
        this.offset = offset;
        return this;
    }

    @Override
    public List<T> list() {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qualifiedTable());
        List<Object> bindings = new ArrayList<>();
        appendWhere(sql, bindings);
        appendOrderBy(sql);
        appendLimitOffset(sql, bindings);

        List<T> result = new ArrayList<>();
        try (PreparedStatement ps = ctx.connection().prepareStatement(sql.toString())) {
            bind(ps, bindings);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapper.map(toRow(rs)));
                }
            }
        } catch (SQLException e) {
            throw new HormException("Failed to list " + entityType.getName(), e);
        }
        return result;
    }

    @Override
    public Optional<T> findFirst() {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qualifiedTable());
        List<Object> bindings = new ArrayList<>();
        appendWhere(sql, bindings);
        appendOrderBy(sql);
        sql.append(" LIMIT ?");
        bindings.add(1L);
        if (offset != null) {
            sql.append(" OFFSET ?");
            bindings.add(offset);
        }

        try (PreparedStatement ps = ctx.connection().prepareStatement(sql.toString())) {
            bind(ps, bindings);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapper.map(toRow(rs)));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new HormException("Failed to findFirst " + entityType.getName(), e);
        }
    }

    @Override
    public long count() {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(qualifiedTable());
        List<Object> bindings = new ArrayList<>();
        appendWhere(sql, bindings);

        try (PreparedStatement ps = ctx.connection().prepareStatement(sql.toString())) {
            bind(ps, bindings);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            }
        } catch (SQLException e) {
            throw new HormException("Failed to count " + entityType.getName(), e);
        }
    }

    @Override
    public boolean exists() {
        StringBuilder sql = new StringBuilder("SELECT 1 FROM ").append(qualifiedTable());
        List<Object> bindings = new ArrayList<>();
        appendWhere(sql, bindings);
        sql.append(" LIMIT 1");

        try (PreparedStatement ps = ctx.connection().prepareStatement(sql.toString())) {
            bind(ps, bindings);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new HormException("Failed to check existence of " + entityType.getName(), e);
        }
    }

    private void appendConditions(Condition... conditions) {
        if (conditions == null) {
            return;
        }
        for (Condition c : conditions) {
            if (c != null) {
                whereConditions.add(c);
            }
        }
    }

    private void appendWhere(StringBuilder sql, List<Object> bindings) {
        if (whereConditions.isEmpty()) {
            return;
        }
        sql.append(" WHERE ");
        for (int i = 0; i < whereConditions.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            Condition c = whereConditions.get(i);
            sql.append("(").append(c.sqlFragment()).append(")");
            bindings.addAll(c.bindings());
        }
    }

    private void appendOrderBy(StringBuilder sql) {
        if (orderByClauses.isEmpty()) {
            return;
        }
        sql.append(" ORDER BY ");
        for (int i = 0; i < orderByClauses.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            OrderBy ob = orderByClauses.get(i);
            sql.append(ob.column).append(" ").append(ob.direction.name());
        }
    }

    private void appendLimitOffset(StringBuilder sql, List<Object> bindings) {
        if (limit != null) {
            sql.append(" LIMIT ?");
            bindings.add(limit);
        }
        if (offset != null) {
            sql.append(" OFFSET ?");
            bindings.add(offset);
        }
    }

    private void bind(PreparedStatement ps, List<Object> bindings) throws SQLException {
        int i = 1;
        for (Object b : bindings) {
            ps.setObject(i++, b);
        }
    }

    private Row toRow(ResultSet rs) throws SQLException {
        Row row = Row.create(meta.tableName());
        for (FieldMeta<?> fd : meta.fields()) {
            row.set(fd.column(), rs.getObject(fd.column()));
        }
        return row;
    }

    private String qualifiedTable() {
        String schema = meta.schema();
        return (schema == null || schema.isEmpty())
            ? meta.tableName()
            : schema + "." + meta.tableName();
    }

    private static final class OrderBy {
        final String column;
        final Order direction;

        OrderBy(String column, Order direction) {
            this.column = column;
            this.direction = direction;
        }
    }
}
