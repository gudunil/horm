package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.annotation.GenerationType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SqlTemplates}, verifying that SQL strings are
 * correctly pre-generated from {@link EntityMeta} at construction time.
 *
 * <p>Coverage focuses on the branching logic of the constructor:
 * <ul>
 *   <li>presence/absence of an {@code @Id} field;</li>
 *   <li>presence/absence of a {@code @Version} field;</li>
 *   <li>schema-qualified table names;</li>
 *   <li>insertable column filtering for the INSERT template.</li>
 * </ul>
 *
 * <p>No Mockito is used — {@link SqlTemplates} is a pure computation over
 * {@link EntityMeta}, so hand-built metadata is sufficient.
 */
class SqlTemplatesTest {

    @SuppressWarnings("unchecked")
    private static EntityMeta<TestEntity> buildMeta(String tableName,
                                                    String schema,
                                                    FieldMeta<?> idField,
                                                    FieldMeta<?> versionField,
                                                    List<FieldMeta<?>> extraFields) {
        java.util.ArrayList<FieldMeta<?>> fields = new java.util.ArrayList<>();
        if (idField != null) fields.add(idField);
        if (versionField != null) fields.add(versionField);
        if (extraFields != null) fields.addAll(extraFields);

        EntityMeta.Builder<TestEntity> b = EntityMeta.<TestEntity>builder()
            .type(TestEntity.class)
            .tableName(tableName)
            .fields(List.copyOf(fields))
            .mapper((Mapper<TestEntity>) null);
        if (schema != null) b.schema(schema);
        if (idField != null) b.idField(idField);
        if (versionField != null) b.versionField(versionField);
        return b.build();
    }

    private static FieldMeta<Long> idField(String column) {
        return FieldMeta.<Long>builder()
            .name("id").column(column).type(Long.class).id(true)
            .generationStrategy(GenerationType.IDENTITY)
            .build();
    }

    private static FieldMeta<Long> manualIdField(String column) {
        return FieldMeta.<Long>builder()
            .name("id").column(column).type(Long.class).id(true)
            .generationStrategy(GenerationType.MANUAL)
            .build();
    }

    private static FieldMeta<Integer> versionField(String column) {
        return FieldMeta.<Integer>builder()
            .name("version").column(column).type(Integer.class)
            .insertable(false).updatable(true).version(true)
            .build();
    }

    private static FieldMeta<String> dataField(String name, String column, boolean insertable) {
        return FieldMeta.<String>builder()
            .name(name).column(column).type(String.class)
            .insertable(insertable)
            .build();
    }

    @Test
    void buildsFindByIdAndDeleteByIdWhenIdFieldPresent() {
        EntityMeta<TestEntity> meta = buildMeta("test_entities", null,
            idField("id"), null, List.of());
        SqlTemplates t = new SqlTemplates(meta);

        assertThat(t.findById()).isEqualTo("SELECT * FROM test_entities WHERE id = ?");
        assertThat(t.deleteById()).isEqualTo("DELETE FROM test_entities WHERE id = ?");
    }

    @Test
    void findByIdAndDeleteByIdAreNullWhenNoIdField() {
        EntityMeta<TestEntity> meta = buildMeta("no_id_table", null,
            null, null, List.of());
        SqlTemplates t = new SqlTemplates(meta);

        assertThat(t.findById()).isNull();
        assertThat(t.deleteById()).isNull();
        assertThat(t.deleteByIdAndVersion()).isNull();
    }

    @Test
    void buildsInsertSqlWithInsertableNonIdColumns() {
        FieldMeta<String> email = dataField("email", "email", true);
        FieldMeta<String> readOnly = dataField("computed", "computed", false);
        EntityMeta<TestEntity> meta = buildMeta("test_entities", null,
            idField("id"), null, List.of(email, readOnly));
        SqlTemplates t = new SqlTemplates(meta);

        // insertColumns only contains insertable=true, non-id, non-version fields
        assertThat(t.insertColumns()).containsExactly("email");
        assertThat(t.insert()).isEqualTo("INSERT INTO test_entities (email) VALUES (?)");
    }

    @Test
    void buildsInsertSqlIncludingManualIdColumn() {
        FieldMeta<String> email = dataField("email", "email", true);
        EntityMeta<TestEntity> meta = buildMeta("test_entities", null,
            manualIdField("id"), null, List.of(email));
        SqlTemplates t = new SqlTemplates(meta);

        assertThat(t.insertColumns()).containsExactly("id", "email");
        assertThat(t.insert()).isEqualTo("INSERT INTO test_entities (id, email) VALUES (?, ?)");
    }

    @Test
    void buildsDeleteByIdAndVersionWhenVersionFieldPresent() {
        EntityMeta<TestEntity> meta = buildMeta("test_entities", null,
            idField("id"), versionField("ver"), List.of());
        SqlTemplates t = new SqlTemplates(meta);

        assertThat(t.deleteByIdAndVersion())
            .isEqualTo("DELETE FROM test_entities WHERE id = ? AND ver = ?");
    }

    @Test
    void deleteByIdAndVersionIsNullWhenNoVersionField() {
        EntityMeta<TestEntity> meta = buildMeta("test_entities", null,
            idField("id"), null, List.of());
        SqlTemplates t = new SqlTemplates(meta);

        assertThat(t.deleteByIdAndVersion()).isNull();
    }

    @Test
    void qualifiedTableUsesSchemaPrefixWhenSchemaNonNull() {
        EntityMeta<TestEntity> meta = buildMeta("entities", "app_schema",
            idField("id"), null, List.of());
        SqlTemplates t = new SqlTemplates(meta);

        assertThat(t.findAll()).isEqualTo("SELECT * FROM app_schema.entities");
        assertThat(t.count()).isEqualTo("SELECT COUNT(*) FROM app_schema.entities");
        assertThat(t.findById()).isEqualTo("SELECT * FROM app_schema.entities WHERE id = ?");
    }

    @Test
    void findAllAndCountSqlAreUnconditional() {
        EntityMeta<TestEntity> meta = buildMeta("simple", null,
            idField("id"), null, List.of());
        SqlTemplates t = new SqlTemplates(meta);

        assertThat(t.findAll()).isEqualTo("SELECT * FROM simple");
        assertThat(t.count()).isEqualTo("SELECT COUNT(*) FROM simple");
    }

    /** CRTP subtype used only as a registry key for these tests. */
    static final class TestEntity extends Model<TestEntity> {
    }
}
