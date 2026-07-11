package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a field to a data source column. Applies to non-{@link Id} fields; an
 * {@code @Id} field may also carry {@code @Column} to override the column name.
 *
 * <p>If {@link #name()} is omitted, the framework applies a naming convention
 * (camelCase → snake_case) to derive the column name from the field name.
 *
 * <p>Example:
 * <pre>{@code
 * @Column(name = "created_at", nullable = false, length = 128)
 * private Instant createdAt;
 * }</pre>
 *
 * @see Entity
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {

    /** Physical column name. Empty means derive from field name via naming convention. */
    String name() default "";

    /** Whether the column allows NULL. Defaults to {@code true}. */
    boolean nullable() default true;

    /** Whether the column value must be unique across rows. */
    boolean unique() default false;

    /** Maximum length for string-valued columns. Ignored for other types. */
    int length() default 255;

    /** Precision for decimal columns. Ignored for non-decimal types. */
    int precision() default 0;

    /** Scale for decimal columns. Ignored for non-decimal types. */
    int scale() default 0;

    /** SQL DDL definition fragment, e.g. {@code "VARCHAR(128) NOT NULL"}. */
    String columnDefinition() default "";

    /** Whether the column is included in INSERT statements. */
    boolean insertable() default true;

    /** Whether the column is included in UPDATE statements. */
    boolean updatable() default true;
}
