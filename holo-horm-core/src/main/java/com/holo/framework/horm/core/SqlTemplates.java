package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Pre-generates and caches the SQL strings for an entity's fixed-shape
 * CRUD operations. Constructed once per {@link JdbcRepository} instance,
 * it eliminates the per-call string concatenation that previously occurred
 * on every {@code find}/{@code all}/{@code count}/{@code insert}/
 * {@code delete} invocation.
 *
 * <p>Only SQL statements whose structure is fully determined by the
 * {@link EntityMeta} (table name, column names, id column) are pre-generated
 * here. The {@code update} SQL is <em>not</em> pre-generated because its
 * SET clause depends on runtime field-value combinations and optimistic-lock
 * configuration.
 *
 * <p>The INSERT SQL template uses {@link EntityMeta#insertableColumns()},
 * which excludes auto-generated primary keys by default and includes the
 * primary key column when the id strategy is {@code GenerationType.MANUAL}.
 *
 * <p><strong>Internal API.</strong> Public for reuse by {@link JdbcRepository};
 * not part of the stable HORM contract and may change between releases.
 */
public final class SqlTemplates {

    private final String findByIdSql;
    private final String findAllSql;
    private final String countSql;
    private final String insertSql;
    private final String deleteByIdSql;
    private final String deleteByIdAndVersionSql;
    private final List<String> insertColumns;

    /**
     * Builds the SQL templates from the given entity metadata.
     *
     * @param meta the entity metadata; must not be {@code null}
     */
    public SqlTemplates(EntityMeta<?> meta) {
        String table = MetaSupport.qualifiedTable(meta);

        this.findAllSql = "SELECT * FROM " + table;
        this.countSql = "SELECT COUNT(*) FROM " + table;

        FieldMeta<?> idField = meta.idField();
        if (idField != null) {
            String idColumn = idField.column();
            this.findByIdSql = "SELECT * FROM " + table
                + " WHERE " + idColumn + " = ?";
            this.deleteByIdSql = "DELETE FROM " + table
                + " WHERE " + idColumn + " = ?";
        } else {
            this.findByIdSql = null;
            this.deleteByIdSql = null;
        }

        this.insertColumns = meta.insertableColumns();
        this.insertSql = "INSERT INTO " + table + " ("
            + String.join(", ", insertColumns)
            + ") VALUES ("
            + insertColumns.stream().map(c -> "?").collect(Collectors.joining(", "))
            + ")";

        FieldMeta<?> versionField = meta.versionField();
        if (idField != null && versionField != null) {
            this.deleteByIdAndVersionSql = "DELETE FROM " + table
                + " WHERE " + idField.column() + " = ? AND "
                + versionField.column() + " = ?";
        } else {
            this.deleteByIdAndVersionSql = null;
        }
    }

    public String findById() { return findByIdSql; }
    public String findAll() { return findAllSql; }
    public String count() { return countSql; }
    public String insert() { return insertSql; }
    public String deleteById() { return deleteByIdSql; }
    public String deleteByIdAndVersion() { return deleteByIdAndVersionSql; }
    public List<String> insertColumns() { return insertColumns; }
}
