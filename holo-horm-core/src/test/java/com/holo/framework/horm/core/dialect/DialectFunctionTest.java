package com.holo.framework.horm.core.dialect;

import com.holo.framework.horm.meta.query.expr.FunctionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialectFunctionTest {

    private final Dialect mysql = new MySqlDialect();
    private final Dialect postgres = new PostgresDialect();
    private final Dialect h2 = new H2Dialect("MySQL");
    private final Dialect h2pg = new H2Dialect("PostgreSQL");

    // —— Default implementation ——

    @Test
    void defaultCountStarRendersCorrectly() {
        assertThat(mysql.functionSql(FunctionType.COUNT, List.of())).isEqualTo("COUNT(*)");
        assertThat(postgres.functionSql(FunctionType.COUNT, List.of())).isEqualTo("COUNT(*)");
    }

    @Test
    void defaultUpperRendersAnsi() {
        assertThat(mysql.functionSql(FunctionType.UPPER, List.of("t0.name"))).isEqualTo("UPPER(t0.name)");
    }

    @Test
    void rawThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> mysql.functionSql(FunctionType.RAW, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RAW must not reach");
    }

    @Test
    void defaultSupportsFunctionReturnsTrue() {
        assertThat(mysql.supportsFunction(FunctionType.UPPER)).isTrue();
        assertThat(postgres.supportsFunction(FunctionType.DATE_FORMAT)).isTrue();
    }

    // —— MySQL dialect ——

    @Test
    void mysqlDateFormatRendersWithBinding() {
        assertThat(mysql.functionSql(FunctionType.DATE_FORMAT, List.of("t0.created_at")))
            .isEqualTo("DATE_FORMAT(t0.created_at, ?)");
    }

    @Test
    void mysqlYearRendersYear() {
        assertThat(mysql.functionSql(FunctionType.YEAR, List.of("t0.created_at")))
            .isEqualTo("YEAR(t0.created_at)");
    }

    @Test
    void mysqlNowRendersNow() {
        assertThat(mysql.functionSql(FunctionType.NOW, List.of())).isEqualTo("NOW()");
    }

    @Test
    void mysqlConcatRendersConcat() {
        assertThat(mysql.functionSql(FunctionType.CONCAT, List.of("t0.a", "t0.b")))
            .isEqualTo("CONCAT(t0.a, t0.b)");
    }

    @Test
    void mysqlTranslatePattern() {
        assertThat(mysql.translateDateFormatPattern("yyyy-MM-dd")).isEqualTo("%Y-%m-%d");
        assertThat(mysql.translateDateFormatPattern("yyyy-MM-dd HH:mm:ss")).isEqualTo("%Y-%m-%d %H:%i:%s");
    }

    // —— PostgreSQL dialect ——

    @Test
    void postgresDateFormatRendersToChar() {
        assertThat(postgres.functionSql(FunctionType.DATE_FORMAT, List.of("t0.created_at")))
            .isEqualTo("TO_CHAR(t0.created_at, ?)");
    }

    @Test
    void postgresYearRendersExtract() {
        assertThat(postgres.functionSql(FunctionType.YEAR, List.of("t0.created_at")))
            .isEqualTo("EXTRACT(YEAR FROM t0.created_at)");
    }

    @Test
    void postgresNowRendersCurrentTimestamp() {
        assertThat(postgres.functionSql(FunctionType.NOW, List.of())).isEqualTo("CURRENT_TIMESTAMP");
    }

    @Test
    void postgresConcatRendersPipePipe() {
        assertThat(postgres.functionSql(FunctionType.CONCAT, List.of("t0.a", "t0.b", "t0.c")))
            .isEqualTo("t0.a || t0.b || t0.c");
    }

    @Test
    void postgresTranslatePattern() {
        assertThat(postgres.translateDateFormatPattern("yyyy-MM-dd")).isEqualTo("YYYY-MM-DD");
    }

    // —— H2 dialect ——

    @Test
    void h2DateFormatRendersFormatdatetime() {
        assertThat(h2.functionSql(FunctionType.DATE_FORMAT, List.of("t0.created_at")))
            .isEqualTo("FORMATDATETIME(t0.created_at, ?)");
    }

    @Test
    void h2NowRendersCurrentTimestamp() {
        assertThat(h2.functionSql(FunctionType.NOW, List.of())).isEqualTo("CURRENT_TIMESTAMP");
    }

    @Test
    void h2TranslatePatternNoChange() {
        assertThat(h2.translateDateFormatPattern("yyyy-MM-dd")).isEqualTo("yyyy-MM-dd");
    }

    @Test
    void h2PgModeDateFormatRendersToChar() {
        assertThat(h2pg.functionSql(FunctionType.DATE_FORMAT, List.of("t0.created_at")))
            .isEqualTo("TO_CHAR(t0.created_at, ?)");
    }

    @Test
    void h2PgModeConcatRendersPipePipe() {
        assertThat(h2pg.functionSql(FunctionType.CONCAT, List.of("t0.a", "t0.b")))
            .isEqualTo("t0.a || t0.b");
    }

    @Test
    void h2PgModeTranslatePattern() {
        assertThat(h2pg.translateDateFormatPattern("yyyy-MM-dd")).isEqualTo("YYYY-MM-DD");
    }

    // —— Standard functions (all dialects) ——

    @Test
    void sumRendersAnsiOnAllDialects() {
        assertThat(mysql.functionSql(FunctionType.SUM, List.of("t0.amount"))).isEqualTo("SUM(t0.amount)");
        assertThat(postgres.functionSql(FunctionType.SUM, List.of("t0.amount"))).isEqualTo("SUM(t0.amount)");
        assertThat(h2.functionSql(FunctionType.SUM, List.of("t0.amount"))).isEqualTo("SUM(t0.amount)");
    }

    @Test
    void avgRendersAnsiOnAllDialects() {
        assertThat(mysql.functionSql(FunctionType.AVG, List.of("t0.salary"))).isEqualTo("AVG(t0.salary)");
    }

    @Test
    void countDistinctRendersAnsi() {
        assertThat(mysql.functionSql(FunctionType.COUNT_DISTINCT, List.of("t0.name")))
            .isEqualTo("COUNT_DISTINCT(t0.name)");
    }
}
