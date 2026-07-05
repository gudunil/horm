package com.holo.framework.horm.meta;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable runtime metadata for a HORM entity, assembled from the
 * compile-time-generated {@code XxxMeta} class.
 *
 * <p>One {@code EntityMeta<T>} instance per entity type, registered in the
 * {@code EntityMetaRegistry} at startup. The registry reads
 * {@code META-INF/horm/entities.idx} (produced by the APT processor) and
 * invokes each {@code XxxMeta.entityMeta()} static factory.
 *
 * <p>Runtime code looks up fields by name or column without reflection:
 * <pre>{@code
 * EntityMeta<User> meta = registry.get(User.class);
 * FieldMeta<?> emailField = meta.fieldByColumn("email");
 * }</pre>
 *
 * @param <T> entity type
 */
public final class EntityMeta<T> {

    private final Class<T> type;
    private final String tableName;
    private final String schema;
    private final String dataSource;
    private final List<FieldMeta<?>> fields;
    private final FieldMeta<?> idField;
    private final Mapper<T> mapper;
    private final List<RelationMeta> relations;
    private final FieldMeta<?> versionField;

    private EntityMeta(Builder<T> b) {
        this.type = Objects.requireNonNull(b.type, "type");
        this.tableName = Objects.requireNonNull(b.tableName, "tableName");
        this.schema = b.schema;
        this.dataSource = b.dataSource;
        this.fields = List.copyOf(Objects.requireNonNull(b.fields, "fields"));
        this.idField = b.idField;
        this.mapper = b.mapper;
        this.relations = b.relations == null ? List.of() : List.copyOf(b.relations);
        this.versionField = b.versionField;
    }

    public Class<T> type() { return type; }
    public String tableName() { return tableName; }
    public String schema() { return schema; }
    public String dataSource() { return dataSource; }
    public List<FieldMeta<?>> fields() { return fields; }
    public FieldMeta<?> idField() { return idField; }
    public Mapper<T> mapper() { return mapper; }
    public List<RelationMeta> relations() { return relations; }
    public FieldMeta<?> versionField() { return versionField; }

    /** Looks up a field by Java property name. */
    public Optional<FieldMeta<?>> field(String name) {
        for (FieldMeta<?> f : fields) {
            if (f.name().equals(name)) return Optional.of(f);
        }
        return Optional.empty();
    }

    /** Looks up a field by physical column name. */
    public Optional<FieldMeta<?>> fieldByColumn(String column) {
        for (FieldMeta<?> f : fields) {
            if (f.column().equals(column)) return Optional.of(f);
        }
        return Optional.empty();
    }

    /** Returns the names of columns included in INSERT statements (excludes non-insertable and id-with-identity). */
    public List<String> insertableColumns() {
        return fields.stream()
            .filter(FieldMeta::insertable)
            .filter(f -> !(f.isId() && f.generationStrategy() != null))
            .map(FieldMeta::column)
            .toList();
    }

    public static <T> Builder<T> builder() { return new Builder<>(); }

    public static final class Builder<T> {
        private Class<T> type;
        private String tableName;
        private String schema;
        private String dataSource;
        private List<FieldMeta<?>> fields;
        private FieldMeta<?> idField;
        private Mapper<T> mapper;
        private List<RelationMeta> relations;
        private FieldMeta<?> versionField;

        public Builder<T> type(Class<T> type) { this.type = type; return this; }
        public Builder<T> tableName(String tableName) { this.tableName = tableName; return this; }
        public Builder<T> schema(String schema) { this.schema = schema; return this; }
        public Builder<T> dataSource(String dataSource) { this.dataSource = dataSource; return this; }
        public Builder<T> fields(List<FieldMeta<?>> fields) { this.fields = fields; return this; }
        public Builder<T> idField(FieldMeta<?> idField) { this.idField = idField; return this; }
        public Builder<T> mapper(Mapper<T> mapper) { this.mapper = mapper; return this; }
        public Builder<T> relations(List<RelationMeta> relations) { this.relations = relations; return this; }
        public Builder<T> versionField(FieldMeta<?> versionField) { this.versionField = versionField; return this; }

        public EntityMeta<T> build() { return new EntityMeta<>(this); }
    }
}
