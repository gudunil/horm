package com.holo.framework.horm.migration;

import com.holo.framework.horm.migration.internal.ColumnDefinition;
import com.holo.framework.horm.migration.internal.DefaultColumnBuilder;
import com.holo.framework.horm.migration.internal.DefaultTableBuilder;
import com.holo.framework.horm.migration.internal.TableDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link com.holo.framework.horm.migration.internal.DefaultColumnBuilder} 单元测试。
 */
class DefaultColumnBuilderTest {

    @Test
    void referencesSetsForeignKeyFields() {
        TableDefinition tableDef = new TableDefinition("orders");
        DefaultTableBuilder tableBuilder = new DefaultTableBuilder(tableDef);
        tableBuilder.bigInteger("user_id").references("users", "id");

        ColumnDefinition col = tableDef.getColumns().get(0);
        assertThat(col.getReferencedTable()).isEqualTo("users");
        assertThat(col.getReferencedColumn()).isEqualTo("id");
    }

    @Test
    void commentSetsComment() {
        TableDefinition tableDef = new TableDefinition("users");
        DefaultTableBuilder tableBuilder = new DefaultTableBuilder(tableDef);
        tableBuilder.string("status", 16).comment("active/inactive");

        ColumnDefinition col = tableDef.getColumns().get(0);
        assertThat(col.getComment()).isEqualTo("active/inactive");
    }

    @Test
    void primaryKeySetsNotNull() {
        ColumnDefinition col = new ColumnDefinition("id", "BIGINT");
        DefaultColumnBuilder builder = new DefaultColumnBuilder(col);

        builder.primaryKey();

        assertThat(col.isPrimaryKey()).isTrue();
        assertThat(col.isNullable()).isFalse();
    }

    @Test
    void autoIncrementSetsFlag() {
        ColumnDefinition col = new ColumnDefinition("id", "INT");
        DefaultColumnBuilder builder = new DefaultColumnBuilder(col);

        builder.autoIncrement();

        assertThat(col.isAutoIncrement()).isTrue();
    }

    @Test
    void defaultValSetsValue() {
        ColumnDefinition col = new ColumnDefinition("status", "VARCHAR");
        DefaultColumnBuilder builder = new DefaultColumnBuilder(col);

        builder.defaultVal("'active'");

        assertThat(col.getDefaultValue()).isEqualTo("'active'");
    }

    @Test
    void notNullAndNullable() {
        ColumnDefinition col = new ColumnDefinition("email", "VARCHAR");
        DefaultColumnBuilder builder = new DefaultColumnBuilder(col);

        assertThat(col.isNullable()).isTrue();

        builder.notNull();
        assertThat(col.isNullable()).isFalse();

        builder.nullable();
        assertThat(col.isNullable()).isTrue();
    }

    @Test
    void getDefinitionReturnsSameInstance() {
        ColumnDefinition col = new ColumnDefinition("name", "VARCHAR");
        DefaultColumnBuilder builder = new DefaultColumnBuilder(col);

        assertThat(builder.getDefinition()).isSameAs(col);
    }
}