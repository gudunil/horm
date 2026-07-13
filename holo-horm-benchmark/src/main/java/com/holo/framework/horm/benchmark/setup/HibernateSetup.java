package com.holo.framework.horm.benchmark.setup;

import java.io.IOException;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.holo.framework.horm.benchmark.BenchDataSourceProvider;
import com.holo.framework.horm.benchmark.entity.HibernateBenchUser;

/**
 * Initializes Hibernate ORM 6.x with the shared HikariCP connection pool.
 *
 * <p>Uses the same {@link BenchmarkEnv#getSharedDataSource()} as other frameworks
 * to ensure fair performance comparisons.
 *
 * <p>Second-level cache and query cache are explicitly disabled so that
 * benchmarks measure raw ORM mapping overhead — not cache hits — which
 * keeps the comparison fair against HORM (whose cache benefits are
 * measured separately in {@code CacheBenchmark}).
 */
@State(Scope.Benchmark)
public class HibernateSetup {

    public SessionFactory sessionFactory;
    private StandardServiceRegistry registry;

    @org.openjdk.jmh.annotations.Setup(Level.Trial)
    public void setUp() throws SQLException, IOException {
        BenchmarkEnv.ensureInitialized();
        DataSource dataSource = BenchmarkEnv.getSharedDataSource();

        // Inject shared DataSource via ConnectionProvider
        ConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);

        registry = new StandardServiceRegistryBuilder()
            .addService(ConnectionProvider.class, connectionProvider)
            .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
            .applySetting("hibernate.hbm2ddl.auto", "none")
            .applySetting("hibernate.cache.use_second_level_cache", "false")
            .applySetting("hibernate.cache.use_query_cache", "false")
            .applySetting("hibernate.show_sql", "false")
            .build();

        Metadata metadata = new MetadataSources(registry)
            .addAnnotatedClass(HibernateBenchUser.class)
            .buildMetadata();

        sessionFactory = metadata.buildSessionFactory();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    /** Convenience: opens a session and finds a single row by id. */
    public HibernateBenchUser findById(long id) {
        try (Session session = sessionFactory.openSession()) {
            return session.find(HibernateBenchUser.class, id);
        }
    }

    /**
     * Custom ConnectionProvider that wraps a shared DataSource.
     * This allows Hibernate to use the same HikariCP pool as other frameworks.
     */
    @SuppressWarnings("serial")
    private static class DataSourceConnectionProvider extends UserSuppliedConnectionProviderImpl {
        private static final long serialVersionUID = 1L;
        private final DataSource dataSource;

        DataSourceConnectionProvider(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public java.sql.Connection getConnection() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void closeConnection(java.sql.Connection conn) throws SQLException {
            if (conn != null) {
                conn.close();
            }
        }

        @Override
        public boolean supportsAggressiveRelease() {
            return false;
        }
    }
}
