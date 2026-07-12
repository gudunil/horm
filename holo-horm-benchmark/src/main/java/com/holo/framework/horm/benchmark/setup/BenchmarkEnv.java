package com.holo.framework.horm.benchmark.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.holo.framework.horm.benchmark.BenchDataSourceProvider;

/**
 * Shared H2 in-memory database bootstrap for all JMH benchmarks.
 *
 * <p>Initializes the {@code bench_users} schema and pre-populates
 * {@link #PRE_FILL_COUNT} rows exactly once per JVM (the H2 memory DB
 * is shared via {@code DB_CLOSE_DELAY=-1}). Framework-specific setup
 * classes delegate to {@link #ensureInitialized()} so that whichever
 * benchmark runs first pays the one-time setup cost.
 */
public final class BenchmarkEnv {

    /** Number of rows pre-populated into {@code bench_users}. */
    public static final int PRE_FILL_COUNT = 1000;

    private static volatile boolean initialized = false;

    private BenchmarkEnv() {}

    /** Ensures the schema and seed data exist. Idempotent and thread-safe. */
    public static synchronized void ensureInitialized() throws SQLException, IOException {
        if (initialized) return;
        try (Connection conn = openConnection()) {
            executeSchema(conn);
            seedData(conn);
        }
        initialized = true;
    }

    /** Opens a fresh connection to the shared H2 memory database. */
    public static Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(BenchDataSourceProvider.JDBC_URL, "sa", "");
        conn.setAutoCommit(true);
        return conn;
    }

    /** Returns the ids of all pre-populated rows (1..{@link #PRE_FILL_COUNT}). */
    public static List<Long> seedIds() {
        List<Long> ids = new ArrayList<>(PRE_FILL_COUNT);
        for (long i = 1; i <= PRE_FILL_COUNT; i++) ids.add(i);
        return ids;
    }

    private static void executeSchema(Connection conn) throws IOException, SQLException {
        String sql;
        try (InputStream in = BenchmarkEnv.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new IOException("schema.sql not found on classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static void seedData(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM bench_users");
            rs.next();
            if (rs.getLong(1) > 0) return;
        }
        Instant now = Instant.now();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO bench_users (email, name, created_at) VALUES (?, ?, ?)")) {
            for (int i = 1; i <= PRE_FILL_COUNT; i++) {
                ps.setString(1, "user" + i + "@bench.com");
                ps.setString(2, "bench-" + i);
                ps.setObject(3, java.sql.Timestamp.from(now));
                ps.addBatch();
                if (i % 200 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
    }
}
