package com.holo.framework.horm.meta;

import com.holo.framework.horm.meta.annotation.GenerationType;

import java.util.Objects;

/**
 * Immutable metadata describing a single entity field and its mapping to a
 * data source column.
 *
 * <p>Instances are created at compile time by the {@code holo-horm-meta}
 * annotation processor and exposed as static constants on the generated
 * {@code XxxMeta} class. Runtime code reads them without reflection.
 *
 * @see EntityMeta
 */
public final class FieldMeta<T> {

    private final String name;
    private final String column;
    private final Class<T> type;
    private final boolean id;
    private final GenerationType generationStrategy;
    private final boolean nullable;
    private final boolean unique;
    private final int length;
    private final int precision;
    private final int scale;
    private final boolean insertable;
    private final boolean updatable;

    private FieldMeta(Builder<T> b) {
        this.name = Objects.requireNonNull(b.name, "name");
        this.column = (b.column == null || b.column.isEmpty()) ? b.name : b.column;
        this.type = Objects.requireNonNull(b.type, "type");
        this.id = b.id;
        this.generationStrategy = b.generationStrategy;
        this.nullable = b.nullable;
        this.unique = b.unique;
        this.length = b.length;
        this.precision = b.precision;
        this.scale = b.scale;
        this.insertable = b.insertable;
        this.updatable = b.updatable;
    }

    public String name() { return name; }
    public String column() { return column; }
    public Class<T> type() { return type; }
    public boolean isId() { return id; }
    public GenerationType generationStrategy() { return generationStrategy; }
    public boolean nullable() { return nullable; }
    public boolean unique() { return unique; }
    public int length() { return length; }
    public int precision() { return precision; }
    public int scale() { return scale; }
    public boolean insertable() { return insertable; }
    public boolean updatable() { return updatable; }

    public static <T> Builder<T> builder() { return new Builder<>(); }

    public static final class Builder<T> {
        private String name;
        private String column;
        private Class<T> type;
        private boolean id;
        private GenerationType generationStrategy;
        private boolean nullable = true;
        private boolean unique = false;
        private int length = 255;
        private int precision = 0;
        private int scale = 0;
        private boolean insertable = true;
        private boolean updatable = true;

        public Builder<T> name(String name) { this.name = name; return this; }
        public Builder<T> column(String column) { this.column = column; return this; }
        public Builder<T> type(Class<T> type) { this.type = type; return this; }
        public Builder<T> id(boolean id) { this.id = id; return this; }
        public Builder<T> generationStrategy(GenerationType strategy) { this.generationStrategy = strategy; return this; }
        public Builder<T> nullable(boolean nullable) { this.nullable = nullable; return this; }
        public Builder<T> unique(boolean unique) { this.unique = unique; return this; }
        public Builder<T> length(int length) { this.length = length; return this; }
        public Builder<T> precision(int precision) { this.precision = precision; return this; }
        public Builder<T> scale(int scale) { this.scale = scale; return this; }
        public Builder<T> insertable(boolean insertable) { this.insertable = insertable; return this; }
        public Builder<T> updatable(boolean updatable) { this.updatable = updatable; return this; }

        public FieldMeta<T> build() { return new FieldMeta<>(this); }
    }
}
