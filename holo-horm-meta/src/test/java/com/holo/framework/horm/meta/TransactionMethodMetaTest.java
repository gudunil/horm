package com.holo.framework.horm.meta;

import com.holo.framework.horm.meta.annotation.Isolation;
import com.holo.framework.horm.meta.annotation.Propagation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionMethodMetaTest {

    @Test
    void constructorAssignsAllFields() {
        TransactionMethodMeta meta = new TransactionMethodMeta(
            "createUser", Propagation.REQUIRED, Isolation.READ_COMMITTED, 30, true);

        assertThat(meta.methodName()).isEqualTo("createUser");
        assertThat(meta.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(meta.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(meta.timeout()).isEqualTo(30);
        assertThat(meta.readOnly()).isTrue();
    }

    @Test
    void methodNameReturnsValue() {
        TransactionMethodMeta meta = new TransactionMethodMeta(
            "findUser", Propagation.REQUIRED, Isolation.DEFAULT, 0, false);

        assertThat(meta.methodName()).isEqualTo("findUser");
    }

    @Test
    void propagationReturnsValue() {
        TransactionMethodMeta meta = new TransactionMethodMeta(
            "save", Propagation.REQUIRES_NEW, Isolation.SERIALIZABLE, 10, false);

        assertThat(meta.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void isolationReturnsValue() {
        TransactionMethodMeta meta = new TransactionMethodMeta(
            "save", Propagation.REQUIRED, Isolation.REPEATABLE_READ, 0, false);

        assertThat(meta.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void timeoutReturnsValue() {
        TransactionMethodMeta meta = new TransactionMethodMeta(
            "save", Propagation.REQUIRED, Isolation.DEFAULT, 60, false);

        assertThat(meta.timeout()).isEqualTo(60);
    }

    @Test
    void readOnlyReturnsFalseWhenNotReadOnly() {
        TransactionMethodMeta meta = new TransactionMethodMeta(
            "save", Propagation.REQUIRED, Isolation.DEFAULT, 0, false);

        assertThat(meta.readOnly()).isFalse();
    }

    @Test
    void toStringContainsAllFields() {
        TransactionMethodMeta meta = new TransactionMethodMeta(
            "createUser", Propagation.REQUIRED, Isolation.READ_COMMITTED, 30, true);

        String result = meta.toString();
        assertThat(result).isEqualTo(
            "TransactionMethodMeta{createUser, REQUIRED, READ_COMMITTED, timeout=30, readOnly=true, rollbackFor=[], noRollbackFor=[]}");
    }
}
