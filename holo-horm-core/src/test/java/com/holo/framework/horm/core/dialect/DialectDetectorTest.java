package com.holo.framework.horm.core.dialect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DialectDetectorTest {

    @Test
    void nullUrlReturnsMySqlDialect() {
        assertThat(DialectDetector.detect(null)).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void emptyUrlReturnsMySqlDialect() {
        assertThat(DialectDetector.detect("")).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void blankUrlReturnsMySqlDialect() {
        assertThat(DialectDetector.detect("   ")).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void mysqlUrlReturnsMySqlDialect() {
        assertThat(DialectDetector.detect("jdbc:mysql://localhost:3306/mydb")).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void postgresqlUrlReturnsPostgresDialect() {
        assertThat(DialectDetector.detect("jdbc:postgresql://localhost:5432/mydb")).isInstanceOf(PostgresDialect.class);
    }

    @Test
    void h2DefaultReturnsH2MysqlMode() {
        var dialect = DialectDetector.detect("jdbc:h2:mem:test");
        assertThat(dialect).isInstanceOf(H2Dialect.class);
        assertThat(dialect.name()).isEqualTo("h2");
    }

    @Test
    void h2PostgresqlModeReturnsH2PostgresqlMode() {
        var dialect = DialectDetector.detect("jdbc:h2:mem:test;MODE=PostgreSQL");
        assertThat(dialect).isInstanceOf(H2Dialect.class);
        assertThat(dialect.name()).isEqualTo("h2-postgresql");
    }

    @Test
    void h2PostgresqlModeCaseInsensitive() {
        var dialect = DialectDetector.detect("jdbc:h2:mem:test;MODE=postgresql");
        assertThat(dialect).isInstanceOf(H2Dialect.class);
        assertThat(dialect.name()).isEqualTo("h2-postgresql");
    }

    @Test
    void h2MysqlModeExplicit() {
        var dialect = DialectDetector.detect("jdbc:h2:mem:test;MODE=MySQL");
        assertThat(dialect).isInstanceOf(H2Dialect.class);
        assertThat(dialect.name()).isEqualTo("h2");
    }

    @Test
    void unknownUrlReturnsMySqlDialect() {
        assertThat(DialectDetector.detect("jdbc:oracle://host/db")).isInstanceOf(MySqlDialect.class);
    }
}
