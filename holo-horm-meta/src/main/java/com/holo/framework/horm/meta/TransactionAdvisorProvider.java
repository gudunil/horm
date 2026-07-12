package com.holo.framework.horm.meta;

import java.util.List;

/**
 * SPI interface for APT-generated transaction advisor providers.
 *
 * <p>For each class containing {@code @Transactional} methods, the APT
 * processor generates an {@code XxxTransactionAdvisor} companion class that
 * implements this interface. The runtime {@code TransactionAdvisorRegistry}
 * discovers implementations via {@link java.util.ServiceLoader} and calls
 * {@link #methods()} and {@link #targetClassName()} to register transaction
 * metadata — eliminating the need for {@code Class.forName()} and
 * {@code Field.get()} reflection on the startup path.
 *
 * <p>Backward compatibility: the registry falls back to the legacy
 * {@code META-INF/horm/transactions.idx} index when no ServiceLoader
 * configuration is present.
 *
 * @see TransactionMethodMeta
 */
public interface TransactionAdvisorProvider {

    /**
     * Returns the transaction method metadata for the target class.
     *
     * <p>APT-generated implementations typically return the static
     * {@code METHODS} list from the generated advisor class.
     *
     * @return the list of transaction method metadata, never {@code null}
     */
    List<TransactionMethodMeta> methods();

    /**
     * Returns the fully-qualified class name of the original class that
     * the transaction metadata applies to.
     *
     * <p>This replaces the legacy approach of deriving the class name
     * from the advisor class name by stripping the "TransactionAdvisor"
     * suffix.
     *
     * @return the fully-qualified target class name
     */
    String targetClassName();
}
