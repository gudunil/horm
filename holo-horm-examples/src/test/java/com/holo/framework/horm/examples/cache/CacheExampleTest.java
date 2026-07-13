package com.holo.framework.horm.examples.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存链示例测试。
 */
class CacheExampleTest {

    @Test
    void cacheExampleLoadsAndHits() throws Exception {
        CacheExample.Result result = CacheExample.runExample();

        assertThat(result.phoneId()).isNotNull();
        assertThat(result.padId()).isNotNull();
        assertThat(result.sizeAfterInvalidation()).isZero();
        assertThat(result.firstLoadSize()).isEqualTo(2);
        assertThat(result.sizeAfterFirstLoad()).isEqualTo(2);
        assertThat(result.secondLoadSize()).isEqualTo(2);
        assertThat(result.sizeAfterSecondLoad()).isEqualTo(2);
        assertThat(result.sameInstance()).isTrue();
    }
}
