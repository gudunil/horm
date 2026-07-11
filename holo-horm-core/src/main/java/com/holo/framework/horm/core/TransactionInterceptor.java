package com.holo.framework.horm.core;

import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.meta.TransactionMethodMeta;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Runtime transaction interceptor that applies transactional semantics
 * based on APT-generated {@link TransactionMethodMeta} metadata.
 *
 * <p>This class is the runtime counterpart to the compile-time
 * {@code TransactionAdvisorBuilder}. When a method annotated with
 * {@code @Transactional} is invoked through a proxy (e.g. Spring AOP),
 * the interceptor:
 * <ol>
 *   <li>Looks up the transaction metadata from {@link TransactionAdvisorRegistry}</li>
 *   <li>If found, constructs a {@link TransactionDefinition} and begins a transaction</li>
 *   <li>Invokes the target method</li>
 *   <li>Commits on success, rolls back on exception (subject to rollback rules)</li>
 * </ol>
 *
 * <p>If no transaction metadata is registered for the method, the interceptor
 * simply invokes the target method directly without transactional wrapping.
 *
 * <p>Rollback rules are evaluated by {@link TransactionMethodMeta#shouldRollback(Throwable)}:
 * <ul>
 *   <li>{@code noRollbackFor} exceptions never trigger rollback</li>
 *   <li>If {@code rollbackFor} is specified, only matching exceptions trigger rollback</li>
 *   <li>By default, {@link RuntimeException} (and subclasses) trigger rollback</li>
 * </ul>
 *
 * <p>This class is intended for use by the Spring Boot Starter (M8) or other
 * AOP frameworks. It is not called directly by application code.
 */
public final class TransactionInterceptor {

    private TransactionInterceptor() {
    }

    /**
     * Intercepts a method invocation and applies transactional semantics
     * if metadata is registered for the target class and method.
     *
     * <p>If transaction metadata is found, the method is invoked within a
     * transaction boundary. The transaction is committed on successful return;
     * it is rolled back if the method throws an exception that matches the
     * rollback rules.
     *
     * <p>If no metadata is found, the method is invoked directly without
     * transactional wrapping.
     *
     * @param target the target object on which the method is invoked
     * @param method the method being invoked
     * @param args   the method arguments
     * @return the result of the method invocation
     * @throws Exception if the method throws an exception (after rollback if applicable)
     */
    public static Object invoke(Object target, Method method, Object[] args) throws Exception {
        Optional<TransactionMethodMeta> metaOpt =
            TransactionAdvisorRegistry.lookup(target.getClass(), method.getName());

        if (metaOpt.isEmpty()) {
            // No transaction metadata — invoke directly
            return invokeTarget(target, method, args);
        }

        TransactionMethodMeta meta = metaOpt.get();
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(meta.propagation())
            .isolation(meta.isolation())
            .timeout(meta.timeout())
            .readOnly(meta.readOnly())
            .build();

        HormContext ctx = HormContext.current();
        String dataSourceName = DataSourceRegistry.DEFAULT_NAME;

        TransactionStatus status = TransactionManager.begin(ctx, dataSourceName, def);

        try {
            Object result = invokeTarget(target, method, args);
            TransactionManager.commit(status);
            return result;
        } catch (Throwable ex) {
            if (shouldRollback(ex, meta)) {
                TransactionManager.rollback(status);
            } else {
                // Exception does not match rollback rules — commit anyway
                TransactionManager.commit(status);
            }
            // Re-throw the original exception
            rethrow(ex);
            return null; // unreachable
        } finally {
            popAndResume(dataSourceName, status);
        }
    }

    /**
     * Invokes the target method, unwrapping {@link InvocationTargetException}
     * to expose the underlying exception.
     */
    private static Object invokeTarget(Object target, Method method, Object[] args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new TransactionException("Method invocation threw non-Exception Throwable", cause);
        }
    }

    /**
     * Determines whether the given exception should trigger a rollback
     * according to the transaction metadata.
     */
    private static boolean shouldRollback(Throwable ex, TransactionMethodMeta meta) {
        return meta.shouldRollback(ex);
    }

    /**
     * Re-throws the exception, preserving its original type.
     * Checked exceptions are wrapped in {@link TransactionException} if they
     * are not declared by the method signature.
     */
    private static void rethrow(Throwable ex) throws Exception {
        if (ex instanceof Exception exception) {
            throw exception;
        }
        if (ex instanceof Error error) {
            throw error;
        }
        throw new TransactionException("Unexpected Throwable", ex);
    }

    /**
     * Pops the transaction status from the stack and resumes any suspended
     * transaction (for REQUIRES_NEW propagation).
     *
     * <p>Delegates to {@link TransactionManager#popAndResume(String, TransactionStatus)}
     * which is package-private for this purpose.
     */
    private static void popAndResume(String dataSourceName, TransactionStatus status) {
        TransactionManager.popAndResume(dataSourceName, status);
    }
}
