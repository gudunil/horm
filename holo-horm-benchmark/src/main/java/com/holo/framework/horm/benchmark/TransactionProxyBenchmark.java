package com.holo.framework.horm.benchmark;

import com.holo.framework.horm.benchmark.service.BenchService;
import com.holo.framework.horm.benchmark.setup.BenchmarkEnv;
import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.MethodBridgeFactory;
import com.holo.framework.horm.core.TransactionAdvisorRegistry;
import com.holo.framework.horm.core.TransactionInterceptor;
import com.holo.framework.horm.meta.TransactionMethodMeta;
import com.holo.framework.horm.meta.TransactionProxyFactory;
import com.holo.framework.horm.meta.annotation.Isolation;
import com.holo.framework.horm.meta.annotation.Propagation;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * JMH benchmark comparing transaction proxy invocation paths:
 * <ol>
 *   <li>{@code Method.invoke()} — traditional reflection (baseline)</li>
 *   <li>{@code MethodBridgeFactory} — LambdaMetafactory bridge</li>
 *   <li>APT-generated proxy subclass — zero-reflection</li>
 *   <li>{@code TransactionInterceptor} — JDK dynamic proxy with MethodBridge</li>
 * </ol>
 *
 * <p>Measures proxy invocation overhead under the same shared HikariCP data source
 * used by the rest of the HORM benchmark suite, so the numbers are comparable
 * with other HORM benchmarks.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms256m", "-Xmx256m"})
@State(Scope.Benchmark)
public class TransactionProxyBenchmark {

    private BenchService target;
    private Method executeMethod;
    private MethodBridgeFactory.MethodBridge methodBridge;
    private Object aptProxy;
    private Object jdkProxy;
    private HormContext noOpCtx;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        target = new BenchService();

        // Use the shared HikariCP data source (same as HormSetup) so that
        // this benchmark is comparable with the rest of the HORM benchmark
        // suite. This measures proxy dispatch + real connection-pool overhead.
        BenchmarkEnv.ensureInitialized();
        DataSource ds = BenchmarkEnv.getSharedDataSource();
        DataSourceProvider provider = new DataSourceAdapter(ds);
        noOpCtx = new HormContext(provider);
        HormContext.install(noOpCtx);

        // 1. Method.invoke baseline
        executeMethod = BenchService.class.getMethod("execute", String.class);

        // 2. MethodBridge (LambdaMetafactory)
        methodBridge = MethodBridgeFactory.bridge(executeMethod);

        // 3. APT proxy (discovered via ServiceLoader)
        TransactionProxyFactory factory = null;
        for (TransactionProxyFactory f : ServiceLoader.load(TransactionProxyFactory.class,
                Thread.currentThread().getContextClassLoader())) {
            if (f.targetType() == BenchService.class) {
                factory = f;
                break;
            }
        }
        if (factory != null) {
            aptProxy = factory.create(target, noOpCtx);
        }

        // 4. JDK dynamic proxy (TransactionInterceptor.createProxy)
        TransactionMethodMeta meta = new TransactionMethodMeta(
            "execute", Propagation.REQUIRED, Isolation.DEFAULT, -1, false,
            List.of(), List.of()
        );
        TransactionAdvisorRegistry.registerManual(BenchService.class, "execute", meta);
        jdkProxy = TransactionInterceptor.createProxy(target);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        TransactionAdvisorRegistry.clear();
        HormContext.install(null);
    }

    @Benchmark
    public Object methodInvoke() throws Exception {
        return executeMethod.invoke(target, "test");
    }

    @Benchmark
    public Object methodBridge() throws Throwable {
        return methodBridge.invoke(target, new Object[]{"test"});
    }

    @Benchmark
    public Object aptProxyDirect() {
        return ((BenchService) aptProxy).execute("test");
    }

    @Benchmark
    public Object jdkDynamicProxy() {
        return ((BenchService) jdkProxy).execute("test");
    }

    @Benchmark
    public Object directCall() {
        return target.execute("test");
    }

    /** Adapts a {@link DataSource} to HORM's {@link DataSourceProvider} interface. */
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
