package com.holo.framework.horm.meta;

/**
 * SPI interface for APT-generated entity meta providers.
 *
 * <p>For each {@code @Entity}-annotated class, the APT processor generates
 * an {@code XxxMeta} companion class that implements this interface. The
 * runtime {@code EntityMetaRegistry} discovers implementations via
 * {@link java.util.ServiceLoader} and calls {@link #provide()} to register
 * entity metadata — eliminating the need for {@code Class.forName()} and
 * {@code Method.invoke()} reflection on the startup path.
 *
 * <p>Backward compatibility: the registry falls back to the legacy
 * {@code META-INF/horm/entities.idx} index when no ServiceLoader
 * configuration is present.
 *
 * @see EntityMeta
 */
public interface EntityMetaProvider {

    /**
     * Returns the entity metadata for the associated entity class.
     *
     * <p>APT-generated implementations typically delegate to the static
     * {@code entityMeta()} method on the generated {@code XxxMeta} class.
     *
     * @return the entity metadata, never {@code null}
     */
    EntityMeta<?> provide();
}
