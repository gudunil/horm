package com.holo.framework.horm.migration;

import com.holo.framework.horm.migration.internal.DefaultTableBuilder;
import com.holo.framework.horm.migration.internal.TableDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link com.holo.framework.horm.migration.internal.DefaultTableBuilder} 单元测试。
 */
class DefaultTableBuilderTest {

    private final TableDefinition tableDef = new TableDefinition("test_table");
    private final DefaultTableBuilder builder = new DefaultTableBuilder(tableDef);

    @Test
    void bigIncrementsCreatesPrimaryKey() {
        builder.bigIncrements("id");
        assertThat(tableDef.getColumns()).hasSize(1);
        assertThat(tableDef.getColumns().get(0).getName()).isEqualTo("id");
        assertThat(tableDef.getColumns().get(0).getType()).isEqualTo("BIGINT");
        assertThat(tableDef.getColumns().get(0).isPrimaryKey()).isTrue();
        assertThat(tableDef.getColumns().get(0).isAutoIncrement()).isTrue();
    }

    @Test
    void incrementsCreatesIntPrimaryKey() {
        builder.increments("id");
        assertThat(tableDef.getColumns().get(0).getType()).isEqualTo("INT");
        assertThat(tableDef.getColumns().get(0).isPrimaryKey()).isTrue();
    }

    @Test
    void allColumnTypes() {
        builder.bigInteger("col1");
        builder.integer("col2");
        builder.smallInteger("col3");
        builder.tinyInteger("col4");
        builder.string("col5", 128);
        builder.text("col6");
        builder.decimal("col7", 10, 2);
        builder.float_("col8");
        builder.double_("col9");
        builder.boolean_("col10");
        builder.date("col11");
        builder.time("col12");
        builder.datetime("col13");
        builder.timestamp("col14");

        assertThat(tableDef.getColumns()).hasSize(14);
        assertThat(tableDef.getColumns().get(0).getType()).isEqualTo("BIGINT");
        assertThat(tableDef.getColumns().get(1).getType()).isEqualTo("INT");
        assertThat(tableDef.getColumns().get(2).getType()).isEqualTo("SMALLINT");
        assertThat(tableDef.getColumns().get(3).getType()).isEqualTo("TINYINT");
        assertThat(tableDef.getColumns().get(4).getType()).isEqualTo("VARCHAR");
        assertThat(tableDef.getColumns().get(4).getLength()).isEqualTo(128);
        assertThat(tableDef.getColumns().get(5).getType()).isEqualTo("TEXT");
        assertThat(tableDef.getColumns().get(6).getType()).isEqualTo("DECIMAL");
        assertThat(tableDef.getColumns().get(6).getPrecision()).isEqualTo(10);
        assertThat(tableDef.getColumns().get(6).getScale()).isEqualTo(2);
        assertThat(tableDef.getColumns().get(7).getType()).isEqualTo("FLOAT");
        assertThat(tableDef.getColumns().get(8).getType()).isEqualTo("DOUBLE");
        assertThat(tableDef.getColumns().get(9).getType()).isEqualTo("BOOLEAN");
        assertThat(tableDef.getColumns().get(10).getType()).isEqualTo("DATE");
        assertThat(tableDef.getColumns().get(11).getType()).isEqualTo("TIME");
        assertThat(tableDef.getColumns().get(12).getType()).isEqualTo("DATETIME");
        assertThat(tableDef.getColumns().get(13).getType()).isEqualTo("TIMESTAMP");
    }

    @Test
    void timestampsAddsTwoColumns() {
        builder.timestamps();
        assertThat(tableDef.getColumns()).hasSize(2);
        assertThat(tableDef.getColumns().get(0).getName()).isEqualTo("created_at");
        assertThat(tableDef.getColumns().get(1).getName()).isEqualTo("updated_at");
    }

    @Test
    void foreignKey() {
        builder.bigInteger("user_id");
        builder.foreignKey("user_id", "users", "id");

        assertThat(tableDef.getForeignKeys()).hasSize(1);
        assertThat(tableDef.getForeignKeys().get(0).getColumnName()).isEqualTo("user_id");
        assertThat(tableDef.getForeignKeys().get(0).getReferencedTable()).isEqualTo("users");
        assertThat(tableDef.getForeignKeys().get(0).getReferencedColumn()).isEqualTo("id");
    }

    @Test
    void dropColumn() {
        builder.dropColumn("obsolete");
        assertThat(tableDef.getColumns()).hasSize(1);
        assertThat(tableDef.getColumns().get(0).getType()).isEqualTo("DROP");
    }

    @Test
    void modifyColumn() {
        builder.modifyColumn("email").notNull();
        assertThat(tableDef.getColumns()).hasSize(1);
        assertThat(tableDef.getColumns().get(0).getType()).isEqualTo("MODIFY");
        assertThat(tableDef.getColumns().get(0).isNullable()).isFalse();
    }

    @Test
    void getTableDefinition() {
        builder.bigIncrements("id");
        assertThat(builder.getTableDefinition()).isSameAs(tableDef);
    }
}