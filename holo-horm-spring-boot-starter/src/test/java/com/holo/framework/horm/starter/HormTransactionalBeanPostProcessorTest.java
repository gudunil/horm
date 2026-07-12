package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.TransactionAdvisorRegistry;
import com.holo.framework.horm.meta.TransactionMethodMeta;
import com.holo.framework.horm.meta.annotation.Isolation;
import com.holo.framework.horm.meta.annotation.Propagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HormTransactionalBeanPostProcessor}.
 */
class HormTransactionalBeanPostProcessorTest {

    private HormTransactionalBeanPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new HormTransactionalBeanPostProcessor();
        TransactionAdvisorRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        TransactionAdvisorRegistry.clear();
    }

    @Test
    void beanWithoutTransactionalAnnotationNotProxied() {
        Object bean = new NonTransactionalService();
        Object result = processor.postProcessAfterInitialization(bean, "nonTransactionalService");
        assertThat(result).isSameAs(bean);
    }

    @Test
    void beanWithInterfaceAndRegisteredMetaProxied() {
        // 手动注册事务元数据，模拟 APT 生成的结果
        TransactionMethodMeta meta = new TransactionMethodMeta(
            "execute", Propagation.REQUIRED, Isolation.DEFAULT, -1, false,
            List.of(), List.of()
        );
        TransactionAdvisorRegistry.registerManual(ServiceWithInterfaceImpl.class, "execute", meta);

        Object bean = new ServiceWithInterfaceImpl();
        Object result = processor.postProcessAfterInitialization(bean, "serviceWithInterface");

        // 由于 ServiceWithInterfaceImpl 实现了接口，应该被代理
        assertThat(result).isNotSameAs(bean);
        assertThat(result).isInstanceOf(ServiceWithInterface.class);
    }

    @Test
    void beanWithoutInterfacesNotProxied() {
        // 即使注册了元数据，没有接口的 bean 也不会被代理
        TransactionMethodMeta meta = new TransactionMethodMeta(
            "doWork", Propagation.REQUIRED, Isolation.DEFAULT, -1, false,
            List.of(), List.of()
        );
        TransactionAdvisorRegistry.registerManual(NoInterfaceService.class, "doWork", meta);

        Object bean = new NoInterfaceService();
        Object result = processor.postProcessAfterInitialization(bean, "noInterfaceService");

        assertThat(result).isSameAs(bean);
    }

    // 测试用的服务类，不使用 @Transactional 注解避免 APT 处理冲突
    static class NonTransactionalService {
        public void doWork() {}
    }

    static class NoInterfaceService {
        public void doWork() {}
    }

    interface ServiceWithInterface {
        void execute();
    }

    static class ServiceWithInterfaceImpl implements ServiceWithInterface {
        @Override
        public void execute() {}
    }
}
