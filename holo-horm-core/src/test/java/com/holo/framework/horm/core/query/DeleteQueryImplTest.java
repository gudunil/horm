package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.HormContext;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeleteQueryImpl 的纯单元测试，使用 Mockito 模拟 JDBC 对象，无需 H2 数据库。
 * 验证 DELETE SQL 的生成、参数绑定以及边界场景。
 */
class DeleteQueryImplTest {

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
    void executeWithWhereDeletesMatchingRows() throws Exception {
        // 基本场景：带 WHERE 条件的 DELETE
        int result = new DeleteQueryImpl<>(User.class, ctx)
            .where(ID.eq(1L))
            .execute();

        // 验证返回受影响行数
        assertThat(result).isEqualTo(1);

        // 验证生成的 SQL 包含 DELETE FROM ... WHERE
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(mockConnection).prepareStatement(sql.capture());
        assertThat(sql.getValue())
            .startsWith("DELETE FROM users")
            .contains("WHERE (id = ?)");

        // 验证调用了 executeUpdate()
        verify(mockPs).executeUpdate();

        // 验证参数绑定
        verify(mockPs).setObject(1, 1L);
    }

    @Test
    void executeWithoutWhereDeletesAllRows() throws Exception {
        // 无 WHERE 子句：全表删除
        int result = new DeleteQueryImpl<>(User.class, ctx)
            .execute();

        assertThat(result).isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(mockConnection).prepareStatement(sql.capture());
        // SQL 不应包含 WHERE，即全表删除
        assertThat(sql.getValue())
            .isEqualTo("DELETE FROM users");
    }

    @Test
    void executeWithAndConditions() throws Exception {
        // 通过 and() 链式添加多个 WHERE 条件
        int result = new DeleteQueryImpl<>(User.class, ctx)
            .where(ID.gt(0L))
            .and(EMAIL.eq("delete@test.com"))
            .execute();

        assertThat(result).isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(mockConnection).prepareStatement(sql.capture());
        // 多个 WHERE 条件用 AND 连接
        assertThat(sql.getValue())
            .contains("WHERE (id > ?) AND (email = ?)");

        // 验证参数绑定顺序
        verify(mockPs).setObject(1, 0L);
        verify(mockPs).setObject(2, "delete@test.com");
    }

    @Test
    void executeUsesTransactionManagerConnection() throws Exception {
        // 验证 execute() 使用 TransactionManager.currentConnection() 获取连接
        // 当没有活跃事务时，currentConnection() 会回退到 ctx.connection()，
        // 所以我们验证 mockConnection 被用来 prepareStatement
        new DeleteQueryImpl<>(User.class, ctx)
            .where(ID.eq(1L))
            .execute();

        // mockConnection 是 ctx 中的连接，TransactionManager.currentConnection(ctx)
        // 在无事务时回退到 ctx.connection()，因此应使用 mockConnection
        verify(mockConnection).prepareStatement(anyString());
        verify(mockPs).executeUpdate();
    }
}
