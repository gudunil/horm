package com.holo.framework.horm.starter;

import com.holo.framework.horm.meta.annotation.Transactional;

/**
 * Test service for M8.7 TransactionProxyBuilder integration testing.
 * APT will generate {@code TransactionProxyFactory_Service_TransactionalProxyFactory}
 * and {@code TransactionProxyFactory_Service_TransactionalProxy} during compilation.
 */
public class TransactionProxyTestService {

    private int callCount;

    public int getCallCount() {
        return callCount;
    }

    @Transactional
    public String execute(String input) {
        callCount++;
        return "executed:" + input;
    }

    @Transactional(readOnly = true)
    public String query() {
        callCount++;
        return "queried";
    }

    public String noTxMethod() {
        return "no-tx";
    }
}
