package com.holo.framework.horm.benchmark.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.holo.framework.horm.benchmark.entity.MybatisBenchUser;

/**
 * Initializes MyBatis with an XML-driven {@link SqlSessionFactory} bound to
 * the shared H2 memory database. Mapper statements live in
 * {@code mybatis/BenchUserMapper.xml}.
 */
@State(Scope.Benchmark)
public class MybatisSetup {

    public SqlSessionFactory sqlSessionFactory;

    @org.openjdk.jmh.annotations.Setup(Level.Trial)
    public void setUp() throws SQLException, IOException {
        BenchmarkEnv.ensureInitialized();
        try (InputStream in = getClass().getResourceAsStream("/mybatis/mybatis-config.xml");
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // SqlSessionFactory does not require explicit close.
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
}
