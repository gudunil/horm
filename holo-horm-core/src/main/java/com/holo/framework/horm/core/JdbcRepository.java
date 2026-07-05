package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.Row;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JDBC-backed {@link Repository} implementation that drives all CRUD
 * operations through parameterized {@link PreparedStatement}s.
 *
 * <p>SQL is composed at call time from the {@link EntityMeta} registered
 * for {@code T}: table name, id column, and the insertable/updatable field
 * columns are all read from compile-time-generated metadata, so no
 * reflection is involved on the hot path. Every user-supplied value is
 * bound via a {@code ?} placeholder, eliminating SQL injection risk —
 * the only string concatenation is over APT-frozen column identifiers.
 *
 * <p>{@link #save(Object)} auto-detects INSERT (when {@code mapper.getId}
 * returns {@code null}) vs UPDATE (id non-null). INSERT executes with
 * {@link Statement#RETURN_GENERATED_KEYS} and backfills the generated
 * primary key onto the entity via {@link Mapper#setId}.
 *
 * <p>All {@link SQLException}s are wrapped in {@link HormException} so
 * callers can stay block-free while still catching HORM-specific failures.
 *
 * @param <T> entity type
 */
public final class JdbcRepository<T extends Model<T>> implements Repository<T> {

    private final Class<T> entityType;
    private final HormContext ctx;
    private final EntityMeta<T> meta;
    private final Mapper<T> mapper;
    private final String dataSourceName;

    public JdbcRepository(Class<T> entityType, HormContext ctx) {
        this.entityType = entityType;
        this.ctx = ctx;
        this.meta = EntityMetaRegistry.lookup(entityType);
        this.mapper = meta.mapper();
        // Resolve datasource name from entity metadata; empty/null defaults to "default"
        String dsName = meta.dataSource();
        this.dataSourceName = (dsName == null || dsName.isEmpty())
            ? com.holo.framework.horm.core.datasource.DataSourceRegistry.DEFAULT_NAME
            : dsName;
    }

    @Override
    public T find(Object id) {
        FieldMeta<?> idField = requireIdField();
        String sql = "SELECT * FROM " + qualifiedTable()
            + " WHERE " + idField.column() + " = ?";
        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapper.map(toRow(rs));
            }
        } catch (SQLException e) {
            throw new HormException(
                "Failed to find " + entityType.getName() + " by id " + id, e);
        } finally {
            TransactionManager.releaseConnection(ctx, dataSourceName, conn);
        }
    }

    @Override
    public List<T> all() {
        String sql = "SELECT * FROM " + qualifiedTable();
        List<T> result = new ArrayList<>();
        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapper.map(toRow(rs)));
            }
        } catch (SQLException e) {
            throw new HormException("Failed to fetch all " + entityType.getName(), e);
        } finally {
            TransactionManager.releaseConnection(ctx, dataSourceName, conn);
        }
        return result;
    }

    @Override
    public long count() {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable();
        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (SQLException e) {
            throw new HormException("Failed to count " + entityType.getName(), e);
        } finally {
            TransactionManager.releaseConnection(ctx, dataSourceName, conn);
        }
    }

    @Override
    public boolean exists(Object id) {
        FieldMeta<?> idField = requireIdField();
        // H2 MODE=MySQL and MySQL both accept LIMIT 1; other dialects tolerate
        // the redundant row and rely on rs.next() short-circuiting.
        String sql = "SELECT 1 FROM " + qualifiedTable()
            + " WHERE " + idField.column() + " = ? LIMIT 1";
        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new HormException(
                "Failed to check existence of " + entityType.getName() + " by id " + id, e);
        } finally {
            TransactionManager.releaseConnection(ctx, dataSourceName, conn);
        }
    }

    @Override
    public T save(T entity) {
        Object id = mapper.getId(entity);
        return id == null ? insert(entity) : update(entity);
    }

    private T insert(T entity) {
        requireIdField();
        // Simplified M1 policy: the id column is always excluded from the
        // INSERT column list so the data source generates it (IDENTITY).
        List<String> columns = meta.fields().stream()
            .filter(FieldMeta::insertable)
            .filter(f -> !f.isId())
            .map(FieldMeta::column)
            .toList();
        String sql = "INSERT INTO " + qualifiedTable() + " ("
            + String.join(", ", columns)
            + ") VALUES ("
            + columns.stream().map(c -> "?").collect(Collectors.joining(", "))
            + ")";
        Row row = mapper.toRow(entity);
        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        try (PreparedStatement ps = conn.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            for (String col : columns) {
                ps.setObject(i++, row.get(col));
            }
            ps.executeUpdate();
            try (ResultSet genKeys = ps.getGeneratedKeys()) {
                if (genKeys.next()) {
                    long generatedId = genKeys.getLong(1);
                    mapper.setId(entity, generatedId);
                }
            }
        } catch (SQLException e) {
            throw new HormException("Failed to insert " + entityType.getName(), e);
        } finally {
            TransactionManager.releaseConnection(ctx, dataSourceName, conn);
        }
        return entity;
    }

    private T update(T entity) {
        FieldMeta<?> idField = requireIdField();
        FieldMeta<?> versionField = meta.versionField();

        List<String> columns = meta.fields().stream()
            .filter(FieldMeta::updatable)
            .filter(f -> !f.isId())
            .filter(f -> !f.version())
            .map(FieldMeta::column)
            .toList();

        String setClause = columns.stream()
            .map(c -> c + " = ?")
            .collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder("UPDATE ")
            .append(qualifiedTable())
            .append(" SET ");

        List<Object> bindings = new ArrayList<>();
        Row row = mapper.toRow(entity);

        if (columns.isEmpty() && versionField == null) {
            throw new IllegalStateException(
                "No updatable fields for " + entityType.getName() + "; nothing to update");
        }

        if (!columns.isEmpty()) {
            sql.append(setClause);
            for (String col : columns) {
                bindings.add(row.get(col));
            }
        }

        // Version column: SET version = version + 1
        if (versionField != null) {
            if (!columns.isEmpty()) {
                sql.append(", ");
            }
            sql.append(versionField.column()).append(" = ").append(versionField.column()).append(" + 1");
        }

        sql.append(" WHERE ").append(idField.column()).append(" = ?");
        bindings.add(mapper.getId(entity));

        // Version check: AND version = ?
        if (versionField != null) {
            sql.append(" AND ").append(versionField.column()).append(" = ?");
            bindings.add(mapper.getField(entity, versionField.name()));
        }

        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            for (Object val : bindings) {
                ps.setObject(i++, val);
            }
            int affected = ps.executeUpdate();
            if (versionField != null && affected == 0) {
                throw new OptimisticLockException(
                    "Optimistic lock failed for " + entityType.getName()
                        + " with id=" + mapper.getId(entity));
            }
            if (versionField != null) {
                mapper.incrementVersion(entity);
            }
        } catch (SQLException e) {
            throw new HormException("Failed to update " + entityType.getName(), e);
        } finally {
            TransactionManager.releaseConnection(ctx, dataSourceName, conn);
        }
        return entity;
    }

    @Override
    public void delete(T entity) {
        FieldMeta<?> versionField = meta.versionField();
        Object id = mapper.getId(entity);
        if (versionField != null) {
            StringBuilder sql = new StringBuilder("DELETE FROM ")
                .append(qualifiedTable())
                .append(" WHERE ")
                .append(requireIdField().column())
                .append(" = ? AND ")
                .append(versionField.column())
                .append(" = ?");
            Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                ps.setObject(1, id);
                ps.setObject(2, mapper.getField(entity, versionField.name()));
                int affected = ps.executeUpdate();
                if (affected == 0) {
                    throw new OptimisticLockException(
                        "Optimistic lock failed on delete for " + entityType.getName()
                            + " with id=" + id);
                }
            } catch (SQLException e) {
                throw new HormException(
                    "Failed to delete " + entityType.getName() + " by id " + id, e);
            } finally {
                TransactionManager.releaseConnection(ctx, dataSourceName, conn);
            }
        } else {
            deleteById(id);
        }
    }

    @Override
    public void deleteById(Object id) {
        FieldMeta<?> idField = requireIdField();
        String sql = "DELETE FROM " + qualifiedTable()
            + " WHERE " + idField.column() + " = ?";
        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new HormException(
                "Failed to delete " + entityType.getName() + " by id " + id, e);
        } finally {
            TransactionManager.releaseConnection(ctx, dataSourceName, conn);
        }
    }

    /** Materializes a {@link Row} from the current cursor position of {@code rs}. */
    private Row toRow(ResultSet rs) throws SQLException {
        Row row = Row.create(meta.tableName());
        for (FieldMeta<?> fd : meta.fields()) {
            row.set(fd.column(), rs.getObject(fd.column()));
        }
        return row;
    }

    /** Returns {@code schema.tableName} when a schema is set, else {@code tableName}. */
    private String qualifiedTable() {
        String schema = meta.schema();
        return (schema == null || schema.isEmpty())
            ? meta.tableName()
            : schema + "." + meta.tableName();
    }

    private FieldMeta<?> requireIdField() {
        FieldMeta<?> idField = meta.idField();
        if (idField == null) {
            throw new HormException("Entity " + entityType.getName() + " has no @Id field");
        }
        return idField;
    }
}
