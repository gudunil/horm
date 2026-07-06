package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.TransactionInterceptor;
import com.holo.framework.horm.meta.annotation.Transactional;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * Spring Bean 后置处理器，用于自动为标注了 {@link Transactional} 注解的 Bean 创建事务代理。
 *
 * <p>此处理器在 Bean 初始化完成后检查 Bean 类是否标注了 {@code @Transactional} 注解
 * （类级别或方法级别）。如果存在注解且 Bean 实现了至少一个接口，则使用 JDK 动态代理
 * 包装该 Bean，代理调用 {@link TransactionInterceptor} 实现事务拦截。
 *
 * <p>注意：JDK 动态代理要求 Bean 必须实现至少一个接口。对于未实现接口的 Bean，
 * 此处理器不会创建代理，事务注解将被忽略。
 *
 * <p>事务元数据由 APT 在编译期生成并注册到 {@code TransactionAdvisorRegistry}，
 * 运行时 {@link TransactionInterceptor} 根据元数据决定事务行为。
 *
 * @author Holo Framework Team
 * @since 1.0.0
 */
public class HormTransactionalBeanPostProcessor implements BeanPostProcessor {

    /**
     * 在 Bean 初始化完成后检查是否需要创建事务代理。
     *
     * <p>如果 Bean 类标注了 {@code @Transactional} 注解（类级别或任意方法级别），
     * 且 Bean 实现了至少一个接口，则返回 JDK 动态代理；否则返回原始 Bean。
     *
     * @param bean     the new bean instance
     * @param beanName the name of the bean
     * @return 事务代理或原始 Bean
     * @throws BeansException in case of errors
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> beanClass = bean.getClass();

        if (!hasTransactionalAnnotation(beanClass)) {
            return bean;
        }

        Class<?>[] interfaces = beanClass.getInterfaces();
        if (interfaces.length == 0) {
            // JDK 动态代理要求至少一个接口；未实现接口的 Bean 无法代理
            return bean;
        }

        return Proxy.newProxyInstance(
            beanClass.getClassLoader(),
            interfaces,
            (proxy, method, args) -> TransactionInterceptor.invoke(bean, method, args)
        );
    }

    /**
     * 检查指定类是否标注了 {@code @Transactional} 注解。
     *
     * <p>检查范围包括类级别注解和所有声明方法的注解。
     *
     * @param clazz the class to check
     * @return true 如果类或任意方法标注了 {@code @Transactional}
     */
    private boolean hasTransactionalAnnotation(Class<?> clazz) {
        if (clazz.isAnnotationPresent(Transactional.class)) {
            return true;
        }
        return Arrays.stream(clazz.getDeclaredMethods())
            .anyMatch(m -> m.isAnnotationPresent(Transactional.class));
    }
}
