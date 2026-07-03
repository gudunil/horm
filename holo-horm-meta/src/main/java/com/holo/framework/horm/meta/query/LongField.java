package com.holo.framework.horm.meta.query;

public final class LongField<E> implements TypedField<E, Long> {
    private final Class<E> entityType;
    private final String name;
    private final String column;

    private LongField(Class<E> entityType, String name, String column) {
        this.entityType = entityType;
        this.name = name;
        this.column = column;
    }

    @Override public Class<E> entityType() { return entityType; }
    @Override public String name() { return name; }
    @Override public String column() { return column; }
    @Override public Class<Long> type() { return Long.class; }

    public static <E> LongField<E> of(Class<E> entityType, String name, String column) {
        return new LongField<>(entityType, name, column);
    }
}
