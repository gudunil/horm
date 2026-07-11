package com.holo.framework.horm.core;

/**
 * Thrown when an optimistic-lock check fails during UPDATE or DELETE.
 *
 * <p>This indicates that the database row was modified by another transaction
 * between the time the entity was read and the time it was written, so the
 * version column no longer matches the expected value.
 */
public class OptimisticLockException extends RuntimeException {

    public OptimisticLockException(String message) {
        super(message);
    }
}
