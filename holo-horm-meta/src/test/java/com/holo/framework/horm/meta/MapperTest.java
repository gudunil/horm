package com.holo.framework.horm.meta;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapperTest {

    static class SimpleMapper implements Mapper<Object> {
        @Override public Object map(Row row) { return null; }
        @Override public Row toRow(Object entity) { return null; }
        @Override public Object getId(Object entity) { return null; }
        @Override public void setId(Object entity, Object id) {}
        @Override public Object getField(Object entity, String field) { return null; }
        @Override public void setField(Object entity, String field, Object value) {}
    }

    private final SimpleMapper mapper = new SimpleMapper();

    @Test
    void setRelationDefaultThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> mapper.setRelation(new Object(), "orders", List.of()))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("orders");
    }

    @Test
    void getRelationDefaultThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> mapper.getRelation(new Object(), "orders"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("orders");
    }

    @Test
    void incrementVersionDefaultThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> mapper.incrementVersion(new Object()))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("incrementVersion");
    }
}
