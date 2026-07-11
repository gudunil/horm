package com.holo.framework.horm.meta;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Data-source agnostic row abstraction used by {@link Mapper} to bridge
 * between entities and native result representations (JDBC {@code ResultSet},
 * Mongo {@code Document}, HTTP JSON, etc.).
 *
 * <p>Implementations must tolerate missing columns: {@link #has(String)}
 * returns {@code false} for absent columns, and typed accessors return
 * {@code null} when the column is absent or SQL NULL.
 */
public interface Row {

    /** Table name this row belongs to, or empty for ad-hoc rows. */
    String table();

    /** Whether a column is present and non-null. */
    boolean has(String column);

    /** Returns the raw value, or {@code null} if absent. */
    Object get(String column);

    /** Typed accessor for {@code Long} / {@code long} columns. */
    Long getLong(String column);

    /** Typed accessor for {@code Integer} / {@code int} columns. */
    Integer getInteger(String column);

    /** Typed accessor for {@code String} columns. */
    String getString(String column);

    /** Typed accessor for {@code Instant} columns. stored as epoch millis or SQL timestamp. */
    Instant getInstant(String column);

    /** Typed accessor for {@code BigDecimal} columns. */
    BigDecimal getBigDecimal(String column);

    /** Typed accessor for {@code boolean} columns. */
    Boolean getBoolean(String column);

    /** Typed accessor for {@code byte[]} columns. */
    byte[] getBytes(String column);

    /** Typed accessor for enum columns, stored by name. */
    <E extends Enum<E>> E getEnum(String column, Class<E> type);

    /** Sets a value on this row (mutable rows only). */
    void set(String column, Object value);

    /** Typed setters for convenience. */
    void setLong(String column, long value);
    void setString(String column, String value);
    void setInstant(String column, Instant value);
    void setBoolean(String column, boolean value);

    /** Returns a snapshot view of all column → value pairs. */
    Map<String, Object> asMap();

    /** Creates a new empty mutable row bound to the given table name. */
    static Row create(String table) {
        return new MapRow(table);
    }

    /** Default in-memory implementation backed by a {@link LinkedHashMap}. */
    final class MapRow implements Row {
        private final String table;
        private final Map<String, Object> values = new LinkedHashMap<>();

        MapRow(String table) { this.table = Objects.requireNonNullElse(table, ""); }

        @Override public String table() { return table; }

        @Override public boolean has(String column) {
            return values.containsKey(column) && values.get(column) != null;
        }

        @Override public Object get(String column) { return values.get(column); }

        @Override public Long getLong(String column) {
            Object v = values.get(column);
            if (v == null) return null;
            if (v instanceof Number n) return n.longValue();
            if (v instanceof String s) return Long.parseLong(s);
            throw new ClassCastException("Cannot cast " + v.getClass() + " to Long for column " + column);
        }

        @Override public Integer getInteger(String column) {
            Object v = values.get(column);
            if (v == null) return null;
            if (v instanceof Number n) return n.intValue();
            if (v instanceof String s) return Integer.parseInt(s);
            throw new ClassCastException("Cannot cast " + v.getClass() + " to Integer for column " + column);
        }

        @Override public String getString(String column) {
            Object v = values.get(column);
            return v == null ? null : v.toString();
        }

        @Override public Instant getInstant(String column) {
            Object v = values.get(column);
            if (v == null) return null;
            if (v instanceof Instant i) return i;
            if (v instanceof java.sql.Timestamp t) return t.toInstant();
            if (v instanceof java.time.LocalDateTime ldt) return ldt.atZone(java.time.ZoneOffset.UTC).toInstant();
            if (v instanceof java.time.OffsetDateTime odt) return odt.toInstant();
            if (v instanceof java.time.ZonedDateTime zdt) return zdt.toInstant();
            if (v instanceof Long l) return Instant.ofEpochMilli(l);
            if (v instanceof String s) return Instant.parse(s);
            throw new ClassCastException("Cannot cast " + v.getClass() + " to Instant for column " + column);
        }

        @Override public BigDecimal getBigDecimal(String column) {
            Object v = values.get(column);
            if (v == null) return null;
            if (v instanceof BigDecimal bd) return bd;
            if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            if (v instanceof String s) return new BigDecimal(s);
            throw new ClassCastException("Cannot cast " + v.getClass() + " to BigDecimal for column " + column);
        }

        @Override public Boolean getBoolean(String column) {
            Object v = values.get(column);
            if (v == null) return null;
            if (v instanceof Boolean b) return b;
            if (v instanceof Number n) return n.intValue() != 0;
            if (v instanceof String s) return Boolean.parseBoolean(s);
            throw new ClassCastException("Cannot cast " + v.getClass() + " to Boolean for column " + column);
        }

        @Override public byte[] getBytes(String column) {
            Object v = values.get(column);
            if (v == null) return null;
            if (v instanceof byte[] b) return b;
            throw new ClassCastException("Cannot cast " + v.getClass() + " to byte[] for column " + column);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <E extends Enum<E>> E getEnum(String column, Class<E> type) {
            Object v = values.get(column);
            if (v == null) return null;
            if (type.isInstance(v)) return (E) v;
            if (v instanceof String s) return Enum.valueOf(type, s);
            throw new ClassCastException("Cannot cast " + v.getClass() + " to " + type + " for column " + column);
        }

        @Override public void set(String column, Object value) { values.put(column, value); }
        @Override public void setLong(String column, long value) { values.put(column, value); }
        @Override public void setString(String column, String value) { values.put(column, value); }
        @Override public void setInstant(String column, Instant value) { values.put(column, value); }
        @Override public void setBoolean(String column, boolean value) { values.put(column, value); }

        @Override public Map<String, Object> asMap() {
            return new LinkedHashMap<>(values);
        }

        @Override public String toString() {
            return "Row{" + table + " " + values + "}";
        }
    }
}
