package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.HormException;
import com.holo.framework.horm.core.TransactionManager;
import com.holo.framework.horm.core.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static com.holo.framework.horm.core.generated.UserQueryMeta.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UpdateQueryImpl 的纯单元测试，使用 Mockito 模拟 JDBC 对象，无需 H2 数据库。
 * 验证 UPDATE SQL 的生成、参数绑定以及异常处理。
 */
class UpdateQueryImplTest {

    private Connection mockConnection;
    private PreparedStatement mockPs;
    private HormContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        // 重新加载实体元数据注册表，确保 User 实体已注册
        EntityMetaRegistry.reload();
        mockConnection = mock(Connection.class);
        mockPs = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPs);
        when(mockPs.executeUpdate()).thenReturn(1);
        ctx = new HormContext(mockConnection);
    }

    @AfterEach
    void tearDown() {
        // 清理事务管理器线程本地状态，避免测试之间泄漏
        TransactionManager.clear();
    }

    @Test
    void executeWithSingleSetAndWhere() throws Exception {
        // 基本场景：单个 SET + WHERE 条件
        int result = new UpdateQueryImpl<>(User.class, ctx)
            .set(EMAIL, "new@test.com")
            .where(ID.eq(1L))
            .execute();

        // 验证返回受影响行数
        assertThat(result).isEqualTo(1);

        // 验证生成的 SQL 包含 UPDATE ... SET ... WHERE
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(mockConnection).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .startsWith("UPDATE users SET ")
            .contains("email = ?")
            .contains("WHERE (id = ?)");

        // 验证调用了 executeUpdate() 而非 executeQuery()
        verify(mockPs).executeUpdate();

        // 验证参数绑定顺序：先 SET 值，后 WHERE 值
        verify(mockPs).setObject(1, "new@test.com");
        verify(mockPs).setObject(2, 1L);
    }

    @Test
    void executeWithMultipleSetEntries() throws Exception {
        // 多个 SET 字段
        int result = new UpdateQueryImpl<>(User.class, ctx)
            .set(EMAIL, "multi@test.com")
            .set(UPDATED_AT, java.time.Instant.parse("2026-01-01T00:00:00Z"))
            .where(ID.eq(2L))
            .execute();

        assertThat(result).isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(mockConnection).prepareStatement(sql.capture());
        // 多个 SET 字段用逗号分隔
        assertThat(sql.getValue())
            .contains("email = ?")
            .contains("updated_at = ?")
            .contains("WHERE (id = ?)");

        // 验证参数绑定顺序；Instant 经 SqlBinding 规范化为 Timestamp 以统一时区写入路径
        verify(mockPs).setObject(1, "multi@test.com");
        verify(mockPs).setObject(2, java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z")));
        verify(mockPs).setObject(3, 2L);
    }

    @Test
    void executeWithoutWhereUpdatesAllRows() throws Exception {
        // 无 WHERE 子句：全表更新
        int result = new UpdateQueryImpl<>(User.class, ctx)
            .set(EMAIL, "all@test.com")
            .execute();

        assertThat(result).isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(mockConnection).prepareStatement(sql.capture());
        // SQL 不应包含 WHERE
        assertThat(sql.getValue())
            .startsWith("UPDATE users SET email = ?")
            .doesNotContain("WHERE");
    }

    @Test
    void executeWithoutSetClauseThrows() {
        // 没有 SET 子句时应当抛出 HormException
        assertThatThrownBy(() -> new UpdateQueryImpl<>(User.class, ctx)
            .where(ID.eq(1L))
            .execute())
            .isInstanceOf(HormException.class)
            .hasMessageContaining("UPDATE requires at least one SET clause");
    }

    @Test
    void executeWithAndConditions() throws Exception {
        // 通过 and() 链式添加多个 WHERE 条件
        int result = new UpdateQueryImpl<>(User.class, ctx)
            .set(EMAIL, "and@test.com")
            .where(ID.gt(0L))
            .and(EMAIL.eq("old@test.com"))
            .execute();

        assertThat(result).isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(mockConnection).prepareStatement(sql.capture());
        // 多个 WHERE 条件用 AND 连接
        assertThat(sql.getValue())
            .contains("WHERE (id > ?) AND (email = ?)");

        // 验证参数绑定顺序：SET 值在前，WHERE 值在后
        verify(mockPs).setObject(1, "and@test.com");
        verify(mockPs).setObject(2, 0L);
        verify(mockPs).setObject(3, "old@test.com");
    }

    @Test
    void executeUsesTransactionManagerConnection() throws Exception {
        // 验证 execute() 使用 TransactionManager.currentConnection() 获取连接，
        // 而非直接使用 ctx.connection()
        // 当没有活跃事务时，currentConnection() 会回退到 ctx.connection()，
        // 所以我们验证 mockConnection 被用来 prepareStatement
        new UpdateQueryImpl<>(User.class, ctx)
            .set(EMAIL, "tx@test.com")
            .where(ID.eq(1L))
            .execute();

        // mockConnection 是 ctx 中的连接，TransactionManager.currentConnection(ctx)
        // 在无事务时回退到 ctx.connection()，因此应使用 mockConnection
        verify(mockConnection).prepareStatement(anyString());
        verify(mockPs).executeUpdate();
    }
}
