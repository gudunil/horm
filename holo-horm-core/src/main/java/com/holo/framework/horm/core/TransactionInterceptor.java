package com.holo.framework.horm.core;

import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.meta.TransactionMethodMeta;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;

/**
 * Runtime transaction interceptor that applies transactional semantics
 * based on APT-generated {@link TransactionMethodMeta} metadata.
 *
 * <p>M8.7 改造：此类作为 APT 代理的降级路径保留。当目标类无法生成 APT 代理
 * （final 类、第三方类）时，使用 {@link MethodBridgeFactory} 替代
 * {@link Method#invoke}，减少反射开销。
 *
 * <p>主路径（APT 代理子类）不使用此类——代理子类直接内联事务边界逻辑，
 * 零反射调用。此类的 {@link #createProxy(Object)} 方法仅用于无法生成 APT
 * 代理的降级场景。
 */
public final class TransactionInterceptor {

    private TransactionInterceptor() {
    }

    /**
     * Creates a JDK dynamic proxy for the given target that intercepts
     * transactional method calls. This is the fallback path for classes
     * that cannot have an APT-generated proxy subclass (e.g. final classes).
     *
     * <p>The proxy uses {@link MethodBridgeFactory} internally to reduce
     * reflection overhead compared to raw {@link Method#invoke}.
     *
     * @param target the target bean to wrap
     * @return a JDK dynamic proxy that intercepts transactional methods
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(T target) {
        Class<?> targetClass = target.getClass();
        Class<?>[] interfaces = targetClass.getInterfaces();
        if (interfaces.length == 0) {
            // Cannot create JDK proxy without interfaces
            return target;
        }
        return (T) Proxy.newProxyInstance(
            targetClass.getClassLoader(),
            interfaces,
            (proxy, method, args) -> invoke(target, method, args)
        );
    }

    /**
     * Intercepts a method invocation and applies transactional semantics
     * if metadata is registered for the target class and method.
     *
     * <p>If transaction metadata is found, the method is invoked within a
     * transaction boundary using {@link MethodBridgeFactory} for reduced
     * reflection overhead.
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
                TransactionManager.commit(status);
            }
            rethrow(ex);
            return null;
        } finally {
            popAndResume(dataSourceName, status);
        }
    }

    /**
     * Invokes the target method using {@link MethodBridgeFactory} for
     * reduced reflection overhead compared to raw {@link Method#invoke}.
     */
    private static Object invokeTarget(Object target, Method method, Object[] args) throws Exception {
        try {
            return MethodBridgeFactory.bridge(method).invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new TransactionException("Method invocation threw non-Exception Throwable", cause);
        } catch (Throwable t) {
            if (t instanceof Exception ex) {
                throw ex;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new TransactionException("Method bridge invocation failed", t);
        }
    }

    private static boolean shouldRollback(Throwable ex, TransactionMethodMeta meta) {
        return meta.shouldRollback(ex);
    }

    private static void rethrow(Throwable ex) throws Exception {
        if (ex instanceof Exception exception) {
            throw exception;
        }
        if (ex instanceof Error error) {
            throw error;
        }
        throw new TransactionException("Unexpected Throwable", ex);
    }

    private static void popAndResume(String dataSourceName, TransactionStatus status) {
        TransactionManager.popAndResume(dataSourceName, status);
    }
}
