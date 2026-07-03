package com.holo.framework.horm.meta.query;

public final class StringField<E> implements ComparableField<E, String> {
    private final Class<E> entityType;
    private final String name;
    private final String column;

    private StringField(Class<E> entityType, String name, String column) {
        this.entityType = entityType;
        this.name = name;
        this.column = column;
    }

    @Override public Class<E> entityType() { return entityType; }
    @Override public String name() { return name; }
    @Override public String column() { return column; }
    @Override public Class<String> type() { return String.class; }

    public Condition like(String pattern) {
        return Conditions.like(this, pattern);
    }

    public static <E> StringField<E> of(Class<E> entityType, String name, String column) {
        return new StringField<>(entityType, name, column);
    }
}
