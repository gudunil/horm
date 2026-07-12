package com.holo.framework.horm.benchmark;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.hibernate.Session;
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
import com.holo.framework.horm.core.Model;

/**
 * Compares bulk {@code findById} (100 ids) across HORM, hand-written JDBC,
 * MyBatis and Hibernate.
 *
 * <p>The HORM path uses {@link Model#findMany} which, when a {@code CacheChain}
 * is installed, performs a bulk read-through. Without a chain (the setup used
 * here), it issues a single {@code SELECT ... WHERE id IN (?,?,...)} identical
 * to the JDBC baseline — the benchmark therefore measures the ORM mapping
 * overhead, not cache effects.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms256m", "-Xmx256m"})
public class FindManyBenchmark {

    private static final int ID_COUNT = 100;
    private static final List<Long> IDS = IntStream.rangeClosed(1, ID_COUNT)
        .mapToObj(Long::valueOf)
        .collect(Collectors.toUnmodifiableList());

    @Benchmark
    public java.util.Map<Object, BenchUser> hormFindMany(HormSetup setup) {
        return Model.findMany(BenchUser.class, IDS);
    }

    @Benchmark
    public List<JdbcSetup.JdbcBenchUser> jdbcFindMany(JdbcSetup setup) throws Exception {
        String placeholders = IDS.stream().map(i -> "?").collect(Collectors.joining(", "));
        String sql = "SELECT id, email, name, created_at FROM bench_users WHERE id IN ("
            + placeholders + ")";
        try (Connection conn = setup.dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < IDS.size(); i++) {
                ps.setLong(i + 1, IDS.get(i));
            }
            List<JdbcSetup.JdbcBenchUser> result = new ArrayList<>(IDS.size());
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
    public List<MybatisBenchUser> mybatisFindMany(MybatisSetup setup) {
        return setup.findByIds(IDS);
    }

    @Benchmark
    @SuppressWarnings("unchecked")
    public List<HibernateBenchUser> hibernateFindMany(HibernateSetup setup) {
        try (Session session = setup.sessionFactory.openSession()) {
            return session.byMultipleIds(HibernateBenchUser.class).multiLoad(IDS);
        }
    }
}
