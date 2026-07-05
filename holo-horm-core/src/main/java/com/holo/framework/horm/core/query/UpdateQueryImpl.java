package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.HormException;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.TransactionManager;
import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.query.Condition;
import com.holo.framework.horm.meta.query.TypedField;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link UpdateQuery} implementation. Composes an UPDATE statement
 * from the entity's {@link EntityMeta} and the user-supplied SET entries
 * and WHERE conditions.
 */
public final class UpdateQueryImpl<T extends Model<T>> implements UpdateQuery<T> {

    private final Class<T> entityType;
    private final HormContext ctx;
    private final EntityMeta<T> meta;

    private final List<SetEntry> setEntries = new ArrayList<>();
    private final List<Condition> whereConditions = new ArrayList<>();

    private record SetEntry(String column, Object value) {}

    public UpdateQueryImpl(Class<T> entityType, HormContext ctx) {
        this.entityType = entityType;
        this.ctx = ctx;
        this.meta = EntityMetaRegistry.lookup(entityType);
    }

    @Override
    public UpdateQuery<T> set(TypedField<T, ?> field, Object value) {
        if (field == null) {
            throw new IllegalArgumentException("field must not be null");
        }
        setEntries.add(new SetEntry(field.column(), value));
        return this;
    }

    @Override
    public UpdateQuery<T> where(Condition... conditions) {
        appendConditions(conditions);
        return this;
    }

    @Override
    public UpdateQuery<T> and(Condition... conditions) {
        appendConditions(conditions);
        return this;
    }

    @Override
    public int execute() {
        if (setEntries.isEmpty()) {
            throw new HormException("UPDATE requires at least one SET clause");
        }

        StringBuilder sql = new StringBuilder("UPDATE ")
            .append(qualifiedTable())
            .append(" SET ");
        List<Object> bindings = new ArrayList<>();

        for (int i = 0; i < setEntries.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(setEntries.get(i).column()).append(" = ?");
            bindings.add(setEntries.get(i).value());
        }

        appendWhere(sql, bindings);

        Connection conn = TransactionManager.currentConnection(ctx);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, bindings);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new HormException("Failed to execute UPDATE on " + entityType.getName(), e);
        } finally {
            TransactionManager.releaseConnection(ctx, conn);
        }
    }

    private void appendConditions(Condition... conditions) {
        if (conditions == null) return;
        for (Condition c : conditions) {
            if (c != null) whereConditions.add(c);
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

    private void bind(PreparedStatement ps, List<Object> bindings) throws SQLException {
        for (int i = 0; i < bindings.size(); i++) {
            ps.setObject(i + 1, bindings.get(i));
        }
    }

    private String qualifiedTable() {
        String schema = meta.schema();
        return (schema == null || schema.isEmpty())
            ? meta.tableName()
            : schema + "." + meta.tableName();
    }
}
