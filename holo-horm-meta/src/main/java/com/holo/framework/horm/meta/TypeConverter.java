package com.holo.framework.horm.meta;

/**
 * Converts between Java domain types and data source storage types.
 *
 * <p>HORM ships built-in converters for common pairs (e.g.
 * {@code Instant ↔ java.sql.Timestamp}, {@code Enum ↔ String},
 * {@code UUID ↔ String}). Custom converters are registered through the
 * {@code TypeConverterRegistry} (M2+).
 *
 * <p>The annotation processor chooses the appropriate converter for each
 * field at compile time, so generated {@link Mapper} code calls
 * {@code converter.toJava(row.getXxx(...))} directly — no runtime lookup.
 *
 * @param <J> Java domain type
 * @param <S> storage type used by the data source driver
 */
public interface TypeConverter<J, S> {

    /** Java domain type handled by this converter. */
    Class<J> javaType();

    /** Storage type produced/consumed by the data source driver. */
    Class<S> storageType();

    /** Converts a storage value to the Java domain type. */
    J toJava(S storageValue);

    /** Converts a Java domain value to the storage type. */
    S toStorage(J javaValue);
}
