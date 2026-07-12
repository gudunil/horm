package com.holo.framework.horm.benchmark;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

import com.holo.framework.horm.benchmark.entity.CachedBenchUser;
import com.holo.framework.horm.benchmark.setup.CachedHormSetup;
import com.holo.framework.horm.benchmark.setup.HormSetup;
import com.holo.framework.horm.core.Model;

/**
 * Measures the three read paths available to a {@code @Cached} HORM entity:
 * <ol>
 *   <li>{@code cacheHit} — L1 (Caffeine) contains the entry; no DB round-trip.</li>
 *   <li>{@code cacheMiss} — entry invalidated before each invocation; the chain
 *       falls through to the database and back-fills L1.</li>
 *   <li>{@code noCache} — no {@code CacheChain} installed on the context;
 *       every read goes straight to JDBC (baseline).</li>
 * </ol>
 *
 * <p>The cache-hit path is nano-second territory, so this benchmark uses
 * {@link TimeUnit#NANOSECONDS} and more iterations than the CRUD benchmarks.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms256m", "-Xmx256m"})
public class CacheBenchmark {

    private static final long LOOKUP_ID = 1L;

    @Benchmark
    public CachedBenchUser cacheHit(CachedHormSetup setup) {
        return Model.find(CachedBenchUser.class, LOOKUP_ID);
    }

    @Benchmark
    public CachedBenchUser cacheMiss(CachedHormSetup setup) {
        setup.invalidateLookupKey();
        return Model.find(CachedBenchUser.class, LOOKUP_ID);
    }

    @Benchmark
    public CachedBenchUser noCache(HormSetup setup) {
        return Model.find(CachedBenchUser.class, LOOKUP_ID);
    }
}
