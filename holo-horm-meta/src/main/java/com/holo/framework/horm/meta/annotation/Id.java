package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the primary key field of an entity. Every {@code @Entity} must declare
 * exactly one {@code @Id} field; the APT processor reports a compile-time
 * error otherwise.
 *
 * <p>The generation strategy controls how the primary key value is assigned:
 * <ul>
 *   <li>{@link GenerationType#IDENTITY} — database auto-increment column (default)</li>
 *   <li>{@link GenerationType#AUTO} — framework picks an appropriate strategy</li>
 *   <li>{@link GenerationType#SEQUENCE} — database sequence (M2+)</li>
 *   <li>{@link GenerationType#TABLE} — separate generator table (M2+)</li>
 *   <li>{@link GenerationType#UUID} — client-side UUID string</li>
 *   <li>{@link GenerationType#MANUAL} — client-assigned primary key (M10+)</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * @Id(strategy = GenerationType.IDENTITY)
 * private Long id;
 * }</pre>
 *
 * @see GeneratedValue
 * @see GenerationType
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Id {

    /** Strategy used to generate primary key values. Defaults to {@link GenerationType#IDENTITY}. */
    GenerationType strategy() default GenerationType.IDENTITY;

    /** Sequence name when {@link #strategy()} is {@link GenerationType#SEQUENCE}. */
    String sequenceName() default "";
}
