package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a HORM entity whose state is persisted to a data source.
 *
 * <p>At compile time the {@code holo-horm-meta} annotation processor scans for
 * {@code @Entity} types and generates three companion classes:
 * <ul>
 *   <li>{@code XxxMeta} — static field/relation metadata and the {@code entityMeta()} factory</li>
 *   <li>{@code XxxMapper} — zero-reflection {@code map(Row)} / {@code toRow(entity)} implementation</li>
 *   <li>{@code XxxQueryMeta} — type-safe {@code TypedField} constants for the query DSL</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * @Entity(table = "users", dataSource = "mysql-primary")
 * public class User extends Model<User> { ... }
 * }</pre>
 *
 * @see Table
 * @see Id
 * @see Column
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Entity {

    /** Logical table name. If omitted, the framework derives a name from the class. */
    String table() default "";

    /** Name of the data source this entity binds to. Empty means the default data source. */
    String dataSource() default "";

    /** Schema name for data sources that support schemas (e.g. PostgreSQL). */
    String schema() default "";
}
