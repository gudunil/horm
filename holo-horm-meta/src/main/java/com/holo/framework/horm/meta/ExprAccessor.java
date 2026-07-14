package com.holo.framework.horm.meta;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Routes typed value access on a {@link Row} based on the expression's
 * {@code javaType()}. Uses if-else chain (AOT compatible, no reflection).
 */
public final class ExprAccessor {

    private ExprAccessor() {}

    @SuppressWarnings("unchecked")
    public static <T> T get(Row row, String column, Class<T> type) {
        if (type == Long.class) return (T) row.getLong(column);
        if (type == Integer.class) return (T) row.getInteger(column);
        if (type == String.class) return (T) row.getString(column);
        if (type == BigDecimal.class) return (T) row.getBigDecimal(column);
        if (type == Instant.class) return (T) row.getInstant(column);
        if (type == Boolean.class) return (T) row.getBoolean(column);
        if (type.isEnum()) return (T) row.getEnum(column, (Class<? extends Enum>) type);
        // Fallback: raw Object
        return (T) row.get(column);
    }
}
