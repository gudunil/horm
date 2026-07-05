package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies physical table metadata. Optional companion to {@link Entity} —
 * when only {@link Entity#table()} is needed, {@code @Table} can be omitted.
 *
 * <p>Use {@code @Table} when you need to declare schema or catalog separately
 * from the table name, or when you want to keep the {@code @Entity} attribute
 * focused on logical concerns.
 *
 * @see Entity
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Table {

    /** Physical table name in the data source. */
    String name() default "";

    /** Schema (database) name, for data sources that support schemas. */
    String schema() default "";

    /** Catalog name, for data sources that support catalogs. */
    String catalog() default "";
}
