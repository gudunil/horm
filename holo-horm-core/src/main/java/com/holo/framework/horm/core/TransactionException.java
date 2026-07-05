package com.holo.framework.horm.core;

/**
 * Exception thrown when a transaction management operation fails.
 */
public class TransactionException extends HormException {

    private static final long serialVersionUID = 1L;

    public TransactionException(String message) {
        super(message);
    }

    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
