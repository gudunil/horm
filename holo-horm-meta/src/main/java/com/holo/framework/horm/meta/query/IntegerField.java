package com.holo.framework.horm.meta.query;

public final class IntegerField<E> implements TypedField<E, Integer> {
    private final Class<E> entityType;
    private final String name;
    private final String column;

    private IntegerField(Class<E> entityType, String name, String column) {
        this.entityType = entityType;
        this.name = name;
        this.column = column;
    }

    @Override public Class<E> entityType() { return entityType; }
    @Override public String name() { return name; }
    @Override public String column() { return column; }
    @Override public Class<Integer> type() { return Integer.class; }

    public static <E> IntegerField<E> of(Class<E> entityType, String name, String column) {
        return new IntegerField<>(entityType, name, column);
    }
}
