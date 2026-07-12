package com.holo.framework.horm.meta;

/**
 * SPI interface for APT-generated transaction proxy factories.
 *
 * <p>For each class containing {@code @Transactional} methods, the APT
 * processor generates:
 * <ul>
 *   <li>A proxy subclass ({@code Xxx$TransactionalProxy}) that inlines
 *       transaction interception logic for each transactional method</li>
 *   <li>A factory class ({@code Xxx$TransactionalProxyFactory}) implementing
 *       this interface, which the Spring Boot {@code BeanPostProcessor}
 *       discovers via {@link java.util.ServiceLoader}</li>
 * </ul>
 *
 * <p>This eliminates runtime reflection in the transaction path:
 * <ul>
 *   <li>No {@code Method.invoke()} — the proxy calls the delegate directly</li>
 *   <li>No {@code Proxy.newProxyInstance()} — the proxy is a compile-time
 *       generated subclass</li>
 *   <li>No {@code getDeclaredMethods()} — the APT already knows which methods
 *       are transactional at compile time</li>
 * </ul>
 *
 * @see TransactionMethodMeta
 */
public interface TransactionProxyFactory {

    /**
     * Creates a transactional proxy wrapping the given delegate.
     *
     * <p>The returned proxy overrides all {@code @Transactional} methods
     * with inlined transaction boundary logic (begin/commit/rollback).
     * Non-transactional methods delegate directly to the original instance.
     *
     * @param delegate the original bean instance to wrap
     * @param context  the current HORM runtime context, used by the proxy
     *                 to obtain transactions via {@code TransactionManager}
     * @return a proxy instance that intercepts transactional method calls
     */
    Object create(Object delegate, Object context);

    /**
     * Returns the class that this factory creates proxies for.
     *
     * <p>The {@code BeanPostProcessor} uses this method to match factories
     * to Spring beans by class identity.
     *
     * @return the target class (e.g. {@code OrderService.class})
     */
    Class<?> targetType();
}
