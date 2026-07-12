package com.holo.framework.horm.benchmark.setup;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.holo.framework.horm.benchmark.BenchDataSourceProvider;
import com.holo.framework.horm.benchmark.entity.CachedBenchUser;
import com.holo.framework.horm.cache.CachePolicy;
import com.holo.framework.horm.cache.CaffeineCache;
import com.holo.framework.horm.cache.DefaultCacheChain;
import com.holo.framework.horm.cache.key.CacheKey;
import com.holo.framework.horm.cache.key.CacheKeyBuilder;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;

/**
 * Initializes a HORM runtime with a Caffeine-backed L1 {@link DefaultCacheChain}
 * installed on the {@link HormContext}. Used by {@code CacheBenchmark} to
 * measure cache-hit vs cache-miss vs no-cache read paths.
 */
@State(Scope.Benchmark)
public class CachedHormSetup {

    private static final long LOOKUP_ID = 1L;

    public DefaultCacheChain chain;
    private CacheKey lookupKey;

    @org.openjdk.jmh.annotations.Setup(Level.Trial)
    public void setUp() throws SQLException, IOException {
        BenchmarkEnv.ensureInitialized();
        CachePolicy policy = CachePolicy.builder()
            .ttl(Duration.ofMinutes(30))
            .maxEntries(10_000)
            .build();
        CaffeineCache l1 = new CaffeineCache("bench-l1", policy);
        chain = new DefaultCacheChain(l1);
        HormContext ctx = new HormContext(new BenchDataSourceProvider(), chain);
        HormContext.install(ctx);
        EntityMetaRegistry.lookup(CachedBenchUser.class);
        lookupKey = new CacheKeyBuilder()
            .entityType(CachedBenchUser.class)
            .idKey(LOOKUP_ID)
            .build();
    }

    /** Pre-populates the L1 cache so benchmark invocations are hits. */
    @org.openjdk.jmh.annotations.Setup(Level.Iteration)
    public void warmCache() {
        Model.find(CachedBenchUser.class, LOOKUP_ID);
    }

    /** Invalidates the lookup key so the next read is a cache miss. */
    public void invalidateLookupKey() {
        chain.invalidate(lookupKey);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        HormContext.install(null);
    }
}
