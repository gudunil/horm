package com.holo.framework.horm.meta;

import java.util.Objects;

/**
 * Immutable metadata describing a relationship between two entities.
 *
 * <p>Created at compile time when the APT processor encounters
 * {@code @BelongsTo}, {@code @HasOne}, {@code @HasMany},
 * {@code @HasAndBelongsToMany}, or {@code @HasManyThrough} annotations
 * (M3). In M1 the contract is defined so {@link EntityMeta} can carry an
 * empty relation list.
 *
 * @see RelationType
 */
public final class RelationMeta {

    private final String name;
    private final Class<?> targetEntity;
    private final RelationType type;
    private final String foreignKey;
    private final String associationForeignKey;
    private final String joinTable;
    private final Class<?> through;

    private RelationMeta(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name");
        this.targetEntity = Objects.requireNonNull(b.targetEntity, "targetEntity");
        this.type = Objects.requireNonNull(b.type, "type");
        this.foreignKey = b.foreignKey;
        this.associationForeignKey = b.associationForeignKey;
        this.joinTable = b.joinTable;
        this.through = b.through;
    }

    public String name() { return name; }
    public Class<?> targetEntity() { return targetEntity; }
    public RelationType type() { return type; }
    public String foreignKey() { return foreignKey; }
    public String associationForeignKey() { return associationForeignKey; }
    public String joinTable() { return joinTable; }
    public Class<?> through() { return through; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String name;
        private Class<?> targetEntity;
        private RelationType type;
        private String foreignKey;
        private String associationForeignKey;
        private String joinTable;
        private Class<?> through;

        public Builder name(String name) { this.name = name; return this; }
        public Builder targetEntity(Class<?> targetEntity) { this.targetEntity = targetEntity; return this; }
        public Builder type(RelationType type) { this.type = type; return this; }
        public Builder foreignKey(String foreignKey) { this.foreignKey = foreignKey; return this; }
        public Builder associationForeignKey(String associationForeignKey) { this.associationForeignKey = associationForeignKey; return this; }
        public Builder joinTable(String joinTable) { this.joinTable = joinTable; return this; }
        public Builder through(Class<?> through) { this.through = through; return this; }

        public RelationMeta build() { return new RelationMeta(this); }
    }
}
