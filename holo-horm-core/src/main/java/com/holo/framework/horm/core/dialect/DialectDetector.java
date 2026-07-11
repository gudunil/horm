package com.holo.framework.horm.core.dialect;

public final class DialectDetector {

    private DialectDetector() {}

    public static Dialect detect(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return new MySqlDialect();
        }

        if (jdbcUrl.startsWith("jdbc:mysql")) {
            return new MySqlDialect();
        }

        if (jdbcUrl.startsWith("jdbc:postgresql")) {
            return new PostgresDialect();
        }

        if (jdbcUrl.startsWith("jdbc:h2")) {
            if (jdbcUrl.toUpperCase().contains("MODE=POSTGRESQL")) {
                return new H2Dialect("postgresql");
            }
            return new H2Dialect("mysql");
        }

        return new MySqlDialect();
    }
}
