package com.holo.framework.horm.benchmark;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.hibernate.Session;
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
import com.holo.framework.horm.benchmark.entity.generated.BenchUserQueryMeta;
import com.holo.framework.horm.benchmark.setup.HibernateSetup;
import com.holo.framework.horm.benchmark.setup.HormSetup;
import com.holo.framework.horm.benchmark.setup.JdbcSetup;
import com.holo.framework.horm.benchmark.setup.MybatisSetup;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.query.Order;

/**
 * Compares conditional query + ORDER BY + LIMIT across HORM, hand-written
 * JDBC, MyBatis and Hibernate at two result-set sizes (10 and 100 rows).
 *
 * <p>The HORM path uses the APT-generated {@link BenchUserQueryMeta} typed
 * fields so the benchmark exercises the same type-safe DSL that application
 * code would use. {@code @Param} runs each method at both sizes.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms256m", "-Xmx256m"})
@State(Scope.Benchmark)
public class QueryBenchmark {

    @Param({"10", "100"})
    public int limit;

    @Benchmark
    public List<BenchUser> hormQuery(HormSetup setup) {
        return Model.query(BenchUser.class)
            .where(BenchUserQueryMeta.ID.between(1L, (long) limit))
            .orderBy(BenchUserQueryMeta.ID, Order.ASC)
            .limit(limit)
            .list();
    }

    @Benchmark
    public List<JdbcSetup.JdbcBenchUser> jdbcQuery(JdbcSetup setup) throws Exception {
        try (Connection conn = setup.dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, email, name, created_at FROM bench_users "
                 + "WHERE id BETWEEN ? AND ? ORDER BY id LIMIT ?")) {
            ps.setLong(1, 1L);
            ps.setLong(2, limit);
            ps.setLong(3, limit);
            List<JdbcSetup.JdbcBenchUser> result = new ArrayList<>(limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JdbcSetup.JdbcBenchUser u = new JdbcSetup.JdbcBenchUser();
                    u.id = rs.getLong(1);
                    u.email = rs.getString(2);
                    u.name = rs.getString(3);
                    u.createdAt = rs.getTimestamp(4) == null
                        ? null : rs.getTimestamp(4).toInstant();
                    result.add(u);
                }
            }
            return result;
        }
    }

    @Benchmark
    public List<MybatisBenchUser> mybatisQuery(MybatisSetup setup) {
        return setup.findList(limit);
    }

    @Benchmark
    public List<HibernateBenchUser> hibernateQuery(HibernateSetup setup) {
        try (Session session = setup.sessionFactory.openSession()) {
            return session.createQuery(
                    "FROM HibernateBenchUser WHERE id BETWEEN 1 AND :lim ORDER BY id",
                    HibernateBenchUser.class)
                .setParameter("lim", (long) limit)
                .setMaxResults(limit)
                .list();
        }
    }
}
