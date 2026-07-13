package com.holo.framework.horm.benchmark;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

import com.holo.framework.horm.benchmark.entity.BenchUser;
import com.holo.framework.horm.benchmark.entity.HibernateBenchUser;
import com.holo.framework.horm.benchmark.entity.MybatisBenchUser;
import com.holo.framework.horm.benchmark.setup.HibernateSetup;
import com.holo.framework.horm.benchmark.setup.HormSetup;
import com.holo.framework.horm.benchmark.setup.JdbcSetup;
import com.holo.framework.horm.benchmark.setup.MybatisSetup;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.Model;

/**
 * Compares insert / update / delete latency across HORM, hand-written JDBC,
 * MyBatis and Hibernate.
 *
 * <p>Each insert uses a unique email derived from {@code System.nanoTime()} to
 * avoid constraint collisions. Update benchmarks load the pre-seeded row with
 * id=1, mutate its {@code name} field, and persist the change. Delete
 * benchmarks first insert a throwaway row, then delete it — keeping the row
 * count stable across iterations.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms256m", "-Xmx256m"})
public class CrudBenchmark {

    private static final long UPDATE_TARGET_ID = 1L;

    // ===== Insert =====

    @Benchmark
    public BenchUser hormInsert(HormSetup setup) {
        BenchUser u = new BenchUser();
        u.setEmail("horm-" + System.nanoTime() + "@bench.com");
        u.setName("horm-insert");
        u.setCreatedAt(Instant.now());
        // Use explicit transaction for fair comparison with MyBatis/Hibernate commit
        Horm.tx(() -> u.save());
        return u;
    }

    @Benchmark
    public long jdbcInsert(JdbcSetup setup) throws Exception {
        try (Connection conn = setup.dataSource.getConnection()) {
            conn.setAutoCommit(false);  // Explicit transaction for fair comparison
            try (PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bench_users (email, name, created_at) VALUES (?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "jdbc-" + System.nanoTime() + "@bench.com");
                ps.setString(2, "jdbc-insert");
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.executeUpdate();
                try (var rs = ps.getGeneratedKeys()) {
                    long id = rs.next() ? rs.getLong(1) : -1L;
                    conn.commit();
                    return id;
                }
            }
        }
    }

    @Benchmark
    public int mybatisInsert(MybatisSetup setup) {
        MybatisBenchUser u = new MybatisBenchUser();
        u.setEmail("mybatis-" + System.nanoTime() + "@bench.com");
        u.setName("mybatis-insert");
        u.setCreatedAt(Instant.now());
        try (var session = setup.sqlSessionFactory.openSession()) {
            int rows = session.insert(
                "com.holo.framework.horm.benchmark.entity.MybatisBenchUser.insert", u);
            session.commit();
            return rows;
        }
    }

    @Benchmark
    public HibernateBenchUser hibernateInsert(HibernateSetup setup) {
        HibernateBenchUser u = new HibernateBenchUser();
        u.setEmail("hib-" + System.nanoTime() + "@bench.com");
        u.setName("hib-insert");
        u.setCreatedAt(Instant.now());
        try (Session session = setup.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            session.persist(u);
            tx.commit();
            return u;
        }
    }

    // ===== Update =====

    @Benchmark
    public BenchUser hormUpdate(HormSetup setup) {
        // Use explicit transaction for fair comparison with MyBatis/Hibernate commit
        return Horm.tx(() -> {
            BenchUser u = Model.find(BenchUser.class, UPDATE_TARGET_ID);
            u.setName("horm-updated-" + System.nanoTime());
            u.save();
            return u;
        });
    }

    @Benchmark
    public int jdbcUpdate(JdbcSetup setup) throws Exception {
        try (Connection conn = setup.dataSource.getConnection()) {
            conn.setAutoCommit(false);  // Explicit transaction for fair comparison
            try (PreparedStatement ps = conn.prepareStatement(
                     "UPDATE bench_users SET name = ? WHERE id = ?")) {
                ps.setString(1, "jdbc-updated-" + System.nanoTime());
                ps.setLong(2, UPDATE_TARGET_ID);
                int rows = ps.executeUpdate();
                conn.commit();
                return rows;
            }
        }
    }

    @Benchmark
    public int mybatisUpdate(MybatisSetup setup) {
        MybatisBenchUser u = new MybatisBenchUser();
        u.setId(UPDATE_TARGET_ID);
        u.setEmail("updated@bench.com");
        u.setName("mybatis-updated-" + System.nanoTime());
        u.setCreatedAt(Instant.now());
        try (var session = setup.sqlSessionFactory.openSession()) {
            int rows = session.update(
                "com.holo.framework.horm.benchmark.entity.MybatisBenchUser.update", u);
            session.commit();
            return rows;
        }
    }

    @Benchmark
    public void hibernateUpdate(HibernateSetup setup) {
        try (Session session = setup.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            HibernateBenchUser u = session.find(HibernateBenchUser.class, UPDATE_TARGET_ID);
            u.setName("hib-updated-" + System.nanoTime());
            session.flush();  // Force SQL execution before commit
            tx.commit();
        }
    }

    // ===== Delete (insert throwaway row first, then delete to keep row count stable) =====

    @Benchmark
    public void hormDelete(HormSetup setup) {
        // Use explicit transaction for fair comparison with MyBatis/Hibernate commit
        Horm.tx(() -> {
            BenchUser u = new BenchUser();
            u.setEmail("horm-del-" + System.nanoTime() + "@bench.com");
            u.setName("to-delete");
            u.setCreatedAt(Instant.now());
            u.save();
            u.delete();
        });
    }

    @Benchmark
    public int jdbcDelete(JdbcSetup setup) throws Exception {
        long id;
        try (Connection conn = setup.dataSource.getConnection()) {
            conn.setAutoCommit(false);  // Explicit transaction for fair comparison
            try (PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bench_users (email, name, created_at) VALUES (?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "jdbc-del-" + System.nanoTime() + "@bench.com");
                ps.setString(2, "to-delete");
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.executeUpdate();
                try (var rs = ps.getGeneratedKeys()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM bench_users WHERE id = ?")) {
                ps.setLong(1, id);
                int rows = ps.executeUpdate();
                conn.commit();
                return rows;
            }
        }
    }

    @Benchmark
    public int mybatisDelete(MybatisSetup setup) {
        MybatisBenchUser u = new MybatisBenchUser();
        u.setEmail("mybatis-del-" + System.nanoTime() + "@bench.com");
        u.setName("to-delete");
        u.setCreatedAt(Instant.now());
        try (var session = setup.sqlSessionFactory.openSession()) {
            session.insert(
                "com.holo.framework.horm.benchmark.entity.MybatisBenchUser.insert", u);
            int rows = session.delete(
                "com.holo.framework.horm.benchmark.entity.MybatisBenchUser.deleteById", u.getId());
            session.commit();
            return rows;
        }
    }

    @Benchmark
    public void hibernateDelete(HibernateSetup setup) {
        HibernateBenchUser u = new HibernateBenchUser();
        u.setEmail("hib-del-" + System.nanoTime() + "@bench.com");
        u.setName("to-delete");
        u.setCreatedAt(Instant.now());
        try (Session session = setup.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            session.persist(u);
            session.remove(u);
            tx.commit();
        }
    }
}
