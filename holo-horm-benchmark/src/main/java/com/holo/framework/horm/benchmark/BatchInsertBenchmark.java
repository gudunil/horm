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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import com.holo.framework.horm.benchmark.entity.BenchUser;
import com.holo.framework.horm.benchmark.entity.HibernateBenchUser;
import com.holo.framework.horm.benchmark.entity.MybatisBenchUser;
import com.holo.framework.horm.benchmark.setup.HibernateSetup;
import com.holo.framework.horm.benchmark.setup.HormSetup;
import com.holo.framework.horm.benchmark.setup.JdbcSetup;
import com.holo.framework.horm.benchmark.setup.MybatisSetup;

/**
 * Compares batch-insert throughput across HORM, hand-written JDBC, MyBatis
 * and Hibernate at two scales: 100 and 1000 rows per invocation.
 *
 * <p>JMH's {@code @Param} runs each benchmark method once per value, yielding
 * four data points per framework. Rows are tagged with a unique
 * {@code batch-<nanoTime>-} email prefix so they can be cleaned up if the
 * benchmark database is reused across runs.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms256m", "-Xmx256m"})
@State(Scope.Benchmark)
public class BatchInsertBenchmark {

    @Param({"100", "1000"})
    public int batchSize;

    @Benchmark
    public void hormBatch(HormSetup setup) {
        long stamp = System.nanoTime();
        for (int i = 0; i < batchSize; i++) {
            BenchUser u = new BenchUser();
            u.setEmail("batch-" + stamp + "-" + i + "@bench.com");
            u.setName("horm-batch");
            u.setCreatedAt(Instant.now());
            u.save();
        }
    }

    @Benchmark
    public void jdbcBatch(JdbcSetup setup) throws Exception {
        long stamp = System.nanoTime();
        try (Connection conn = setup.dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO bench_users (email, name, created_at) VALUES (?, ?, ?)")) {
            for (int i = 0; i < batchSize; i++) {
                ps.setString(1, "batch-" + stamp + "-" + i + "@bench.com");
                ps.setString(2, "jdbc-batch");
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.addBatch();
                if (i % 200 == 199) ps.executeBatch();
            }
            ps.executeBatch();
        }
    }

    @Benchmark
    public void mybatisBatch(MybatisSetup setup) {
        long stamp = System.nanoTime();
        try (var session = setup.sqlSessionFactory.openSession()) {
            for (int i = 0; i < batchSize; i++) {
                MybatisBenchUser u = new MybatisBenchUser();
                u.setEmail("batch-" + stamp + "-" + i + "@bench.com");
                u.setName("mybatis-batch");
                u.setCreatedAt(Instant.now());
                session.insert(
                    "com.holo.framework.horm.benchmark.entity.MybatisBenchUser.insert", u);
            }
            session.commit();
        }
    }

    @Benchmark
    public void hibernateBatch(HibernateSetup setup) {
        long stamp = System.nanoTime();
        try (Session session = setup.sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            for (int i = 0; i < batchSize; i++) {
                HibernateBenchUser u = new HibernateBenchUser();
                u.setEmail("batch-" + stamp + "-" + i + "@bench.com");
                u.setName("hib-batch");
                u.setCreatedAt(Instant.now());
                session.persist(u);
                if (i % 50 == 49) {
                    session.flush();
                    session.clear();
                }
            }
            tx.commit();
        }
    }
}
