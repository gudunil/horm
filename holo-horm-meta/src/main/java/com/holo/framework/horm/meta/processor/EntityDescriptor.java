package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.annotation.GenerationType;

import java.util.ArrayList;
import java.util.List;

/**
 * Intermediate representation of an entity built by {@link EntityDescriptorParser}
 * from a {@code @Entity}-annotated {@link javax.lang.model.element.TypeElement}.
 *
 * <p>Carries parsed field metadata in a form that {@code JavaPoet} builders can
 * consume directly — type names are stored as fully-qualified strings so that
 * no reflection or {@code Class} loading is needed during compilation.
 */
public final class EntityDescriptor {

    private final String packageName;
    private final String simpleName;
    private final String qualifiedName;
    private final String tableName;
    private final String schema;
    private final String dataSource;
    private final List<FieldDescriptor> fields;
    private final FieldDescriptor idField;

    private EntityDescriptor(Builder b) {
        this.packageName = b.packageName;
        this.simpleName = b.simpleName;
        this.qualifiedName = b.qualifiedName;
        this.tableName = b.tableName;
        this.schema = b.schema;
        this.dataSource = b.dataSource;
        this.fields = List.copyOf(b.fields);
        this.idField = b.idField;
    }

    public String packageName() { return packageName; }
    public String simpleName() { return simpleName; }
    public String qualifiedName() { return qualifiedName; }
    public String tableName() { return tableName; }
    public String schema() { return schema; }
    public String dataSource() { return dataSource; }
    public List<FieldDescriptor> fields() { return fields; }
    public FieldDescriptor idField() { return idField; }

    /** Package where generated companion classes (XxxMeta, XxxMapper) are written. */
    public String generatedPackage() {
        return packageName + ".generated";
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String packageName;
        private String simpleName;
        private String qualifiedName;
        private String tableName;
        private String schema;
        private String dataSource;
        private final List<FieldDescriptor> fields = new ArrayList<>();
        private FieldDescriptor idField;

        public Builder packageName(String v) { this.packageName = v; return this; }
        public Builder simpleName(String v) { this.simpleName = v; return this; }
        public Builder qualifiedName(String v) { this.qualifiedName = v; return this; }
        public Builder tableName(String v) { this.tableName = v; return this; }
        public Builder schema(String v) { this.schema = v; return this; }
        public Builder dataSource(String v) { this.dataSource = v; return this; }
        public Builder addField(FieldDescriptor f) { this.fields.add(f); return this; }
        public Builder idField(FieldDescriptor f) { this.idField = f; return this; }

        public EntityDescriptor build() { return new EntityDescriptor(this); }
    }

    /**
     * Intermediate representation of a single entity field.
     */
    public static final class FieldDescriptor {
        private final String name;
        private final String column;
        private final String typeName;            // simple name, e.g. "Long"
        private final String typeQualifiedName;   // FQN, e.g. "java.lang.Long"
        private final boolean primitive;          // true for primitive types
        private final boolean id;
        private final GenerationType generationStrategy;
        private final boolean nullable;
        private final boolean unique;
        private final int length;
        private final int precision;
        private final int scale;
        private final boolean insertable;
        private final boolean updatable;
        private final String getterName;
        private final String setterName;
        private final boolean enumType;
        private final String enumQualifiedName;   // for enum types

        public FieldDescriptor(String name, String column, String typeName, String typeQualifiedName,
                               boolean primitive, boolean id, GenerationType strategy,
                               boolean nullable, boolean unique, int length, int precision, int scale,
                               boolean insertable, boolean updatable,
                               String getterName, String setterName,
                               boolean enumType, String enumQualifiedName) {
            this.name = name;
            this.column = (column == null || column.isEmpty()) ? name : column;
            this.typeName = typeName;
            this.typeQualifiedName = typeQualifiedName;
            this.primitive = primitive;
            this.id = id;
            this.generationStrategy = strategy;
            this.nullable = nullable;
            this.unique = unique;
            this.length = length;
            this.precision = precision;
            this.scale = scale;
            this.insertable = insertable;
            this.updatable = updatable;
            this.getterName = getterName;
            this.setterName = setterName;
            this.enumType = enumType;
            this.enumQualifiedName = enumQualifiedName;
        }

        public String name() { return name; }
        public String column() { return column; }
        public String typeName() { return typeName; }
        public String typeQualifiedName() { return typeQualifiedName; }
        public boolean primitive() { return primitive; }
        public boolean isId() { return id; }
        public GenerationType generationStrategy() { return generationStrategy; }
        public boolean nullable() { return nullable; }
        public boolean unique() { return unique; }
        public int length() { return length; }
        public int precision() { return precision; }
        public int scale() { return scale; }
        public boolean insertable() { return insertable; }
        public boolean updatable() { return updatable; }
        public String getterName() { return getterName; }
        public String setterName() { return setterName; }
        public boolean enumType() { return enumType; }
        public String enumQualifiedName() { return enumQualifiedName; }
    }
}
