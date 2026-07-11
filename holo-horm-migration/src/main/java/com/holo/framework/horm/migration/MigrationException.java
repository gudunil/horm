package com.holo.framework.horm.migration;

/**
 * 迁移相关异常的基类。
 */
public class MigrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
