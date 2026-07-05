package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as an optimistic-lock version counter.
 *
 * <p>When an entity has a {@code @Version} field, {@code JdbcRepository}
 * appends {@code WHERE version = ?} to UPDATE and DELETE statements and
 * increments the version on successful update. If the affected row count is
 * zero, an {@link com.holo.framework.horm.core.OptimisticLockException} is
 * thrown.
 *
 * <p>Supported types: {@code int}, {@code Integer}, {@code long}, {@code Long}.
 * At most one {@code @Version} field per entity.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface Version {
}
