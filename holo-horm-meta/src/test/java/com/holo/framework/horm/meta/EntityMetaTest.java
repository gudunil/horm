package com.holo.framework.horm.meta;

import com.holo.framework.horm.meta.annotation.GenerationType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityMetaTest {

    // ── helpers ──────────────────────────────────────────────────────────

    private static FieldMeta<Long> idField() {
        return FieldMeta.<Long>builder()
            .name("id")
            .column("id")
            .type(Long.class)
            .id(true)
            .generationStrategy(GenerationType.IDENTITY)
            .insertable(false)
            .build();
    }

    private static FieldMeta<String> nameField() {
        return FieldMeta.<String>builder()
            .name("name")
            .column("user_name")
            .type(String.class)
            .length(100)
            .build();
    }

    private static FieldMeta<Integer> ageField() {
        return FieldMeta.<Integer>builder()
            .name("age")
            .column("age")
            .type(Integer.class)
            .insertable(false)
            .build();
    }

    private static EntityMeta<Object> buildMeta(List<FieldMeta<?>> fields) {
        return EntityMeta.builder()
            .type(Object.class)
            .tableName("users")
            .fields(fields)
            .build();
    }

    // ── Builder – happy path ─────────────────────────────────────────────

    @Test
    void builderCreatesEntityMetaWithAllFields() {
        FieldMeta<Long> id = idField();
        FieldMeta<String> name = nameField();
        Mapper<Object> mapper = new Mapper<Object>() {
            @Override public Object map(Row row) { return null; }
            @Override public Row toRow(Object entity) { return null; }
            @Override public Object getId(Object entity) { return null; }
            @Override public void setId(Object entity, Object id) {}
            @Override public Object getField(Object entity, String field) { return null; }
            @Override public void setField(Object entity, String field, Object value) {}
        };
        RelationMeta relation = RelationMeta.builder()
            .name("orders")
            .targetEntity(Object.class)
            .type(RelationType.HAS_MANY)
            .foreignKey("user_id")
            .build();
        FieldMeta<Integer> version = FieldMeta.<Integer>builder()
            .name("version").type(Integer.class).version(true).build();

        EntityMeta<Object> meta = EntityMeta.<Object>builder()
            .type(Object.class)
            .tableName("users")
            .schema("public")
            .dataSource("primary")
            .fields(List.of(id, name))
            .idField(id)
            .mapper(mapper)
            .relations(List.of(relation))
            .versionField(version)
            .build();

        assertThat(meta.type()).isEqualTo(Object.class);
        assertThat(meta.tableName()).isEqualTo("users");
        assertThat(meta.schema()).isEqualTo("public");
        assertThat(meta.dataSource()).isEqualTo("primary");
        assertThat(meta.fields()).containsExactly(id, name);
        assertThat(meta.idField()).isSameAs(id);
        assertThat(meta.mapper()).isSameAs(mapper);
        assertThat(meta.relations()).containsExactly(relation);
        assertThat(meta.versionField()).isSameAs(version);
    }

    @Test
    void builderDefaultsRelationsToEmptyList() {
        EntityMeta<Object> meta = buildMeta(List.of());
        assertThat(meta.relations()).isEmpty();
    }

    @Test
    void builderDefaultsOptionalFieldsToNull() {
        EntityMeta<Object> meta = buildMeta(List.of());
        assertThat(meta.schema()).isNull();
        assertThat(meta.dataSource()).isNull();
        assertThat(meta.idField()).isNull();
        assertThat(meta.mapper()).isNull();
        assertThat(meta.versionField()).isNull();
    }

    // ── Builder – required field validation ──────────────────────────────

    @Test
    void buildThrowsWhenTypeIsNull() {
        assertThatThrownBy(() -> EntityMeta.builder()
            .tableName("t")
            .fields(List.of())
            .build()
        ).isInstanceOf(NullPointerException.class)
         .hasMessageContaining("type");
    }

    @Test
    void buildThrowsWhenTableNameIsNull() {
        assertThatThrownBy(() -> EntityMeta.builder()
            .type(Object.class)
            .fields(List.of())
            .build()
        ).isInstanceOf(NullPointerException.class)
         .hasMessageContaining("tableName");
    }

    @Test
    void buildThrowsWhenFieldsIsNull() {
        assertThatThrownBy(() -> EntityMeta.builder()
            .type(Object.class)
            .tableName("t")
            .build()
        ).isInstanceOf(NullPointerException.class)
         .hasMessageContaining("fields");
    }

    // ── field() lookup ───────────────────────────────────────────────────

    @Test
    void fieldReturnsMatchingFieldByName() {
        FieldMeta<String> name = nameField();
        EntityMeta<Object> meta = buildMeta(List.of(idField(), name));

        assertThat(meta.field("name")).contains(name);
    }

    @Test
    void fieldReturnsEmptyForUnknownName() {
        EntityMeta<Object> meta = buildMeta(List.of(idField()));
        assertThat(meta.field("nonexistent")).isEmpty();
    }

    // ── fieldByColumn() lookup ───────────────────────────────────────────

    @Test
    void fieldByColumnReturnsMatchingFieldByColumnName() {
        FieldMeta<String> name = nameField();
        EntityMeta<Object> meta = buildMeta(List.of(idField(), name));

        assertThat(meta.fieldByColumn("user_name")).contains(name);
    }

    @Test
    void fieldByColumnReturnsEmptyForUnknownColumn() {
        EntityMeta<Object> meta = buildMeta(List.of(idField()));
        assertThat(meta.fieldByColumn("no_such_column")).isEmpty();
    }

    // ── insertableColumns() ──────────────────────────────────────────────

    @Test
    void insertableColumnsExcludesNonInsertableAndIdentityIdFields() {
        // id: insertable=false, generationStrategy=IDENTITY → excluded (both filters)
        // name: insertable=true → included
        // age: insertable=false → excluded
        EntityMeta<Object> meta = buildMeta(List.of(idField(), nameField(), ageField()));

        List<String> columns = meta.insertableColumns();
        assertThat(columns).containsExactly("user_name");
    }

    @Test
    void insertableColumnsIncludesManualIdField() {
        FieldMeta<Long> manualId = FieldMeta.<Long>builder()
            .name("id").column("id").type(Long.class)
            .id(true)
            .generationStrategy(GenerationType.MANUAL)
            .insertable(true)
            .build();
        EntityMeta<Object> meta = buildMeta(List.of(manualId, nameField()));

        assertThat(meta.insertableColumns()).containsExactly("id", "user_name");
    }

    @Test
    void insertableColumnsReturnsEmptyWhenAllExcluded() {
        EntityMeta<Object> meta = buildMeta(List.of(idField(), ageField()));
        assertThat(meta.insertableColumns()).isEmpty();
    }

    // ── immutable collections ────────────────────────────────────────────

    @Test
    void fieldsListIsImmutable() {
        EntityMeta<Object> meta = buildMeta(List.of(idField()));
        assertThatThrownBy(() -> meta.fields().add(nameField()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void relationsListIsImmutable() {
        EntityMeta<Object> meta = EntityMeta.<Object>builder()
            .type(Object.class)
            .tableName("t")
            .fields(List.of())
            .relations(List.of())
            .build();
        RelationMeta newRelation = RelationMeta.builder()
            .name("test")
            .targetEntity(Object.class)
            .type(RelationType.HAS_MANY)
            .build();
        assertThatThrownBy(() -> meta.relations().add(newRelation))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fieldsListIsDefensivelyCopied() {
        FieldMeta<Long> id = idField();
        java.util.ArrayList<FieldMeta<?>> mutable = new java.util.ArrayList<>();
        mutable.add(id);

        EntityMeta<Object> meta = buildMeta(mutable);

        // Mutating the original list must not affect the meta.
        mutable.add(nameField());
        assertThat(meta.fields()).hasSize(1);
    }
}
