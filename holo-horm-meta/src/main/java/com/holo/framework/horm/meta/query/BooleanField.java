package com.holo.framework.horm.meta.query;

public final class BooleanField<E> implements TypedField<E, Boolean> {
    private final Class<E> entityType;
    private final String name;
    private final String column;

    private BooleanField(Class<E> entityType, String name, String column) {
        this.entityType = entityType;
        this.name = name;
        this.column = column;
    }

    @Override public Class<E> entityType() { return entityType; }
    @Override public String name() { return name; }
    @Override public String column() { return column; }
    @Override public Class<Boolean> type() { return Boolean.class; }

    public static <E> BooleanField<E> of(Class<E> entityType, String name, String column) {
        return new BooleanField<>(entityType, name, column);
    }
}
