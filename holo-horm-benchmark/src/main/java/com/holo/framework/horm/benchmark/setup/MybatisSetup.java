package com.holo.framework.horm.benchmark.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.holo.framework.horm.benchmark.entity.MybatisBenchUser;

/**
 * Initializes MyBatis with a HikariCP-backed {@link SqlSessionFactory}.
 *
 * <p>Uses the shared {@link BenchmarkEnv#getSharedDataSource()} (HikariCP) to
 * ensure fair performance comparisons with other frameworks.
 *
 * <p>Mapper statements live in {@code mybatis/BenchUserMapper.xml}.
 */
@State(Scope.Benchmark)
public class MybatisSetup {

    public SqlSessionFactory sqlSessionFactory;

    @org.openjdk.jmh.annotations.Setup(Level.Trial)
    public void setUp() throws SQLException, IOException {
        BenchmarkEnv.ensureInitialized();
        DataSource dataSource = BenchmarkEnv.getSharedDataSource();
        TransactionFactory transactionFactory = new JdbcTransactionFactory();
        Environment environment = new Environment("bench", transactionFactory, dataSource);

        org.apache.ibatis.session.Configuration config;
        try (InputStream in = getClass().getResourceAsStream("/mybatis/mybatis-config.xml");
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            // Build base config from XML, then override environment
            config = new SqlSessionFactoryBuilder().build(reader).getConfiguration();
        }
        config.setEnvironment(environment);
        // Disable MyBatis internal cache for fair comparison
        config.setCacheEnabled(false);

        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // SqlSessionFactory does not require explicit close.
        // DataSource is shared and closed by BenchmarkEnv.
    }

    public MybatisBenchUser findById(long id) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.selectOne(
                "com.holo.framework.horm.benchmark.entity.MybatisBenchUser.findById", id);
        }
    }

    public List<MybatisBenchUser> findByIds(List<Long> ids) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.selectList(
                "com.holo.framework.horm.benchmark.entity.MybatisBenchUser.findByIds", ids);
        }
    }

    public int insert(MybatisBenchUser user) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            int rows = session.insert(
                "com.holo.framework.horm.benchmark.entity.MybatisBenchUser.insert", user);
            session.commit();
            return rows;
        }
    }

    public List<MybatisBenchUser> findList(int limit) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.selectList(
                "com.holo.framework.horm.benchmark.entity.MybatisBenchUser.findList", limit);
        }
    }

    /**
     * Opens a batch-enabled session for bulk insert operations.
     * Uses {@link ExecutorType#BATCH} to accumulate statements and execute them
     * in a single batch flush, matching JDBC {@code addBatch/executeBatch} behavior.
     */
    public SqlSession openBatchSession() {
        return sqlSessionFactory.openSession(ExecutorType.BATCH);
    }
}
