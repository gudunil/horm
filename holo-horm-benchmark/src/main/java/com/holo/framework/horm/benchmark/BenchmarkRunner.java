package com.holo.framework.horm.benchmark;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH entry point for the HORM benchmark suite.
 *
 * <p>Run with no arguments to execute all benchmarks, or pass regex patterns
 * to select a subset:
 * <pre>
 *   java -jar holo-horm-benchmark.jar                        # all benchmarks
 *   java -jar holo-horm-benchmark.jar FindById              # findById only
 *   java -jar holo-horm-benchmark.jar Cache Crud            # cache + crud
 * </pre>
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        OptionsBuilder builder = new OptionsBuilder();
        if (args.length == 0) {
            builder.include(FindByIdBenchmark.class.getSimpleName())
                   .include(CrudBenchmark.class.getSimpleName())
                   .include(QueryBenchmark.class.getSimpleName())
                   .include(CacheBenchmark.class.getSimpleName())
                   .include(BatchInsertBenchmark.class.getSimpleName())
                   .include(FindManyBenchmark.class.getSimpleName())
                   .include(TransactionProxyBenchmark.class.getSimpleName());
        } else {
            for (String pattern : args) {
                builder.include(pattern);
            }
        }
        Options opt = builder.build();
        new Runner(opt).run();
    }
}
