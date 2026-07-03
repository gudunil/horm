package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.Row;

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

    public JdbcRepository(Class<T> entityType, HormContext ctx) {
        this.entityType = entityType;
        this.ctx = ctx;
        this.meta = EntityMetaRegistry.lookup(entityType);
        this.mapper = meta.mapper();
    }

    @Override
    public T find(Object id) {
        FieldMeta<?> idField = requireIdField();
        String sql = "SELECT * FROM " + qualifiedTable()
            + " WHERE " + idField.column() + " = ?";
        try (PreparedStatement ps = ctx.connection().prepareStatement(sql)) {
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
        }
    }

    @Override
    public List<T> all() {
        String sql = "SELECT * FROM " + qualifiedTable();
        List<T> result = new ArrayList<>();
        try (PreparedStatement ps = ctx.connection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapper.map(toRow(rs)));
            }
        } catch (SQLException e) {
            throw new HormException("Failed to fetch all " + entityType.getName(), e);
        }
        return result;
    }

    @Override
    public long count() {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable();
        try (PreparedStatement ps = ctx.connection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (SQLException e) {
            throw new HormException("Failed to count " + entityType.getName(), e);
        }
    }

    @Override
    public boolean exists(Object id) {
        FieldMeta<?> idField = requireIdField();
        // H2 MODE=MySQL and MySQL both accept LIMIT 1; other dialects tolerate
        // the redundant row and rely on rs.next() short-circuiting.
        String sql = "SELECT 1 FROM " + qualifiedTable()
            + " WHERE " + idField.column() + " = ? LIMIT 1";
        try (PreparedStatement ps = ctx.connection().prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new HormException(
                "Failed to check existence of " + entityType.getName() + " by id " + id, e);
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
        try (PreparedStatement ps = ctx.connection().prepareStatement(
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
        }
        return entity;
    }

    private T update(T entity) {
        FieldMeta<?> idField = requireIdField();
        List<String> columns = meta.fields().stream()
            .filter(FieldMeta::updatable)
            .filter(f -> !f.isId())
            .map(FieldMeta::column)
            .toList();
        String setClause = columns.stream()
            .map(c -> c + " = ?")
            .collect(Collectors.joining(", "));
        String sql = "UPDATE " + qualifiedTable()
            + " SET " + setClause
            + " WHERE " + idField.column() + " = ?";
        Row row = mapper.toRow(entity);
        try (PreparedStatement ps = ctx.connection().prepareStatement(sql)) {
            int i = 1;
            for (String col : columns) {
                ps.setObject(i++, row.get(col));
            }
            ps.setObject(i, mapper.getId(entity));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new HormException("Failed to update " + entityType.getName(), e);
        }
        return entity;
    }

    @Override
    public void delete(T entity) {
        deleteById(mapper.getId(entity));
    }

    @Override
    public void deleteById(Object id) {
        FieldMeta<?> idField = requireIdField();
        String sql = "DELETE FROM " + qualifiedTable()
            + " WHERE " + idField.column() + " = ?";
        try (PreparedStatement ps = ctx.connection().prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new HormException(
                "Failed to delete " + entityType.getName() + " by id " + id, e);
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
