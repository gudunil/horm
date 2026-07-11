package com.holo.framework.horm.meta;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowTest {

    // ── factory ──────────────────────────────────────────────────────────

    @Test
    void createReturnsMapRowWithTableName() {
        Row row = Row.create("users");
        assertThat(row.table()).isEqualTo("users");
        assertThat(row).isInstanceOf(Row.MapRow.class);
    }

    @Test
    void createWithNullTableDefaultsToEmpty() {
        Row row = Row.create(null);
        assertThat(row.table()).isEmpty();
    }

    // ── has / get ────────────────────────────────────────────────────────

    @Test
    void hasReturnsFalseForAbsentColumn() {
        Row row = Row.create("t");
        assertThat(row.has("missing")).isFalse();
    }

    @Test
    void hasReturnsFalseWhenValueIsNull() {
        Row row = Row.create("t");
        row.set("col", null);
        assertThat(row.has("col")).isFalse();
    }

    @Test
    void hasReturnsTrueWhenValueIsPresent() {
        Row row = Row.create("t");
        row.set("col", 42);
        assertThat(row.has("col")).isTrue();
    }

    @Test
    void getReturnsNullForAbsentColumn() {
        Row row = Row.create("t");
        assertThat(row.get("missing")).isNull();
    }

    @Test
    void getReturnsStoredValue() {
        Row row = Row.create("t");
        row.set("col", "hello");
        assertThat(row.get("col")).isEqualTo("hello");
    }

    // ── getLong ──────────────────────────────────────────────────────────

    @Test
    void getLongReturnsNullForAbsentColumn() {
        Row row = Row.create("t");
        assertThat(row.getLong("col")).isNull();
    }

    @Test
    void getLongFromLongValue() {
        Row row = Row.create("t");
        row.set("col", 42L);
        assertThat(row.getLong("col")).isEqualTo(42L);
    }

    @Test
    void getLongFromIntegerValue() {
        Row row = Row.create("t");
        row.set("col", 7);
        assertThat(row.getLong("col")).isEqualTo(7L);
    }

    @Test
    void getLongFromStringValue() {
        Row row = Row.create("t");
        row.set("col", "99");
        assertThat(row.getLong("col")).isEqualTo(99L);
    }

    @Test
    void getLongThrowsForUnsupportedType() {
        Row row = Row.create("t");
        row.set("col", new Object());
        assertThatThrownBy(() -> row.getLong("col"))
            .isInstanceOf(ClassCastException.class)
            .hasMessageContaining("Long");
    }

    // ── getInteger ───────────────────────────────────────────────────────

    @Test
    void getIntegerReturnsNullForAbsentColumn() {
        Row row = Row.create("t");
        assertThat(row.getInteger("col")).isNull();
    }

    @Test
    void getIntegerFromIntegerValue() {
        Row row = Row.create("t");
        row.set("col", 5);
        assertThat(row.getInteger("col")).isEqualTo(5);
    }

    @Test
    void getIntegerFromLongValue() {
        Row row = Row.create("t");
        row.set("col", 10L);
        assertThat(row.getInteger("col")).isEqualTo(10);
    }

    @Test
    void getIntegerFromStringValue() {
        Row row = Row.create("t");
        row.set("col", "123");
        assertThat(row.getInteger("col")).isEqualTo(123);
    }

    @Test
    void getIntegerThrowsForUnsupportedType() {
        Row row = Row.create("t");
        row.set("col", new Object());
        assertThatThrownBy(() -> row.getInteger("col"))
            .isInstanceOf(ClassCastException.class)
            .hasMessageContaining("Integer");
    }

    // ── getString ────────────────────────────────────────────────────────

    @Test
    void getStringReturnsNullForAbsentColumn() {
        Row row = Row.create("t");
        assertThat(row.getString("col")).isNull();
    }

    @Test
    void getStringFromStringValue() {
        Row row = Row.create("t");
        row.set("col", "hello");
        assertThat(row.getString("col")).isEqualTo("hello");
    }

    @Test
    void getStringCallsToStringOnNonStringValues() {
        Row row = Row.create("t");
        row.set("col", 42);
        assertThat(row.getString("col")).isEqualTo("42");
    }

    // ── getInstant ───────────────────────────────────────────────────────

    @Test
    void getInstantReturnsNullForAbsentColumn() {
        Row row = Row.create("t");
        assertThat(row.getInstant("col")).isNull();
    }

    @Test
    void getInstantFromInstantValue() {
        Instant now = Instant.now();
        Row row = Row.create("t");
        row.set("col", now);
        assertThat(row.getInstant("col")).isSameAs(now);
    }

    @Test
    void getInstantFromLongEpochMillis() {
        long millis = System.currentTimeMillis();
        Row row = Row.create("t");
        row.set("col", millis);
        assertThat(row.getInstant("col")).isEqualTo(Instant.ofEpochMilli(millis));
    }

    @Test
    void getInstantFromStringValue() {
        String iso = "2025-01-15T10:30:00Z";
        Row row = Row.create("t");
        row.set("col", iso);
        assertThat(row.getInstant("col")).isEqualTo(Instant.parse(iso));
    }

    @Test
    void getInstantThrowsForUnsupportedType() {
        Row row = Row.create("t");
        row.set("col", 3.14);
        assertThatThrownBy(() -> row.getInstant("col"))
            .isInstanceOf(ClassCastException.class)
            .hasMessageContaining("Instant");
    }

    // ── getBigDecimal ────────────────────────────────────────────────────

    @Test
    void getBigDecimalReturnsNullForAbsentColumn() {
        Row row = Row.create("t");
        assertThat(row.getBigDecimal("col")).isNull();
    }

    @Test
    void getBigDecimalFromBigDecimalValue() {
        BigDecimal bd = new BigDecimal("123.45");
        Row row = Row.create("t");
        row.set("col", bd);
        assertThat(row.getBigDecimal("col")).isEqualTo(bd);
    }

    @Test
    void getBigDecimalFromDoubleValue() {
        Row row = Row.create("t");
        row.set("col", 1.5);
        assertThat(row.getBigDecimal("col")).isEqualTo(BigDecimal.valueOf(1.5));
    }

    @Test
    void getBigDecimalFromStringValue() {
        Row row = Row.create("t");
        row.set("col", "99.99");
        assertThat(row.getBigDecimal("col")).isEqualTo(new BigDecimal("99.99"));
    }

    @Test
    void getBigDecimalThrowsForUnsupportedType() {
        Row row = Row.create("t");
        row.set("col", new Object());
        assertThatThrownBy(() -> row.getBigDecimal("col"))
            .isInstanceOf(ClassCastException.class)
            .hasMessageContaining("BigDecimal");
    }

    // ── getBoolean ───────────────────────────────────────────────────────

    @Test
    void getBooleanReturnsNullForAbsentColumn() {
        Row row = Row.create("t");
        assertThat(row.getBoolean("col")).isNull();
    }

    @Test
    void getBooleanFromBooleanValue() {
        Row row = Row.create("t");
        row.set("col", true);
        assertThat(row.getBoolean("col")).isTrue();
    }

    @Test
    void getBooleanFromNumberNonZero() {
        Row row = Row.create("t");
        row.set("col", 1);
        assertThat(row.getBoolean("col")).isTrue();
    }

    @Test
    void getBooleanFromNumberZero() {
        Row row = Row.create("t");
        row.set("col", 0);
        assertThat(row.getBoolean("col")).isFalse();
    }

    @Test
    void getBooleanFromStringValue() {
        Row row = Row.create("t");
        row.set("col", "true");
        assertThat(row.getBoolean("col")).isTrue();
    }

    @Test
    void getBooleanThrowsForUnsupportedType() {
        Row row = Row.create("t");
        row.set("col", new Object());
        assertThatThrownBy(() -> row.getBoolean("col"))
            .isInstanceOf(ClassCastException.class)
            .hasMessageContaining("Boolean");
    }

    // ── getBytes ─────────────────────────────────────────────────────────

    @Test
    void getBytesReturnsNullForAbsentColumn() {
        Row row = Row.create("t");
        assertThat(row.getBytes("col")).isNull();
    }

    @Test
    void getBytesFromByteArrayValue() {
        byte[] data = {1, 2, 3};
        Row row = Row.create("t");
        row.set("col", data);
        assertThat(row.getBytes("col")).isEqualTo(data);
    }

    @Test
    void getBytesThrowsForUnsupportedType() {
        Row row = Row.create("t");
        row.set("col", "not bytes");
        assertThatThrownBy(() -> row.getBytes("col"))
            .isInstanceOf(ClassCastException.class)
            .hasMessageContaining("byte[]");
    }

    // ── getEnum ──────────────────────────────────────────────────────────

    enum Color { RED, GREEN, BLUE }

    @Test
    void getEnumReturnsNullForAbsentColumn() {
        Row row = Row.create("t");
        assertThat(row.getEnum("col", Color.class)).isNull();
    }

    @Test
    void getEnumFromEnumValue() {
        Row row = Row.create("t");
        row.set("col", Color.GREEN);
        assertThat(row.getEnum("col", Color.class)).isEqualTo(Color.GREEN);
    }

    @Test
    void getEnumFromStringValue() {
        Row row = Row.create("t");
        row.set("col", "BLUE");
        assertThat(row.getEnum("col", Color.class)).isEqualTo(Color.BLUE);
    }

    @Test
    void getEnumThrowsForUnsupportedType() {
        Row row = Row.create("t");
        row.set("col", 42);
        assertThatThrownBy(() -> row.getEnum("col", Color.class))
            .isInstanceOf(ClassCastException.class)
            .hasMessageContaining("Color");
    }

    // ── typed setters ────────────────────────────────────────────────────

    @Test
    void setLongStoresValue() {
        Row row = Row.create("t");
        row.setLong("id", 100L);
        assertThat(row.getLong("id")).isEqualTo(100L);
    }

    @Test
    void setStringStoresValue() {
        Row row = Row.create("t");
        row.setString("name", "Alice");
        assertThat(row.getString("name")).isEqualTo("Alice");
    }

    @Test
    void setInstantStoresValue() {
        Instant now = Instant.now();
        Row row = Row.create("t");
        row.setInstant("created", now);
        assertThat(row.getInstant("created")).isSameAs(now);
    }

    @Test
    void setBooleanStoresValue() {
        Row row = Row.create("t");
        row.setBoolean("active", true);
        assertThat(row.getBoolean("active")).isTrue();
    }

    // ── asMap ────────────────────────────────────────────────────────────

    @Test
    void asMapReturnsSnapshotCopy() {
        Row row = Row.create("t");
        row.set("a", 1);
        row.set("b", "two");

        Map<String, Object> snapshot = row.asMap();
        assertThat(snapshot).containsEntry("a", 1).containsEntry("b", "two");

        // Mutating the snapshot must not affect the row.
        snapshot.put("c", 3);
        assertThat(row.has("c")).isFalse();
    }

    @Test
    void asMapReturnsEmptyMapForEmptyRow() {
        Row row = Row.create("t");
        assertThat(row.asMap()).isEmpty();
    }

    // ── toString ─────────────────────────────────────────────────────────

    @Test
    void toStringIncludesTableAndValues() {
        Row row = Row.create("users");
        row.set("id", 1);
        String s = row.toString();
        assertThat(s).contains("users");
        assertThat(s).contains("id=1");
    }

    @Test
    void toStringForEmptyRow() {
        Row row = Row.create("empty_table");
        assertThat(row.toString()).startsWith("Row{empty_table");
    }
}
