package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.TransactionManager;
import com.holo.framework.horm.meta.TransactionProxyFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for M8.7 zero-reflection transaction proxy.
 *
 * <p>Verifies that the APT-generated {@link TransactionProxyFactory} is
 * discoverable via {@link ServiceLoader} and that the
 * {@link HormTransactionalBeanPostProcessor} correctly uses it to create
 * transaction proxies.
 */
class TransactionProxyIntegrationTest {

    private HormTransactionalBeanPostProcessor processor;
    private HormContext ctx;

    @BeforeEach
    void setUp() {
        processor = new HormTransactionalBeanPostProcessor();
        ctx = new HormContext(new TestDataSourceProvider());
        HormContext.install(ctx);
    }

    @AfterEach
    void tearDown() {
        TransactionManager.clear();
        HormContext.install(null);
    }

    @Test
    void serviceLoaderDiscoversAptGeneratedFactory() {
        boolean found = false;
        for (TransactionProxyFactory factory : ServiceLoader.load(TransactionProxyFactory.class,
                Thread.currentThread().getContextClassLoader())) {
            if (factory.targetType() == TransactionProxyTestService.class) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void beanPostProcessorCreatesProxyViaFactory() {
        TransactionProxyTestService bean = new TransactionProxyTestService();
        Object result = processor.postProcessAfterInitialization(bean, "testService");

        assertThat(result).isNotSameAs(bean);
        assertThat(result).isInstanceOf(TransactionProxyTestService.class);
    }

    @Test
    void proxyDelegatesNonTransactionalMethod() {
        TransactionProxyTestService bean = new TransactionProxyTestService();
        TransactionProxyTestService proxy =
                (TransactionProxyTestService) processor.postProcessAfterInitialization(bean, "testService");

        assertThat(proxy.noTxMethod()).isEqualTo("no-tx");
    }

    @Test
    void proxyTransactionalMethodExecutesSuccessfully() {
        TransactionProxyTestService bean = new TransactionProxyTestService();
        TransactionProxyTestService proxy =
                (TransactionProxyTestService) processor.postProcessAfterInitialization(bean, "testService");

        String result = proxy.execute("hello");
        assertThat(result).isEqualTo("executed:hello");
        assertThat(proxy.getCallCount()).isEqualTo(1);
    }

    @Test
    void proxyTransactionalReadOnlyMethodExecutesSuccessfully() {
        TransactionProxyTestService bean = new TransactionProxyTestService();
        TransactionProxyTestService proxy =
                (TransactionProxyTestService) processor.postProcessAfterInitialization(bean, "testService");

        String result = proxy.query();
        assertThat(result).isEqualTo("queried");
        assertThat(proxy.getCallCount()).isEqualTo(1);
    }

    @Test
    void proxyIsSubclassNotJdkProxy() {
        TransactionProxyTestService bean = new TransactionProxyTestService();
        Object proxy = processor.postProcessAfterInitialization(bean, "testService");

        assertThat(proxy.getClass().getName()).contains("_TransactionalProxy");
    }
}
