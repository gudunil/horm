package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures primary key value generation independently of {@link Id}. Useful
 * when {@link Id#strategy()} is left at the default but a different strategy
 * is desired, or to specify a sequence/generator name.
 *
 * <p>When present, this annotation takes precedence over {@link Id#strategy()}.
 *
 * @see Id
 * @see GenerationType
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GeneratedValue {

    /** Generation strategy. */
    GenerationType strategy() default GenerationType.IDENTITY;

    /** Generator name for {@link GenerationType#SEQUENCE} and {@link GenerationType#TABLE}. */
    String generator() default "";
}
