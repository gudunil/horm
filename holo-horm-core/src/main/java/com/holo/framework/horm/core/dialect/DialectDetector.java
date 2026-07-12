package com.holo.framework.horm.core.dialect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DialectDetector {

    private static final Logger log = LoggerFactory.getLogger(DialectDetector.class);

    private DialectDetector() {}

    public static Dialect detect(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            log.warn("JDBC URL is null or blank; falling back to MySQL dialect");
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

        log.warn("Unrecognized JDBC URL '{}'; falling back to MySQL dialect", jdbcUrl);
        return new MySqlDialect();
    }
}
