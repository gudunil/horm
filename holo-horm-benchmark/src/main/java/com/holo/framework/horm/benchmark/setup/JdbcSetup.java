package com.holo.framework.horm.benchmark.setup;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import javax.sql.DataSource;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Hand-written JDBC environment backed by the shared HikariCP pool.
 * Serves as the baseline (zero ORM overhead) for all CRUD/query benchmarks.
 */
@State(Scope.Benchmark)
public class JdbcSetup {

    public DataSource dataSource;

    @org.openjdk.jmh.annotations.Setup(Level.Trial)
    public void setUp() throws SQLException, IOException {
        BenchmarkEnv.ensureInitialized();
        // Use the shared HikariCP pool (consistent with other frameworks)
        dataSource = BenchmarkEnv.getSharedDataSource();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // DataSource is shared and closed by BenchmarkEnv, no explicit close here.
    }

    /** Hand-rolled findById used by benchmarks. */
    public JdbcBenchUser findById(long id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, email, name, created_at FROM bench_users WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                JdbcBenchUser u = new JdbcBenchUser();
                u.id = rs.getLong(1);
                u.email = rs.getString(2);
                u.name = rs.getString(3);
                Instant ts = rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant();
                u.createdAt = ts;
                return u;
            }
        }
    }

    /** Plain DTO for JDBC benchmarks. */
    public static class JdbcBenchUser {
        public long id;
        public String email;
        public String name;
        public Instant createdAt;
    }
}
