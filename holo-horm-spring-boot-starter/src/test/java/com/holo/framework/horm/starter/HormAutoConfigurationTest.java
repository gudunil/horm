package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.HormContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link HormAutoConfiguration}.
 */
class HormAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HormAutoConfiguration.class));

    @Test
    void autoConfigurationCreatesHormContext() {
        contextRunner
            .withBean(DataSource.class, TestDataSource::new)
            .run(context -> {
                assertThat(context).hasSingleBean(HormContext.class);
            });
    }

    @Test
    void userDefinedHormContextTakesPrecedence() {
        contextRunner
            .withUserConfiguration(CustomHormContextConfig.class)
            .run(context -> {
                assertThat(context).hasSingleBean(HormContext.class);
                assertThat(context.getBean(HormContext.class))
                    .isSameAs(context.getBean("customHormContext"));
            });
    }

    @Test
    void springDataSourceProviderGetConnection() throws SQLException {
        DataSource mockDataSource = mock(DataSource.class);
        Connection mockConnection = mock(Connection.class);
        when(mockDataSource.getConnection()).thenReturn(mockConnection);

        contextRunner
            .withBean(DataSource.class, () -> mockDataSource)
            .run(context -> {
                HormContext ctx = context.getBean(HormContext.class);
                Connection conn = ctx.dataSourceProvider().getConnection();
                assertThat(conn).isSameAs(mockConnection);
                verify(mockDataSource).getConnection();
            });
    }

    @Test
    void springDataSourceProviderReleaseConnection() throws SQLException {
        DataSource mockDataSource = mock(DataSource.class);
        Connection mockConnection = mock(Connection.class);

        contextRunner
            .withBean(DataSource.class, () -> mockDataSource)
            .run(context -> {
                HormContext ctx = context.getBean(HormContext.class);
                ctx.dataSourceProvider().releaseConnection(mockConnection);
                verify(mockConnection).close();
            });
    }

    @Test
    void springDataSourceProviderReleaseConnectionHandlesException() throws SQLException {
        DataSource mockDataSource = mock(DataSource.class);
        Connection mockConnection = mock(Connection.class);
        doThrow(new SQLException("Close failed")).when(mockConnection).close();

        contextRunner
            .withBean(DataSource.class, () -> mockDataSource)
            .run(context -> {
                HormContext ctx = context.getBean(HormContext.class);
                // Should not throw exception
                ctx.dataSourceProvider().releaseConnection(mockConnection);
                verify(mockConnection).close();
            });
    }

    @Test
    void springDataSourceProviderReleaseNullConnection() {
        DataSource mockDataSource = mock(DataSource.class);

        contextRunner
            .withBean(DataSource.class, () -> mockDataSource)
            .run(context -> {
                HormContext ctx = context.getBean(HormContext.class);
                // Should not throw exception
                ctx.dataSourceProvider().releaseConnection(null);
            });
    }

    @Configuration
    static class CustomHormContextConfig {
        @Bean
        HormContext customHormContext() {
            DataSourceProvider provider = new DataSourceProvider() {
                @Override
                public Connection getConnection() throws SQLException {
                    return new TestDataSource().getConnection();
                }
                @Override
                public void releaseConnection(Connection connection) {
                    try { connection.close(); } catch (SQLException ignored) {}
                }
            };
            return new HormContext(provider);
        }
    }

    static class TestDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return null;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return null;
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return false;
        }
    }
}
