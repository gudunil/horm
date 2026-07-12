package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.TransactionAdvisorRegistry;
import com.holo.framework.horm.core.TransactionInterceptor;
import com.holo.framework.horm.meta.TransactionProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring Bean 后置处理器，用于自动为标注了 {@code @Transactional} 注解的 Bean 创建事务代理。
 *
 * <p>M8.7 改造：优先使用 APT 编译期生成的代理子类（通过 {@link TransactionProxyFactory}
 * SPI 发现），消除运行时反射。如果 APT 代理不可用（final 类/第三方类），降级到
 * {@link TransactionInterceptor} 运行时拦截。
 *
 * <p>此改造消除了以下反射调用：
 * <ul>
 *   <li>{@code Proxy.newProxyInstance()} — 替换为 APT 代理子类实例化</li>
 *   <li>{@code getDeclaredMethods()} / {@code isAnnotationPresent()} — 替换为
 *       ServiceLoader 发现 + 代理工厂匹配</li>
 * </ul>
 */
public class HormTransactionalBeanPostProcessor implements BeanPostProcessor {

    private final Map<Class<?>, TransactionProxyFactory> factoryCache = new ConcurrentHashMap<>();
    private volatile boolean serviceLoaderExhausted = false;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> beanClass = bean.getClass();

        // Try APT-generated proxy factory first (zero-reflection path)
        TransactionProxyFactory factory = factoryCache.computeIfAbsent(beanClass, this::findFactory);
        if (factory != null) {
            try {
                return factory.create(bean, HormContext.current());
            } catch (Exception e) {
                // Factory failed; fall through to runtime interceptor
            }
        }

        // Fallback: check if transaction metadata exists and use runtime interceptor
        if (!TransactionAdvisorRegistry.isTransactional(beanClass)) {
            return bean;
        }

        // Runtime interception path (reflection-based, for classes without APT proxy)
        return TransactionInterceptor.createProxy(bean);
    }

    private TransactionProxyFactory findFactory(Class<?> beanClass) {
        for (TransactionProxyFactory f : loadFactories()) {
            if (f.targetType().equals(beanClass)) {
                return f;
            }
        }
        return null;
    }

    private Iterable<TransactionProxyFactory> loadFactories() {
        return ServiceLoader.load(TransactionProxyFactory.class,
            Thread.currentThread().getContextClassLoader());
    }
}
