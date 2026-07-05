package com.holo.framework.horm.meta.annotation;

/**
 * Transaction propagation behaviors for HORM transaction management.
 *
 * <p>M4 implements {@link #REQUIRED} and {@link #REQUIRES_NEW}; remaining
 * behaviors are reserved for future milestones.
 */
public enum Propagation {

    /**
     * Support a current transaction; create a new one if none exists.
     * This is the default propagation behavior.
     */
    REQUIRED,

    /**
     * Create a new transaction, suspending the current transaction if one
     * exists. The inner transaction commits or rolls back independently
     * of the outer transaction.
     */
    REQUIRES_NEW
}
