package com.holo.framework.horm.benchmark;

import java.util.concurrent.TimeUnit;

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
 * Compares {@code findById} latency across HORM, hand-written JDBC,
 * MyBatis and Hibernate. Each {@code @Benchmark} method exercises the
 * full call path (session/repository creation → SQL → mapping) so the
 * numbers reflect real-world usage, not a pre-warmed shortcut.
 *
 * <p>HORM is invoked via {@link Model#find} (the {@code Class}-keyed
 * overload) because benchmark sources live in {@code main/java} and
 * ByteBuddy instrumentation only fires after {@code process-classes};
 * the {@code BenchUser.find(id)} Active Record overload would bind to
 * the {@code Model.find(Object)} fallback at compile time.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms256m", "-Xmx256m"})
public class FindByIdBenchmark {

    private static final long LOOKUP_ID = 1L;

    @Benchmark
    public BenchUser horm(HormSetup setup) {
        return Model.find(BenchUser.class, LOOKUP_ID);
    }

    @Benchmark
    public JdbcSetup.JdbcBenchUser jdbc(JdbcSetup setup) throws Exception {
        return setup.findById(LOOKUP_ID);
    }

    @Benchmark
    public MybatisBenchUser mybatis(MybatisSetup setup) {
        return setup.findById(LOOKUP_ID);
    }

    @Benchmark
    public HibernateBenchUser hibernate(HibernateSetup setup) {
        return setup.findById(LOOKUP_ID);
    }
}
