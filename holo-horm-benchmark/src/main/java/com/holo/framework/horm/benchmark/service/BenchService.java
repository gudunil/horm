package com.holo.framework.horm.benchmark.service;

import com.holo.framework.horm.meta.annotation.Transactional;

/**
 * Target service for M8.7 transaction proxy benchmarking.
 * APT will generate a proxy for this class during compilation.
 */
public class BenchService {
    @Transactional
    public String execute(String input) {
        return "result:" + input;
    }
}
