package com.holo.framework.horm.benchmark.setup;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.holo.framework.horm.benchmark.entity.BenchUser;
import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;

/**
 * Initializes the HORM runtime: installs a {@link HormContext} backed by
 * the shared {@link BenchmarkEnv#getSharedDataSource()} (HikariCP) and
 * triggers APT-generated metadata loading for {@link BenchUser}.
 */
@State(Scope.Benchmark)
public class HormSetup {

    public DataSourceProvider provider;

    @org.openjdk.jmh.annotations.Setup(Level.Trial)
    public void setUp() throws SQLException, IOException {
        BenchmarkEnv.ensureInitialized();
        DataSource ds = BenchmarkEnv.getSharedDataSource();
        provider = new DataSourceAdapter(ds);
        Horm.install(provider);
        // Touch the entity class so EntityMetaRegistry warms up.
        EntityMetaRegistry.lookup(BenchUser.class);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        HormContext.install(null);
    }

    /**
     * Adapts a {@link DataSource} to HORM's {@link DataSourceProvider} interface.
     */
    private static class DataSourceAdapter implements DataSourceProvider {
        private final DataSource dataSource;

        DataSourceAdapter(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void releaseConnection(Connection connection) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }
}
