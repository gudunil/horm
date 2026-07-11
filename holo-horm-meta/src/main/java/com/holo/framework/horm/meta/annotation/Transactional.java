package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares transaction boundaries for a method or class.
 *
 * <p>M4 provides APT-based metadata scanning: the annotation processor
 * reads {@code @Transactional} and generates a transaction metadata index
 * ({@code META-INF/horm/transactions.idx}) for runtime lookup. Automatic
 * method interception (AOP proxy) is deferred to M8 (Spring Boot Starter).
 *
 * <p>In the interim, use the programmatic API
 * {@link com.holo.framework.horm.core.Horm#tx} for transaction demarcation.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Transactional {

    /** Transaction propagation behavior. Defaults to {@link Propagation#REQUIRED}. */
    Propagation propagation() default Propagation.REQUIRED;

    /** Transaction isolation level. Defaults to {@link Isolation#DEFAULT}. */
    Isolation isolation() default Isolation.DEFAULT;

    /** Timeout in seconds; {@code -1} means use the data source default. */
    int timeout() default -1;

    /** Whether the transaction is read-only. */
    boolean readOnly() default false;

    /** Exception types that trigger rollback. Empty means all runtime exceptions. */
    Class<? extends Throwable>[] rollbackFor() default {};

    /** Exception types that do <em>not</em> trigger rollback. */
    Class<? extends Throwable>[] noRollbackFor() default {};
}
