package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.HormException;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.TransactionManager;
import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.query.Condition;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link DeleteQuery} implementation. Composes a DELETE statement
 * from the entity's {@link EntityMeta} and the user-supplied WHERE conditions.
 */
public final class DeleteQueryImpl<T extends Model<T>> implements DeleteQuery<T> {

    private final Class<T> entityType;
    private final HormContext ctx;
    private final EntityMeta<T> meta;

    private final List<Condition> whereConditions = new ArrayList<>();

    public DeleteQueryImpl(Class<T> entityType, HormContext ctx) {
        this.entityType = entityType;
        this.ctx = ctx;
        this.meta = EntityMetaRegistry.lookup(entityType);
    }

    @Override
    public DeleteQuery<T> where(Condition... conditions) {
        appendConditions(conditions);
        return this;
    }

    @Override
    public DeleteQuery<T> and(Condition... conditions) {
        appendConditions(conditions);
        return this;
    }

    @Override
    public int execute() {
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(qualifiedTable());
        List<Object> bindings = new ArrayList<>();
        appendWhere(sql, bindings);

        try (PreparedStatement ps = TransactionManager.currentConnection(ctx)
                .prepareStatement(sql.toString())) {
            bind(ps, bindings);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new HormException("Failed to execute DELETE on " + entityType.getName(), e);
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
