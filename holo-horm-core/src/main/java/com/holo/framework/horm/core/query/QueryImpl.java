package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.HormException;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.RelationType;
import com.holo.framework.horm.meta.Row;
import com.holo.framework.horm.meta.query.Condition;
import com.holo.framework.horm.meta.query.RelationField;
import com.holo.framework.horm.meta.query.TypedField;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // M3: relation fetch/join state. Empty joins + null selectFields keeps
    // the default SELECT * path intact (preserves M2 SQL assertions).
    private final List<RelationField<T, ?>> joins = new ArrayList<>();
    private final List<JoinKind> joinKinds = new ArrayList<>();
    private List<TypedField<T, ?>> selectFields;  // null means SELECT *

    private enum JoinKind { FETCH, LEFT, INNER }

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
        if (field == null) {
            throw new IllegalArgumentException("field must not be null");
        }
        String alias;
        if (field.entityType().equals(entityType)) {
            // Root entity column. Default path (no joins) keeps bare column
            // name to preserve M2 SQL assertions; JOIN path prefixes "t0".
            alias = joins.isEmpty() ? null : "t0";
        } else {
            // Accept columns from a joined target entity (M3 relaxed rule).
            // The target must already be registered via fetch/leftJoin/
            // innerJoin before orderBy is called.
            int joinIdx = -1;
            for (int i = 0; i < joins.size(); i++) {
                if (joins.get(i).targetType().equals(field.entityType())) {
                    joinIdx = i;
                    break;
                }
            }
            if (joinIdx < 0) {
                throw new HormException(
                    "ORDER BY field '" + field.name()
                        + "' does not belong to entity '" + entityType.getName()
                        + "' or any of its joins");
            }
            alias = "t" + (joinIdx + 1);
        }
        orderByClauses.add(new OrderBy(field.column(), direction, alias));
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
    public Query<T> fetch(RelationField<T, ?> relation) {
        return addJoin(relation, JoinKind.FETCH);
    }

    @Override
    public Query<T> leftJoin(RelationField<T, ?> relation) {
        return addJoin(relation, JoinKind.LEFT);
    }

    @Override
    public Query<T> innerJoin(RelationField<T, ?> relation) {
        return addJoin(relation, JoinKind.INNER);
    }

    @Override
    public Query<T> join(RelationField<T, ?> relation) {
        return innerJoin(relation);
    }

    @Override
    public Query<T> select(TypedField<T, ?>... fields) {
        if (fields == null || fields.length == 0) {
            throw new HormException("select projection must include at least the id field; got empty");
        }
        FieldMeta<?> idField = meta.idField();
        if (idField != null) {
            boolean hasId = false;
            for (TypedField<T, ?> f : fields) {
                if (f != null && f.name().equals(idField.name())) {
                    hasId = true;
                    break;
                }
            }
            if (!hasId) {
                throw new HormException(
                    "select projection must include id field '" + idField.name() + "'");
            }
        }
        this.selectFields = new ArrayList<>(Arrays.asList(fields));
        return this;
    }

    private Query<T> addJoin(RelationField<T, ?> relation, JoinKind kind) {
        if (relation == null) {
            throw new IllegalArgumentException("relation must not be null");
        }
        if (!relation.entityType().equals(entityType)) {
            throw new HormException(
                "Relation '" + relation.name()
                    + "' does not belong to entity '" + entityType.getName() + "'");
        }
        joins.add(relation);
        joinKinds.add(kind);
        return this;
    }

    @Override
    public List<T> list() {
        if (!joins.isEmpty() || selectFields != null) {
            return listWithFetch();
        }
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
        if (!joins.isEmpty() || selectFields != null) {
            List<T> all = listWithFetch();
            return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
        }
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
            if (ob.alias != null) {
                sql.append(ob.alias).append(".");
            }
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

    private String qualifiedTableOf(EntityMeta<?> m) {
        String schema = m.schema();
        return (schema == null || schema.isEmpty())
            ? m.tableName()
            : schema + "." + m.tableName();
    }

    /**
     * Returns the {@link FieldMeta} list to project for the root entity.
     * When {@link #select} was called, returns only the projected fields
     * (preserving the user-supplied order); otherwise returns all fields.
     */
    private List<FieldMeta<?>> projectedFields() {
        if (selectFields == null) {
            return new ArrayList<>(meta.fields());
        }
        List<FieldMeta<?>> result = new ArrayList<>();
        for (TypedField<T, ?> tf : selectFields) {
            for (FieldMeta<?> fm : meta.fields()) {
                if (fm.name().equals(tf.name())) {
                    result.add(fm);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Resolve each {@link #joins} entry to a {@link JoinAlias} with allocated
     * SQL aliases. Target aliases are {@code t1, t2, ...} in join order
     * (matching the {@code orderBy} alias inference). HABTM middle aliases
     * are {@code jt1, jt2, ...}; THROUGH middle aliases are
     * {@code th1, th2, ...} — independent counters so target aliases stay
     * contiguous.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<JoinAlias> buildJoinAliases() {
        List<JoinAlias> result = new ArrayList<>();
        int targetCounter = 0;
        int jtCounter = 0;
        int thCounter = 0;
        for (int i = 0; i < joins.size(); i++) {
            RelationField<T, ?> rel = joins.get(i);
            JoinKind kind = joinKinds.get(i);
            String targetAlias = "t" + (++targetCounter);
            String middleAlias = null;
            RelationType rt = rel.relationType();
            if (rt == RelationType.HAS_AND_BELONGS_TO_MANY) {
                middleAlias = "jt" + (++jtCounter);
            } else if (rt == RelationType.HAS_MANY_THROUGH) {
                middleAlias = "th" + (++thCounter);
            }
            EntityMeta<?> targetMeta = EntityMetaRegistry.lookup(rel.targetType());
            Mapper<?> targetMapper = targetMeta.mapper();
            result.add(new JoinAlias(rel, kind, targetAlias, middleAlias, targetMeta, targetMapper));
        }
        return result;
    }

    /**
     * Build the JOIN/投影 SQL with aliased columns ({@code t0__col},
     * {@code t1__col}, ...) so the ResultSet can be split per-entity by
     * {@link #splitPrefixedRow} without column-name collisions.
     */
    private String buildAliasedSql(List<Object> bindings, List<JoinAlias> joinAliases) {
        StringBuilder sql = new StringBuilder("SELECT ");

        List<String> selectCols = new ArrayList<>();
        for (FieldMeta<?> fd : projectedFields()) {
            selectCols.add("t0." + fd.column() + " AS t0__" + fd.column());
        }
        for (JoinAlias ja : joinAliases) {
            for (FieldMeta<?> fd : ja.targetMeta.fields()) {
                selectCols.add(ja.targetAlias + "." + fd.column()
                    + " AS " + ja.targetAlias + "__" + fd.column());
            }
        }
        sql.append(String.join(", ", selectCols));

        sql.append(" FROM ").append(qualifiedTable()).append(" t0");

        for (JoinAlias ja : joinAliases) {
            String kind = (ja.kind == JoinKind.INNER) ? "INNER JOIN" : "LEFT JOIN";
            RelationType rt = ja.relation.relationType();
            String targetTable = qualifiedTableOf(ja.targetMeta);
            if (rt == RelationType.BELONGS_TO) {
                sql.append(" ").append(kind).append(" ").append(targetTable).append(" ").append(ja.targetAlias)
                    .append(" ON ").append(ja.targetAlias).append(".id = t0.")
                    .append(ja.relation.foreignKey());
            } else if (rt == RelationType.HAS_ONE || rt == RelationType.HAS_MANY) {
                sql.append(" ").append(kind).append(" ").append(targetTable).append(" ").append(ja.targetAlias)
                    .append(" ON ").append(ja.targetAlias).append(".").append(ja.relation.foreignKey())
                    .append(" = t0.id");
            } else if (rt == RelationType.HAS_AND_BELONGS_TO_MANY) {
                sql.append(" ").append(kind).append(" ").append(ja.relation.joinTable()).append(" ").append(ja.middleAlias)
                    .append(" ON ").append(ja.middleAlias).append(".").append(ja.relation.foreignKey())
                    .append(" = t0.id");
                sql.append(" ").append(kind).append(" ").append(targetTable).append(" ").append(ja.targetAlias)
                    .append(" ON ").append(ja.targetAlias).append(".id = ").append(ja.middleAlias)
                    .append(".").append(ja.relation.associationForeignKey());
            } else if (rt == RelationType.HAS_MANY_THROUGH) {
                EntityMeta<?> throughMeta = EntityMetaRegistry.lookup(ja.relation.through());
                String throughTable = qualifiedTableOf(throughMeta);
                sql.append(" ").append(kind).append(" ").append(throughTable).append(" ").append(ja.middleAlias)
                    .append(" ON ").append(ja.middleAlias).append(".").append(ja.relation.foreignKey())
                    .append(" = t0.id");
                sql.append(" ").append(kind).append(" ").append(targetTable).append(" ").append(ja.targetAlias)
                    .append(" ON ").append(ja.targetAlias).append(".id = ").append(ja.middleAlias)
                    .append(".").append(ja.relation.associationForeignKey());
            }
        }

        appendWhere(sql, bindings);
        appendOrderBy(sql);
        appendLimitOffset(sql, bindings);
        return sql.toString();
    }

    /**
     * Read columns prefixed with {@code <alias>__} from the ResultSet and
     * write them to a {@link Row} keyed by bare column name. This isolates
     * the per-entity column namespace without modifying {@link Row} or
     * {@link Mapper#map}.
     */
    private Row splitPrefixedRow(ResultSet rs, String alias, EntityMeta<?> targetMeta) throws SQLException {
        Row row = Row.create(targetMeta.tableName());
        for (FieldMeta<?> fd : targetMeta.fields()) {
            row.set(fd.column(), rs.getObject(alias + "__" + fd.column()));
        }
        return row;
    }

    /**
     * Execute the JOIN/投影 query and assemble the entity graph.
     *
     * <p>Cartesian products from HAS_MANY/HAS_AND_BELONGS_TO_MANY are
     * de-duplicated by root id: each root appears once in the result, with
     * its relation collections accumulated across all rows sharing the same
     * root id. LEFT JOIN rows with a null target id contribute an empty
     * collection rather than a null entry.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<T> listWithFetch() {
        List<JoinAlias> joinAliases = buildJoinAliases();
        List<Object> bindings = new ArrayList<>();
        String sql = buildAliasedSql(bindings, joinAliases);

        LinkedHashMap<Object, T> byRootId = new LinkedHashMap<>();
        List<Map<Object, List<Object>>> perJoinAccumulated = new ArrayList<>();
        for (int i = 0; i < joinAliases.size(); i++) {
            perJoinAccumulated.add(new HashMap<>());
        }

        try (PreparedStatement ps = ctx.connection().prepareStatement(sql)) {
            bind(ps, bindings);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Row rootRow = splitPrefixedRow(rs, "t0", meta);
                    T root = mapper.map(rootRow);
                    Object rootId = mapper.getId(root);

                    T existing = byRootId.putIfAbsent(rootId, root);
                    T actualRoot = (existing != null) ? existing : root;

                    for (int i = 0; i < joinAliases.size(); i++) {
                        JoinAlias ja = joinAliases.get(i);
                        Row targetRow = splitPrefixedRow(rs, ja.targetAlias, ja.targetMeta);
                        Mapper<Object> targetMapper = (Mapper<Object>) ja.targetMapper;
                        Object target = targetMapper.map(targetRow);
                        Object targetId = targetMapper.getId(target);

                        // LEFT JOIN with no matching row yields null target id;
                        // skip so the relation stays an empty list.
                        if (targetId == null) {
                            continue;
                        }

                        Map<Object, List<Object>> accumulated = perJoinAccumulated.get(i);
                        List<Object> list = accumulated.computeIfAbsent(rootId, k -> new ArrayList<>());
                        list.add(target);
                    }
                }
            }
        } catch (SQLException e) {
            throw new HormException("Failed to listWithFetch " + entityType.getName(), e);
        }

        for (Map.Entry<Object, T> entry : byRootId.entrySet()) {
            Object rootId = entry.getKey();
            T root = entry.getValue();
            for (int i = 0; i < joinAliases.size(); i++) {
                JoinAlias ja = joinAliases.get(i);
                List<Object> list = perJoinAccumulated.get(i).getOrDefault(rootId, new ArrayList<>());
                mapper.setRelation(root, ja.relation.name(), list);
            }
        }

        return new ArrayList<>(byRootId.values());
    }

    private static final class OrderBy {
        final String column;
        final Order direction;
        final String alias;  // null = bare column (default path); "t0"/"t1"/... = JOIN path

        OrderBy(String column, Order direction, String alias) {
            this.column = column;
            this.direction = direction;
            this.alias = alias;
        }
    }

    /**
     * Per-join resolved metadata: allocated SQL aliases + looked-up
     * {@link EntityMeta}/{@link Mapper} for the join target.
     */
    private static final class JoinAlias {
        final RelationField<?, ?> relation;
        final JoinKind kind;
        final String targetAlias;  // t1, t2, ...
        final String middleAlias;  // jt1/jt2 (HABTM) or th1/th2 (THROUGH), null otherwise
        final EntityMeta<?> targetMeta;
        final Mapper<?> targetMapper;

        JoinAlias(RelationField<?, ?> relation, JoinKind kind, String targetAlias,
                  String middleAlias, EntityMeta<?> targetMeta, Mapper<?> targetMapper) {
            this.relation = relation;
            this.kind = kind;
            this.targetAlias = targetAlias;
            this.middleAlias = middleAlias;
            this.targetMeta = targetMeta;
            this.targetMapper = targetMapper;
        }
    }
}
