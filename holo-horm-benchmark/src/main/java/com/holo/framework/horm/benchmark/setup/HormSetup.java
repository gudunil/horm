package com.holo.framework.horm.benchmark.setup;

import java.io.IOException;
import java.sql.SQLException;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.holo.framework.horm.benchmark.BenchDataSourceProvider;
import com.holo.framework.horm.benchmark.entity.BenchUser;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;

/**
 * Initializes the HORM runtime: installs a {@link HormContext} backed by
 * {@link BenchDataSourceProvider} and triggers APT-generated metadata loading
 * for {@link BenchUser}.
 */
@State(Scope.Benchmark)
public class HormSetup {

    public BenchDataSourceProvider provider;

    @org.openjdk.jmh.annotations.Setup(Level.Trial)
    public void setUp() throws SQLException, IOException {
        BenchmarkEnv.ensureInitialized();
        provider = new BenchDataSourceProvider();
        Horm.install(provider);
        // Touch the entity class so EntityMetaRegistry warms up.
        EntityMetaRegistry.lookup(BenchUser.class);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        HormContext.install(null);
    }
}
