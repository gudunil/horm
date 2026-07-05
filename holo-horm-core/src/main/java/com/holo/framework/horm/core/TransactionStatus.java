package com.holo.framework.horm.core;

import java.sql.Connection;

/**
 * Represents the state of an active transaction, stored on the
 * {@link TransactionManager}'s ThreadLocal stack.
 */
public final class TransactionStatus {

    private final Connection connection;
    private final boolean newTransaction;
    private final TransactionStatus suspended;
    private final DataSourceProvider connectionProvider;
    private final String dataSourceName;

    /**
     * @param connection       the JDBC connection bound to this transaction
     * @param newTransaction   {@code true} if this is a newly created
     *                         transaction (as opposed to joining an existing one)
     * @param suspended        the previously suspended transaction status
     *                         (for REQUIRES_NEW), or {@code null}
     * @param connectionProvider the provider that supplied the connection,
     *                         used to release it on transaction completion
     * @param dataSourceName   the name of the datasource this transaction belongs to
     */
    public TransactionStatus(Connection connection, boolean newTransaction,
                             TransactionStatus suspended,
                             DataSourceProvider connectionProvider,
                             String dataSourceName) {
        this.connection = connection;
        this.newTransaction = newTransaction;
        this.suspended = suspended;
        this.connectionProvider = connectionProvider;
        this.dataSourceName = dataSourceName;
    }

    public Connection connection() { return connection; }
    public boolean isNewTransaction() { return newTransaction; }

    /** Returns the suspended transaction (for REQUIRES_NEW), or null. */
    public TransactionStatus suspended() { return suspended; }

    /** Releases the transaction-bound connection to its provider, if any. */
    void releaseConnection() {
        if (connectionProvider != null) {
            connectionProvider.releaseConnection(connection);
        }
    }
}
