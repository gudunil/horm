package com.holo.framework.horm.meta.query;

import java.time.Instant;

public final class InstantField<E> implements ComparableField<E, Instant> {
    private final Class<E> entityType;
    private final String name;
    private final String column;

    private InstantField(Class<E> entityType, String name, String column) {
        this.entityType = entityType;
        this.name = name;
        this.column = column;
    }

    @Override public Class<E> entityType() { return entityType; }
    @Override public String name() { return name; }
    @Override public String column() { return column; }
    @Override public Class<Instant> type() { return Instant.class; }

    public static <E> InstantField<E> of(Class<E> entityType, String name, String column) {
        return new InstantField<>(entityType, name, column);
    }
}
