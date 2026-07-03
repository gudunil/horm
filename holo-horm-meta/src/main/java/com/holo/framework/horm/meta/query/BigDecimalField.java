package com.holo.framework.horm.meta.query;

import java.math.BigDecimal;

public final class BigDecimalField<E> implements TypedField<E, BigDecimal> {
    private final Class<E> entityType;
    private final String name;
    private final String column;

    private BigDecimalField(Class<E> entityType, String name, String column) {
        this.entityType = entityType;
        this.name = name;
        this.column = column;
    }

    @Override public Class<E> entityType() { return entityType; }
    @Override public String name() { return name; }
    @Override public String column() { return column; }
    @Override public Class<BigDecimal> type() { return BigDecimal.class; }

    public static <E> BigDecimalField<E> of(Class<E> entityType, String name, String column) {
        return new BigDecimalField<>(entityType, name, column);
    }
}
