package com.holo.framework.horm.examples.datasource;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.examples.entity.ArchivedOrder;
import com.holo.framework.horm.examples.entity.User;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 多数据源独立示例。
 *
 * <p>演示如何编程式注册多个数据源，以及 {@code @Entity(dataSource = "...")}
 * 如何驱动实体自动路由到指定数据源。
 */
public final class MultiDataSourceExample {

    private static final String DEFAULT_H2 = "jdbc:h2:mem:multi_default;MODE=MySQL;DB_CLOSE_DELAY=-1";
    private static final String ARCHIVE_H2 = "jdbc:h2:mem:multi_archive;MODE=MySQL;DB_CLOSE_DELAY=-1";

    public static void main(String[] args) throws Exception {
        EntityMetaRegistry.reload();

        initSchema(DEFAULT_H2,
            "CREATE TABLE IF NOT EXISTS users (" +
            "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
            "  email VARCHAR(128) NOT NULL, " +
            "  nickname VARCHAR(64), " +
            "  version BIGINT NOT NULL DEFAULT 0, " +
            "  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "  UNIQUE KEY uk_users_email (email)" +
            ")");

        initSchema(ARCHIVE_H2,
            "CREATE TABLE IF NOT EXISTS archived_orders (" +
            "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
            "  order_id BIGINT NOT NULL, " +
            "  email VARCHAR(128) NOT NULL, " +
            "  total_price DECIMAL(19, 4) NOT NULL, " +
            "  archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" +
            ")");

        DataSourceProvider defaultProvider = new DataSourceProvider() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                return DriverManager.getConnection(DEFAULT_H2);
            }

            @Override
            public void releaseConnection(java.sql.Connection connection) {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (java.sql.SQLException e) {
                        // best-effort
                    }
                }
            }
        };

        DataSourceProvider archiveProvider = new DataSourceProvider() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                return DriverManager.getConnection(ARCHIVE_H2);
            }

            @Override
            public void releaseConnection(java.sql.Connection connection) {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (java.sql.SQLException e) {
                        // best-effort
                    }
                }
            }
        };

        Horm.installOrRegister("default", defaultProvider);
        Horm.install("archive", archiveProvider);

        User user = new User();
        user.setEmail("alice@holo.dev");
        user.setNickname("Alice");
        user.save();
        System.out.println("Saved user to default datasource: " + user.getId());

        ArchivedOrder archived = new ArchivedOrder();
        archived.setOrderId(1001L);
        archived.setEmail(user.getEmail());
        archived.setTotalPrice(java.math.BigDecimal.valueOf(1999.00));
        archived.save();
        System.out.println("Saved archived order to archive datasource: " + archived.getId());

        User loadedUser = Model.find(User.class, user.getId());
        ArchivedOrder loadedArchived = Model.find(ArchivedOrder.class, archived.getId());
        System.out.println("Loaded user: " + loadedUser.getEmail());
        System.out.println("Loaded archived order: " + loadedArchived.getOrderId());

        HormContext.install(null);
    }

    private static void initSchema(String url, String ddl) throws Exception {
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            st.execute(ddl);
        }
    }
}
