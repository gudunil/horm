package com.holo.framework.horm.meta.query;

public final class EnumField<E, T extends Enum<T>> implements TypedField<E, T> {
    private final Class<E> entityType;
    private final String name;
    private final String column;
    private final Class<T> enumType;

    private EnumField(Class<E> entityType, String name, String column, Class<T> enumType) {
        this.entityType = entityType;
        this.name = name;
        this.column = column;
        this.enumType = enumType;
    }

    @Override public Class<E> entityType() { return entityType; }
    @Override public String name() { return name; }
    @Override public String column() { return column; }
    @Override public Class<T> type() { return enumType; }

    public static <E, T extends Enum<T>> EnumField<E, T> of(
            Class<E> entityType, String name, String column, Class<T> enumType) {
        return new EnumField<>(entityType, name, column, enumType);
    }
}
